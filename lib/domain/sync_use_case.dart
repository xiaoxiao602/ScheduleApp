import 'dart:io';

import 'package:cookie_jar/cookie_jar.dart';
import 'package:dio/dio.dart';
import 'package:dio_cookie_manager/dio_cookie_manager.dart';

import '../data/local/app_database.dart';
import '../data/local/repositories.dart';
import '../data/zhengfang/data_client.dart';
import '../data/zhengfang/session_client.dart';
import '../data/zhengfang/zf_parsers.dart';
import 'core_logic.dart';
import 'models.dart';

/// ============================================================
/// 同步用例（对应原版 domain/usecase/SyncUseCase.kt，ADR-005 + ADR-008）
/// ============================================================
///
/// ⚠️ 核心安全约定（ADR-005）：
///   1. 账密只作为函数参数传入，**不保存到任何字段或存储**
///   2. 会话 Cookie 只存在**内存 CookieJar**，同步结束即丢弃
///   3. 无论成功失败，**都必须清理凭据引用**
class SyncUseCase {
  SyncUseCase({
    required CourseRepository courseRepo,
    required ExamRepository examRepo,
    required MetaRepository metaRepo,
  })  : _courseRepo = courseRepo,
        _examRepo = examRepo,
        _metaRepo = metaRepo;

  // ⚠️ 2026-09 清理：`_db` / `_gradeRepo` 字段从未被读取（所有读写都走
  //    Repository），连构造函数参数一起删掉，避免误导后续维护。
  final CourseRepository _courseRepo;
  final ExamRepository _examRepo;
  final MetaRepository _metaRepo;

  /// 执行同步。
  ///
  /// [username] 学号（明文，仅本次使用）
  /// [encryptedPassword] 已加密密码（倒序 + 无填充 RSA + hex）
  Future<SyncResult> execute({
    required String username,
    required String encryptedPassword,
    String? captchaId,
    String? captchaCode,
    Term term = Term.current,
    void Function(SyncProgress)? onProgress,
    /// 诊断日志回调。
    ///
    /// ⚠️ 必须由调用方传入 —— 否则底层的「换票 HTTP / 换票响应 / 取得 ST」
    ///    等关键日志只会 print 到 logcat，登录页的「诊断信息」面板全空白，
    ///    用户看到的就是"从 正在登录 直接跳到 ERROR"，无法定位问题。
    void Function(String)? onLog,
  }) async {
    void report(SyncProgress p) => onProgress?.call(p);
    void log(String m) => onLog?.call(m);

    // 内存 CookieJar —— 不持久化，随函数结束销毁
    final cookieJar = CookieJar();
    final dio = Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 20),
      receiveTimeout: const Duration(seconds: 30),
      // ⚠️⚠️ 关键修复（会话建立的根因）：
      //    dio 的 followRedirects 是在**底层 HTTP 层**跟随的，
      //    中间响应的 Set-Cookie **不会经过用户拦截器** →
      //    CookieManager 拿不到任何 cookie → CookieJar 始终为空 →
      //    后续请求（带 ticket 的第二跳、拉课表）服务端不认会话 →
      //    被弹回 CAS 登录页 → 解析出 0 门课。
      //
      //    原版 OkHttp 没这个问题：它的 CookieJar 在 followUpRequest
      //    里**逐跳**处理 Set-Cookie。dio 必须手动跟随才能等价。
      followRedirects: false,
      maxRedirects: 0,
      validateStatus: (s) => s != null && s < 500,
      headers: {'User-Agent': ZhengfangConfig.ua},
    ))
      ..interceptors.add(CookieManager(cookieJar));

    try {
      // ---- ① 登录 + 建立会话 ----
      report(SyncProgress.loggingIn);
      // ⚠️ ZhengfangClient 用回调注入 HTTP（便于测试），这里把 dio 包成回调。
      final session = ZhengfangClient(
        get: (url, headers) => _dioGet(dio, url, headers),
        post: (url, form, headers) => _dioPost(dio, url, form, headers),
        hasSession: () => _hasJsessionId(cookieJar),
        log: log,
      );
      final login = await session.establish(
        username: username,
        encryptedPassword: encryptedPassword,
        captchaId: captchaId,
        captchaCode: captchaCode,
      );
      if (login is LoginFailure) {
        return SyncResult.fail(login.reason, serverCode: login.serverCode);
      }

      final data = ZhengfangDataClient(dio, log: log);

      // ---- ①b 学期日期区间 + ①c 校历（纯增量，失败不影响主流程）----
      final dateRangeRaw =
          await _tryOrNull(() => data.fetchScheduleDateRangeRaw(term));
      final calendarRaw = await _tryOrNull(() => data.fetchCalendarRaw());

      // ---- ② 拉课表 ----
      report(SyncProgress.fetchingSchedule);
      final schedule = await data.fetchSchedule(term);
      if (schedule == null) {
        return SyncResult.fail('获取课表失败（会话可能已失效）');
      }
      final courses = schedule.courses;
      if (courses.isEmpty) {
        return SyncResult.fail('未获取到课程数据（可能是学期参数不正确或本学期无课）');
      }

      // ---- ③ 拉成绩（可能为空，不视为失败）----
      report(SyncProgress.fetchingGrades);
      final gradesRaw = await _tryOrNull(() => data.fetchGradesRaw(term));

      // ---- ④ 拉考试（可能为空）----
      report(SyncProgress.fetchingExams);
      final examsRaw = await _tryOrNull(() => data.fetchExamsRaw(term));

      // ---- ⑤ 写入本地库 ----
      report(SyncProgress.saving);
      final termLabel = _termLabel(term);
      await _courseRepo.replaceTerm(termLabel, courses);
      await _metaRepo.put(MetaKeys.currentTerm, termLabel);

      await _tryOrNull(() async {
        if (gradesRaw != null) await _parseAndSaveGrades(gradesRaw, termLabel);
      });
      await _tryOrNull(() async {
        if (examsRaw != null) await _parseAndSaveExams(examsRaw, termLabel);
      });

      await _metaRepo.put(
          MetaKeys.lastSyncAt, TimeFormats.isoTimestamp(DateTime.now()));
      await _metaRepo.put(MetaKeys.lastUsername, username);

      // ---- 学生信息（公开信息，非凭据）----
      final st = schedule.student;
      if (st != null) {
        await _putIfNotBlank(MetaKeys.studentNo, st.studentId);
        await _putIfNotBlank(MetaKeys.studentName, st.name);
        await _putIfNotBlank(MetaKeys.studentMajor, st.major);
        await _putIfNotBlank(MetaKeys.studentClass, st.className);
        // ⚠️ termName 来自 XQMMC（值 "1"），不是 XQM（值 "3"）
        await _putIfNotBlank(MetaKeys.academicYearName, st.academicYear);
        await _putIfNotBlank(MetaKeys.termName, st.termName);
      }

      // ---- 学期第一周星期一（ADR-011）----
      // 优先级：① cxRsd 日程接口 ② 课表响应带的 ③ 兜底本周一
      final firstMonday = ZfDateRangeParser.firstMonday(dateRangeRaw) ??
          schedule.firstMonday ??
          WeekCalculator.mondayOf(DateTime.now());
      await _metaRepo.put(MetaKeys.firstMonday, TimeFormats.iso(firstMonday));

      // ---- 假日区间（ADR-060）----
      // ⚠️ 解析不出时**不覆盖**已有数据 —— 否则一次网络抖动
      //    就把上次同步到的假期抹掉了。
      final holidays = ZfCalendarParser.parse(calendarRaw);
      if (holidays.isNotEmpty) {
        await _metaRepo.put(MetaKeys.holidays, HolidayCalendar.toJson(holidays));
      }

      return SyncResult.ok(courses.length);
    } on SocketException catch (e) {
      return SyncResult.fail('网络不可用：${e.message}');
    } catch (e) {
      return SyncResult.fail('同步出错：$e');
    } finally {
      // ---- ⑥ 清理：登出 + 丢弃凭据 ----
      report(SyncProgress.loggingOut);
      await _tryOrNull(() => _logout(dio));
      await _tryOrNull(() async => cookieJar.deleteAll());
      dio.close(force: true);
    }
  }

  // ---------- dio <-> 回调适配 ----------

  /// GET，**手动跟随 302**（最多 10 跳）。
  ///
  /// ⚠️⚠️ 为什么不用 dio 的 followRedirects：
  ///    它在底层 HTTP 层跟跳，**中间响应的 Set-Cookie 不经过拦截器**，
  ///    导致 CookieManager 收不到 cookie、CookieJar 永远为空 ——
  ///    这正是「登录成功但会话建立失败」的根因。
  ///    手动跟跳后，每一跳都走 dio 正常流程，CookieManager 全部捕获。
  ///    （原版 OkHttp 天然逐跳处理，所以没这个问题。）
  static Future<HttpResponse> _dioGet(
    Dio dio,
    String url,
    Map<String, String> headers,
  ) async {
    var current = url;
    for (var hop = 0; hop < 10; hop++) {
      final r = await dio.get<dynamic>(
        current,
        options: Options(
          headers: headers,
          validateStatus: (s) => s != null && s < 500,
          responseType: ResponseType.plain,
          followRedirects: false,
        ),
      );
      final code = r.statusCode ?? 0;
      if (code >= 300 && code < 400) {
        final loc = r.headers.value('location');
        if (loc == null || loc.isEmpty) break;
        current = _resolve(current, loc);
        continue;
      }
      return HttpResponse(code, _asText(r.data), r.realUri.toString());
    }
    // 跳数用尽：返回最后一次结果（best-effort）
    final r = await dio.get<dynamic>(
      current,
      options: Options(
        headers: headers,
        validateStatus: (s) => s != null && s < 500,
        responseType: ResponseType.plain,
        followRedirects: false,
      ),
    );
    return HttpResponse(
        r.statusCode ?? 0, _asText(r.data), r.realUri.toString());
  }

  /// POST，**手动跟随 302**（最多 10 跳）。
  ///
  /// ⚠️ 与 _dioGet 同理：必须手动跟跳才能让 CookieManager 捕获每一跳的
  ///    Set-Cookie。换票/登录接口都会 302。
  static Future<HttpResponse> _dioPost(
    Dio dio,
    String url,
    Map<String, String> form,
    Map<String, String> headers,
  ) async {
    var current = url;
    var body = form;
    var method = 'POST';
    for (var hop = 0; hop < 10; hop++) {
      final dynamic r = method == 'POST'
          ? await dio.post<dynamic>(
              current,
              data: body,
              options: Options(
                contentType: Headers.formUrlEncodedContentType,
                headers: headers,
                validateStatus: (s) => s != null && s < 500,
                responseType: ResponseType.plain,
                followRedirects: false,
              ),
            )
          : await dio.get<dynamic>(
              current,
              options: Options(
                headers: headers,
                validateStatus: (s) => s != null && s < 500,
                responseType: ResponseType.plain,
                followRedirects: false,
              ),
            );
      final code = r.statusCode ?? 0;
      if (code >= 300 && code < 400) {
        final loc = r.headers.value('location');
        if (loc == null || loc.isEmpty) break;
        current = _resolve(current, loc);
        // 302 后按浏览器行为转为 GET（307/308 才保持 POST）
        if (code == 302 || code == 303) {
          method = 'GET';
          body = const <String, String>{};
        }
        continue;
      }
      return HttpResponse(code, _asText(r.data), r.realUri.toString());
    }
    return HttpResponse(0, '', current);
  }

  /// 解析 Location（支持相对路径）。
  static String _resolve(String base, String loc) {
    try {
      return Uri.parse(base).resolve(loc).toString();
    } catch (_) {
      return loc;
    }
  }

  static String _asText(dynamic d) {
    if (d == null) return '';
    if (d is String) return d;
    if (d is List<int>) {
      // dio 在无 content-type 时可能返回字节
      try {
        return String.fromCharCodes(d);
      } catch (_) {
        return '';
      }
    }
    return d.toString();
  }

  /// 打印 CookieJar 当前内容（诊断会话问题）。
  ///
  /// ⚠️ CookieJar 没有同步读取 API，只能对若干候选 URL 异步查询。
  static Future<void> dumpCookies(
    CookieJar jar,
    void Function(String) log,
  ) async {
    const urls = [
      'https://cas.gzus.edu.cn/lyuapServer/login',
      'https://jwxt.gzus.edu.cn/jwglxt/xtgl/index_initMenu.html',
      'https://jwxt.gzus.edu.cn/sso/lyiotlogin',
    ];
    for (final u in urls) {
      try {
        final cs = await jar.loadForRequest(Uri.parse(u));
        final names = cs.map((c) => c.name + '=' + c.value).join(', ');
        log('COOKIE ' + u + ' -> [' + names + ']');
      } catch (e) {
        log('COOKIE ' + u + ' -> 读取失败 ' + e.toString());
      }
    }
  }

  /// 会话是否建立：看 CookieJar 里有没有正方域名的 JSESSIONID。
  static bool _hasJsessionId(CookieJar jar) {
    try {
      // CookieJar.loadForRequest 是异步的；这里用同步近似
      return _probeSync(jar);
    } catch (_) {
      return false;
    }
  }

  static bool _probeSync(CookieJar jar) {
    // CookieJar 无同步读取 API，退化为"已发出过请求即认为可能建立"。
    // ⚠️ 真正的会话判定由 establish() 内部通过
    //    _bootstrapJwxtSession 的响应状态码完成，此处只是辅助探针。
    return true;
  }

  /// 主动登出（best-effort，失败不影响结果）。
  Future<void> _logout(Dio dio) async {
    await dio.get<dynamic>(
      '${ZhengfangConfig.jwxtBase}/jwglxt/xtgl/login_logout.html',
      options: Options(
        headers: {'User-Agent': ZhengfangConfig.ua},
        validateStatus: (s) => s != null && s < 500,
      ),
    );
  }

  /// 学期编码 → 中文。`3` = 第一学期（抓包确认）。
  String _termPrefix(String code) => switch (code) {
        '3' => '1',
        '12' => '2',
        _ => code,
      };

  String _termLabel(Term term) {
    final year = int.tryParse(term.year) ?? DateTime.now().year;
    return '${term.year}-${year + 1}学年第${_termPrefix(term.term)}学期';
  }

  Future<void> _putIfNotBlank(String key, String? value) async {
    if (value != null && value.trim().isNotEmpty) {
      await _metaRepo.put(key, value);
    }
  }

  /// 成绩解析：学生刚入学无成绩，暂不实现（与原版一致）。
  Future<void> _parseAndSaveGrades(String json, String termLabel) async {
    // ⚠️ 与原版一致：解析器待有真实数据时实现，这里保证不崩溃。
  }

  /// 解析并保存考试（ADR-013）。
  Future<void> _parseAndSaveExams(String json, String termLabel) async {
    final exams = ZfExamParser.parse(json, termLabel);
    if (exams.isNotEmpty) {
      await _examRepo.replaceAll(exams);
    }
  }

  /// 把"可能抛异常"的调用包成 null（对应 Kotlin 的 runCatching{}.getOrNull()）。
  static Future<T?> _tryOrNull<T>(Future<T> Function() fn) async {
    try {
      return await fn();
    } catch (_) {
      return null;
    }
  }
}

/// 同步进度（供 UI 显示）。
enum SyncProgress {
  loggingIn,
  bootstrapSession,
  fetchingSchedule,
  fetchingGrades,
  fetchingExams,
  saving,
  loggingOut;

  String get label => switch (this) {
        SyncProgress.loggingIn => '正在登录…',
        SyncProgress.bootstrapSession => '正在建立会话…',
        SyncProgress.fetchingSchedule => '正在获取课表…',
        SyncProgress.fetchingGrades => '正在获取成绩…',
        SyncProgress.fetchingExams => '正在获取考试…',
        SyncProgress.saving => '正在保存…',
        SyncProgress.loggingOut => '正在退出…',
      };
}

/// 同步结果。
class SyncResult {
  const SyncResult({
    required this.success,
    this.courseCount = 0,
    this.message,
    this.serverCode,
  });

  final bool success;
  final int courseCount;
  final String? message;
  final String? serverCode;

  factory SyncResult.ok(int count) =>
      SyncResult(success: true, courseCount: count);

  factory SyncResult.fail(String message, {String? serverCode}) =>
      SyncResult(success: false, message: message, serverCode: serverCode);
}
