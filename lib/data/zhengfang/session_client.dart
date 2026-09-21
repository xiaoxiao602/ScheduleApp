import 'dart:convert';

/// 正方教务会话建立器（Dart 移植版，对应 Kotlin `ZhengfangSessionClient.kt`）。
///
/// 完整流程（★ 四步，缺一不可）：
///   ① CAS 登录 -> 拿 TGT
///   ② 用 TGT 换 ST（service=jwxt）
///   ③ 带 ST 访问 jwxt，跟随 302 链建立正方会话
///   ④ 会话就绪，可调数据接口
///
/// ⚠️ 三个必须遵守的约束：
///  1. **保持 Cookie**：跨请求累积（dio 的 CookieJar）
///  2. **自动跟随 302**：`verify` 参数由服务端生成，不能自己算
///  3. **只在内存中**：会话与凭据都不落盘
class ZhengfangConfig {
  static const String casBase = 'https://cas.gzus.edu.cn';
  static const String ticketsPath = '/lyuapServer/v1/tickets';
  static const String ehallService = 'https://ehall.gzus.edu.cn';
  static const String jwxtService =
      'https://jwxt.gzus.edu.cn/sso/lyiotlogin';
  static const String jwxtBase = 'https://jwxt.gzus.edu.cn';
  static const String jwxtHome = '/jwglxt/xtgl/index_initMenu.html';

  /// 常见浏览器 UA —— 与真实用户一致，避免被风控误判。
  static const String ua =
      'Mozilla/5.0 (Windows NT 10.0; Win64; x64) '
      'AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36';

  // ---- 数据接口 ----
  /// ⚠️ 是 cxXsgrkb 不是 cxXsKb
  static const String schedulePath =
      '/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151';
  static const String gradePath =
      '/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005';
  static const String examPath =
      '/jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105';
  static const String scheduleDatePath =
      '/jwglxt/kbcx/xskbcx_cxRsd.html?gnmkdm=N2151';
  static const String calendarPath = '/jwglxt/xtgl/index_initMenu.html';

  /// 验证码端点（对应 Kotlin LoginFields.CAPTCHA_PATH）。
  /// GET 返回 JSON { uid, content }，content 是 base64 dataURL。
  static const String captchaPath = '/lyuapServer/kaptcha';

  /// 校区编号（抓包确认：xqh_id=01）
  static const String campusId = '01';

  /// 课表页 Referer，服务端会校验来源。
  static const String scheduleReferer =
      '$jwxtBase/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default';
}

/// 学期参数。
class Term {
  final String year;
  final String term;

  const Term(this.year, this.term);

  /// 2026-2027 学年第 1 学期（抓包确认：xnm=2026, xqm=3）
  ///
  /// ⚠️ xqm=3 表示【第一学期】（不是第 3 学期）。
  /// 编码映射：3 -> 第一学期，12 -> 第二学期。
  static const Term current = Term('2026', '3');
}

/// 会话建立结果。
sealed class LoginResult {
  const LoginResult();
}

class LoginSuccess extends LoginResult {
  const LoginSuccess();
}

class LoginFailure extends LoginResult {
  final String reason;
  final String? serverCode;
  const LoginFailure(this.reason, [this.serverCode]);
}

/// 登录解析结果。
class _LoginParse {
  final String? tgt;
  final String? st;
  final String? failCode;
  _LoginParse({this.tgt, this.st, this.failCode});
}

/// 正方会话/数据客户端。
///
/// ⚠️ 本类不直接依赖 dio —— 把 HTTP 调用抽象为回调，
/// 便于单元测试注入假响应。
class ZhengfangClient {
  /// HTTP GET 回调：(url, headers) -> (statusCode, body, finalUrl)
  final Future<HttpResponse> Function(String url, Map<String, String> headers)
      _get;

  /// HTTP POST form 回调：(url, formFields, headers) -> (statusCode, body, finalUrl)
  final Future<HttpResponse> Function(
      String url, Map<String, String> form, Map<String, String> headers) _post;

  /// 会话 Cookie 探针：是否已持有正方 JSESSIONID。
  final bool Function() _hasSession;

  /// 诊断日志回调。
  final void Function(String) _log;

  ZhengfangClient({
    required Future<HttpResponse> Function(
            String url, Map<String, String> headers)
        get,
    required Future<HttpResponse> Function(
            String url, Map<String, String> form, Map<String, String> headers)
        post,
    bool Function()? hasSession,
    void Function(String)? log,
  })  : _get = get,
        _post = post,
        _hasSession = hasSession ?? (() => false),
        _log = log ?? ((_) {});

  /// 完成整个会话建立流程。
  ///
  /// @param username 学号
  /// @param encryptedPassword 已加密的密码（倒序+无填充RSA+hex）
  /// @param captchaId 验证码 uid
  /// @param captchaCode 验证码答案（用户填的算术结果）
  Future<LoginResult> establish({
    required String username,
    required String encryptedPassword,
    String? captchaId,
    String? captchaCode,
  }) async {
    // ---- ① CAS 登录，拿 TGT ----
    final ticketJson = await _postTickets(
      username: username,
      encryptedPassword: encryptedPassword,
      captchaId: captchaId,
      captchaCode: captchaCode,
    );
    if (ticketJson == null) {
      return const LoginFailure('登录请求无响应');
    }

    final parsed = _parseLoginResponse(ticketJson);
    if (parsed.failCode != null) {
      return LoginFailure(_humanize(parsed.failCode!), parsed.failCode);
    }
    final tgt = parsed.tgt;
    if (tgt == null) {
      return const LoginFailure('登录失败（未取得票据）', 'NO_TGT');
    }
    // 注意：parsed.st 是【ehall 专属】的 ST，不能用于 jwxt，刻意不使用。

    // ---- ② 取 ST：必须用 TGT 换成【jwxt 专属】的 ST ----
    //
    // ⚠️ 关键教训（真机实测 404 定位）：
    //   登录响应里的 ticket 是【ehall 专属】ST —— 因为登录时
    //   传的 service=https://ehall.gzus.edu.cn。
    //   拿它去访问 jwxt 会被拒绝（HTTP 404）。
    //   必须先 POST /v1/tickets/{TGT} 且 service 指向 jwxt 换票。
    final st = await _requestServiceTicket(tgt);
    if (st == null) {
      // ⚠️ 不要武断地说「票务过期」——原版 Kotlin 并无 ST_FAILED 这个错误码，
      //    换票失败可能是 TGT 无效 / 服务端风控 / 响应格式变了。
      //    真实原因看诊断日志的「换票响应: ...」那一行。
      return LoginFailure(
          '登录票据换取失败 | ${lastTicketDebug.isEmpty ? "无响应" : lastTicketDebug}',
          'ST_FAILED');
    }

    // ---- ③ 带 ST 访问 jwxt，跟随 302 建立会话 ----
    if (!await _bootstrapJwxtSession(st)) {
      return const LoginFailure(
          '正方会话建立失败（票据可能已失效或 IP 不匹配）', 'BOOTSTRAP_FAILED');
    }

    return const LoginSuccess();
  }

  /// ① POST /lyuapServer/v1/tickets
  Future<String?> _postTickets({
    required String username,
    required String encryptedPassword,
    String? captchaId,
    String? captchaCode,
  }) async {
    final form = <String, String>{
      'username': username,
      'password': encryptedPassword,
      'service': ZhengfangConfig.ehallService,
      'loginType': '', // ⚠️ 必须为空字符串，不是 "1"
    };
    if (captchaId != null) form['id'] = captchaId;
    if (captchaCode != null) form['code'] = captchaCode;

    final headers = <String, String>{
      'User-Agent': ZhengfangConfig.ua,
      'Accept': 'application/json, text/plain, */*',
      'Origin': ZhengfangConfig.casBase,
      'Referer':
          '${ZhengfangConfig.casBase}/lyuapServer/login?service=${ZhengfangConfig.ehallService}',
    };

    try {
      final resp = await _post(
        '${ZhengfangConfig.casBase}${ZhengfangConfig.ticketsPath}',
        form,
        headers,
      );
      return resp.body;
    } catch (e) {
      _log('登录请求异常: $e');
      return null;
    }
  }

  /// 解析登录响应，取出 TGT。
  ///
  /// ⚠️ 真机实测格式：`{"tgt":"TGT-...","ticket":"ST-..."}`
  /// 兼容 `{data:...}` 与把 TGT 直接作为字符串返回的情况。
  _LoginParse _parseLoginResponse(String json) {
    dynamic obj;
    try {
      obj = jsonDecode(json);
    } catch (_) {
      obj = null;
    }

    // 失败码检测：data 可能是对象 {"code":"PASSERROR"} 或纯字符串
    final failCode = _extractFailureCode(obj, json);
    if (failCode != null) return _LoginParse(failCode: failCode);

    // TGT：优先顶层 tgt 字段，其次从任意文本里抓
    String? tgt;
    if (obj is Map) {
      final t = obj['tgt'];
      if (t is String && t.contains('TGT-')) tgt = t;
    }
    tgt ??= RegExp(r'TGT-[\dA-Za-z-]+').firstMatch(json)?.group(0);
    if (tgt == null) return _LoginParse(failCode: 'NO_TGT');

    // ST：服务端有时一并返回（但不用于 jwxt）
    String? st;
    if (obj is Map) {
      final t = obj['ticket'];
      if (t is String && t.contains('ST-')) st = t;
    }
    st ??= RegExp(r'ST-[\dA-Za-z-]+').firstMatch(json)?.group(0);

    return _LoginParse(tgt: tgt, st: st);
  }

  /// ② POST /lyuapServer/v1/tickets/{TGT} -> jwxt 专属 ST
  /// 最近一次换票的详细情况（诊断用，直接显示在错误消息里）。
  String lastTicketDebug = "";

  Future<String?> _requestServiceTicket(String tgt) async {
    final form = <String, String>{
      'loginToken': 'loginToken',
      'service': ZhengfangConfig.jwxtService,
    };
    final headers = <String, String>{
      'User-Agent': ZhengfangConfig.ua,
      'Accept': 'application/json, text/plain, */*',
      'Origin': ZhengfangConfig.casBase,
      'Referer':
          '${ZhengfangConfig.casBase}/lyuapServer/login?service=${ZhengfangConfig.jwxtService}',
    };

    String? body;
    try {
      final resp = await _post(
        '${ZhengfangConfig.casBase}${ZhengfangConfig.ticketsPath}/$tgt',
        form,
        headers,
      );
      body = resp.body;
      _log('换票 HTTP ${resp.statusCode} 长度=${body.length}');
      lastTicketDebug = 'HTTP ${resp.statusCode} | ${body.length > 150 ? body.substring(0, 150) : body}';
    } catch (e, st) {
      // 抓完整异常信息：类型 + 消息 + 前 3 层堆栈（定位「换票异常」根因）
      _log('换票请求异常 type=${e.runtimeType} msg=$e');
      _log('换票堆栈: ${st.toString().split('\n').take(3).join(' | ')}');
      lastTicketDebug = '${e.runtimeType}: $e';
      return null;
    }

    _log('换票响应: ${body.substring(0, body.length > 200 ? 200 : body.length)}');

    // 换票响应同样是 {tgt, ticket} 形态，统一走同一套解析。
    String? st;
    try {
      final parsed = jsonDecode(body);
      if (parsed is Map) {
        final t = parsed['ticket'];
        if (t is String && t.contains('ST-')) st = t;
      }
    } catch (_) {}
    st ??= RegExp(r'ST-[\dA-Za-z-]+').firstMatch(body)?.group(0);

    _log(st != null
        ? '取得 jwxt ST: ${st.substring(0, st.length > 40 ? 40 : st.length)}...'
        : '未从换票响应中取得 ST');
    return st;
  }

  /// ③ 带 ST 访问正方，跟随 302 链，建立会话。
  ///
  /// ⚠️ 抓包证实的完整序列（两步，缺一不可）：
  ///   ① GET /sso/lyiotlogin（【不带】ticket，预热）
  ///      —— 让服务端建立 CAS 上下文；
  ///         真机实测：跳过它直接带 ticket 访问会返回 **404**。
  ///   ② GET /sso/lyiotlogin?ticket=ST-..（带 ticket）
  ///      -> 302 /jwglxt/ticketlogin?uid=..&verify=..
  ///      -> 302 /jwglxt/xtgl/login_slogin.html
  ///      -> 302 /jwglxt/xtgl/index_initMenu.html?jsdm=xs
  Future<bool> _bootstrapJwxtSession(String st) async {
    final htmlHeaders = <String, String>{
      'User-Agent': ZhengfangConfig.ua,
      'Accept':
          'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    };

    // ---- ① 预热：不带 ticket 访问 ----
    try {
      final warm = await _get(
        '${ZhengfangConfig.jwxtBase}/sso/lyiotlogin',
        htmlHeaders,
      );
      _log('预热 HTTP ${warm.statusCode} final=${warm.finalUrl}');
      _log('预热 body[0..200]=' + warm.body.substring(0, warm.body.length > 200 ? 200 : warm.body.length));
    } catch (e) {
      _log('预热失败（忽略，继续）: $e');
    }

    // ---- ② 带 ticket 访问，跟随 302 链 ----
    try {
      final resp = await _get(
        '${ZhengfangConfig.jwxtBase}/sso/lyiotlogin?ticket=$st',
        {
          ...htmlHeaders,
          'Referer':
              '${ZhengfangConfig.casBase}/lyuapServer/login?service=${ZhengfangConfig.jwxtService}',
        },
      );

      final finalUrl = resp.finalUrl ?? '';
      final body = resp.body;
      _log('会话跳转 -> HTTP ${resp.statusCode}');
      _log('落地页完整: $finalUrl');
      _log('响应体[0..400]=' + body.substring(0, body.length > 400 ? 400 : body.length));

      final landedInJwglxt = finalUrl.contains('/jwglxt/');
      final isLoginPage = finalUrl.contains('login_slogin') ||
          body.contains('统一身份认证') ||
          (body.contains('id="yhm"') && body.contains('id="mm"'));
      final hasSession = _hasSession();

      _log('落在 jwglxt=$landedInJwglxt 登录页=$isLoginPage 有会话=$hasSession');

      // 只要进了 jwglxt 且不在登录页，或已持有会话 Cookie，即视为成功
      return (landedInJwxt(finalUrl) && !isLoginPage) || hasSession;
    } catch (e) {
      _log('会话跳转异常: $e');
      return false;
    }
  }

  /// 兼容 landing 判定（Kotlin 里就是 landedInJwglxt）。
  bool landedInJwxt(String url) => url.contains('/jwglxt/');


  /// 验证码数据。
  ///
  /// 对应 Kotlin `LyuapAuthRepository.fetchCaptcha()`：
  ///   GET /lyuapServer/kaptcha
  ///   headers: UA + Accept(json) + Referer(.../lyuapServer/login)
  ///   返回 JSON { uid, content }，content 是 base64 dataURL
  Future<CaptchaResult?> fetchCaptcha() async {
    try {
      final resp = await _get(
        '${ZhengfangConfig.casBase}${ZhengfangConfig.captchaPath}',
        {
          'User-Agent': ZhengfangConfig.ua,
          'Accept': 'application/json, text/plain, */*',
          'Referer': '${ZhengfangConfig.casBase}/lyuapServer/login',
        },
      );
      _log('验证码 HTTP ${resp.statusCode}');
      final body = resp.body;
      if (body.isEmpty) {
        _log('验证码响应为空');
        return null;
      }
      final parsed = jsonDecode(body);
      if (parsed is! Map) {
        _log('验证码响应不是 JSON 对象');
        return null;
      }
      final uid = (parsed['uid'] ?? '').toString();
      final content = (parsed['content'] ?? '').toString();
      if (uid.isEmpty || content.isEmpty) {
        _log('验证码数据无效 uid=${uid.length} content=${content.length}');
        return null;
      }
      _log('验证码 OK uid=${uid.length} 字符 content=${content.length} 字符');
      return CaptchaResult(uid: uid, dataUrl: content);
    } catch (e) {
      _log('验证码异常: $e');
      return null;
    }
  }

  /// 从响应里找失败码。
  ///
  /// ⚠️ `data` 的类型不固定：
  ///  - 对象：`{"code":"PASSERROR","data":"PASSERROR"}`
  ///  - 字符串：`"PASSERROR"`
  String? _extractFailureCode(dynamic obj, String rawJson) {
    final candidates = <String>[];
    if (obj is Map) {
      final data = obj['data'];
      if (data != null) candidates.add(jsonEncode(data));
      final code = obj['code'];
      if (code is String) candidates.add(code);
      final meta = obj['meta'];
      if (meta is Map && meta['code'] is String) {
        candidates.add(meta['code'] as String);
      }
    }
    candidates.add(rawJson);

    const known = [
      'PASSERROR',
      'NOUSER',
      'CODEFALSE',
      'USERLOCKED',
      'LOCKED',
      'FAIL',
      'ERROR',
      'NO_TGT',
    ];
    for (final c in candidates) {
      for (final k in known) {
        if (c.toUpperCase().contains(k)) return k;
      }
    }
    return null;
  }

  /// 把服务端错误码翻译成用户能看懂的话。
  String _humanize(String code) {
    switch (code.toUpperCase()) {
      case 'PASSERROR':
        return '密码错误';
      case 'NOUSER':
        return '账号不存在';
      case 'CODEFALSE':
        return '验证码错误';
      case 'ST_FAILED':
        // 中性描述：具体原因由消息体里的服务端响应说明
        return '登录票据换取失败';
      case 'USERLOCKED':
      case 'LOCKED':
        return '账号已被锁定，请稍后再试或联系教务处';
      default:
        return '登录失败（$code）';
    }
  }
}

/// 简化 HTTP 响应（与具体 HTTP 库解耦）。
class HttpResponse {
  final int statusCode;
  final String body;
  final String? finalUrl;
  const HttpResponse(this.statusCode, this.body, [this.finalUrl]);
}


/// 验证码结果（uid + base64 dataURL 图片）。
class CaptchaResult {
  const CaptchaResult({required this.uid, required this.dataUrl});

  /// 提交登录时作为 `id` 字段。
  final String uid;

  /// 形如 "data:image/png;base64,iVBOR..." 的完整 dataURL。
  final String dataUrl;

  bool get isValid => uid.isNotEmpty && dataUrl.isNotEmpty;

  /// 去掉 dataURL 前缀后的纯 base64。
  String get base64Body {
    final i = dataUrl.indexOf(',');
    return i >= 0 ? dataUrl.substring(i + 1) : dataUrl;
  }
}
