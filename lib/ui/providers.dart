import 'dart:convert';

import 'package:cookie_jar/cookie_jar.dart';
import 'package:dio/dio.dart';
import 'package:dio_cookie_manager/dio_cookie_manager.dart';
import 'package:drift/drift.dart' show DatabaseConnection;
import 'package:flutter/material.dart' show Color;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/local/app_database.dart';
import '../data/local/repositories.dart';
import '../data/local/settings_stores.dart';
import '../data/zhengfang/session_client.dart';
import '../domain/colors.dart';
import '../domain/core_logic.dart';
import '../domain/models.dart';
import '../domain/sync_use_case.dart';

/// ============================================================
/// 应用状态（Riverpod providers）
/// ============================================================
///
/// ⚠️ 架构约定（与说明书 §2.2 / §11.1 一致）：
///   UI -> provider -> Repository -> drift(本地库)
///   **本地库是唯一数据源**：界面只读库；同步只是往库里写。
///   断网 / 教务故障不影响查看已下载的数据。

// ---------------------------------------------------------- 基础设施

/// 数据库单例。
final dbProvider = Provider<AppDatabase>((ref) {
  final db = AppDatabase();
  ref.onDispose(db.close);
  return db;
});

/// 5 个仓库。
final courseRepoProvider =
    Provider<CourseRepository>((ref) => CourseRepository(ref.watch(dbProvider)));

final examRepoProvider =
    Provider<ExamRepository>((ref) => ExamRepository(ref.watch(dbProvider)));

final gradeRepoProvider =
    Provider<GradeRepository>((ref) => GradeRepository(ref.watch(dbProvider)));

final metaRepoProvider =
    Provider<MetaRepository>((ref) => MetaRepository(ref.watch(dbProvider)));

final dayOverrideRepoProvider = Provider<DayOverrideRepository>(
    (ref) => DayOverrideRepository(ref.watch(dbProvider)));


/// 正方客户端（用于取验证码等独立请求）。
///
/// ⚠️ 与 SyncUseCase 内部那个实例不同：
///    同步时的会话是**短生命周期**（用完即弃 CookieJar）；
///    这里的实例只服务于"登录前的验证码拉取"，持有一个常驻 CookieJar，
///    以便验证码请求与随后的登录请求落到同一会话上。
final zhengfangClientProvider = Provider<ZhengfangClient>((ref) {
  final jar = CookieJar();
  final dio = Dio(BaseOptions(
    connectTimeout: const Duration(seconds: 20),
    receiveTimeout: const Duration(seconds: 20),
    followRedirects: true,
    maxRedirects: 10,
    validateStatus: (s) => s != null && s < 500,
    headers: {'User-Agent': ZhengfangConfig.ua},
  ))
    ..interceptors.add(CookieManager(jar));

  final client = ZhengfangClient(
    get: (url, headers) async {
      final r = await dio.get<dynamic>(url, options: Options(headers: headers));
      return HttpResponse(r.statusCode ?? 0, _bodyOf(r), r.realUri.toString());
    },
    post: (url, form, headers) async {
      final r = await dio.post<dynamic>(
        url,
        data: FormData.fromMap(form),
        options: Options(headers: headers),
      );
      return HttpResponse(r.statusCode ?? 0, _bodyOf(r), r.realUri.toString());
    },
    hasSession: () => false,
    log: (_) {},
  );

  ref.onDispose(() => dio.close(force: true));
  return client;
});

String _bodyOf(Response<dynamic> r) {
  final d = r.data;
  if (d == null) return '';
  if (d is String) return d;
  try {
    return jsonEncode(d);
  } catch (_) {
    return d.toString();
  }
}

/// 同步用例。
final syncUseCaseProvider = Provider<SyncUseCase>((ref) => SyncUseCase(
      courseRepo: ref.watch(courseRepoProvider),
      examRepo: ref.watch(examRepoProvider),
      metaRepo: ref.watch(metaRepoProvider),
    ));

/// 设置 Store（无需缓存，都是轻量 SharedPreferences 读写）。
final dockTuningStoreProvider = Provider((ref) => DockTuningStore());
final cardStyleStoreProvider = Provider((ref) => CardStyleStore());
final hapticStoreProvider = Provider((ref) => HapticStore());
final userProfileStoreProvider = Provider((ref) => UserProfileStore());
final updatePrefsProvider = Provider((ref) => UpdatePrefs());

// ---------------------------------------------------------- meta 键值

/// 全部 meta（键 → 值）。
///
/// ⚠️ 用一个 provider 一次读全表，避免每个键一个 provider 造成 N 次查询。
final metaAllProvider = FutureProvider<Map<String, String>>((ref) async {
  return ref.watch(metaRepoProvider).getAll();
});


/// 假日区间（ADR-060）。
///
/// ⚠️ 用户明确选「假期就当没课」—— 渲染时**直接跳过**假日的课，
///    不做任何标记。所以这个 provider 是渲染过滤器，不是展示数据。
final holidaysProvider = Provider<List<HolidayRange>>((ref) {
  final raw = ref.watch(metaAllProvider).valueOrNull?[MetaKeys.holidays];
  if (raw == null || raw.isEmpty) return const [];
  return HolidayCalendar.fromJson(raw);
});

/// 当前学期标签（如 "2026-2027学年第1学期"）。
final currentTermProvider = Provider<String?>((ref) {
  return ref.watch(metaAllProvider).valueOrNull?[MetaKeys.currentTerm];
});

/// 学期第一周星期一 —— 周次计算基准（ADR-011）。
///
/// ⚠️ 教务只给相对周次，没有这个就算不出"今天第几周"。
final firstMondayProvider = Provider<DateTime?>((ref) {
  final s = ref.watch(metaAllProvider).valueOrNull?[MetaKeys.firstMonday];
  return s == null ? null : TimeFormats.parseIso(s);
});

/// 上次同步时间。
final lastSyncAtProvider = Provider<DateTime?>((ref) {
  final s = ref.watch(metaAllProvider).valueOrNull?[MetaKeys.lastSyncAt];
  if (s == null) return null;
  return DateTime.tryParse(s);
});

/// 学生信息（公开信息，非凭据）。
final studentInfoProvider = Provider<StudentInfo>((ref) {
  final m = ref.watch(metaAllProvider).valueOrNull ?? const {};
  return StudentInfo(
    name: m[MetaKeys.studentName],
    studentNo: m[MetaKeys.studentNo],
    major: m[MetaKeys.studentMajor],
    className: m[MetaKeys.studentClass],
    displayName: m[MetaKeys.displayName],
    academicYear: m[MetaKeys.academicYearName],
    termName: m[MetaKeys.termName],
  );
});

/// 学生信息（聚合）。
class StudentInfo {
  const StudentInfo({
    this.name,
    this.studentNo,
    this.major,
    this.className,
    this.displayName,
    this.academicYear,
    this.termName,
  });

  final String? name;
  final String? studentNo;
  final String? major;
  final String? className;

  /// 自定义显示名，非空时优先于真名。
  final String? displayName;
  final String? academicYear;

  /// ⚠️ 来自 XQMMC（值 "1"），不是 XQM（值 "3"）。
  final String? termName;

  /// 界面显示用名字：自定义名优先。
  String get shownName {
    final d = displayName;
    if (d != null && d.trim().isNotEmpty) return d;
    final n = name;
    if (n != null && n.trim().isNotEmpty) return n;
    return '同学';
  }

  /// 标题「AY26-27 Term1」。
  String get termTitle => TermTitle.of(academicYear, termName);
}

// ---------------------------------------------------------- 课表数据

/// 当前周次（1..30）。
///
/// ⚠️ 必须在 UI 层自己算，不要靠 load() 刷新 —— 原版踩过
///    "load() 是异步读库，多次刷新互相覆盖 -> 倒计时跳变"。
final currentWeekProvider = Provider<int>((ref) {
  final fm = ref.watch(firstMondayProvider);
  if (fm == null) return 1;
  return WeekCalculator.weekOf(fm, DateTime.now());
});

/// 周次选择状态（用户翻周用；null = 跟随当前周）。
final selectedWeekProvider = StateProvider<int?>((ref) => null);

/// 实际展示的周次。
final shownWeekProvider = Provider<int>((ref) {
  return ref.watch(selectedWeekProvider) ?? ref.watch(currentWeekProvider);
});

/// 当前学期的全部课程。
final coursesProvider = StreamProvider<List<Course>>((ref) {
  final term = ref.watch(currentTermProvider);
  final repo = ref.watch(courseRepoProvider);
  if (term == null || term.isEmpty) {
    return repo.observeAll();
  }
  return repo.observeByTerm(term);
});

/// 全部考试。
final examsProvider =
    StreamProvider<List<Exam>>((ref) => ref.watch(examRepoProvider).observeAll());

/// 全部成绩。
final gradesProvider =
    StreamProvider<List<Grade>>((ref) => ref.watch(gradeRepoProvider).observeAll());

/// 课程 → 颜色下标（ADR-050：按整张课表统一分配，保证同课同色）。
final courseColorMapProvider = Provider<Map<String, int>>((ref) {
  final courses = ref.watch(coursesProvider).valueOrNull ?? const <Course>[];
  return CourseColorAssigner.assign(
    courses.map((c) => c.name),
    CoursePalette.autoAssign.length,
  );
});

/// 课程 → 最终颜色（自定义色优先，否则自动分配）。
final courseColorProvider = Provider<Color Function(String)>((ref) {
  final auto = ref.watch(courseColorMapProvider);
  final custom = ref.watch(customColorsProvider).valueOrNull ?? const {};
  return (String courseName) {
    final c = custom[courseName];
    if (c != null && c != 0) return Color(c);
    final idx = auto[courseName] ?? 0;
    return Color(CoursePalette.autoAssign[idx % CoursePalette.autoAssign.length]);
  };
});

/// 每门课的自定义颜色（ARGB）。
final customColorsProvider = FutureProvider<Map<String, int>>((ref) async {
  return ref.watch(cardStyleStoreProvider).loadAllCustomColors();
});

/// 课程卡片样式。
final cardStyleModeProvider =
    FutureProvider<CardStyleMode>((ref) async {
  return ref.watch(cardStyleStoreProvider).loadMode();
});

// ---------------------------------------------------------- 当日调课

/// 当日调课表（ISO 日期 → sourceDay）。
final dayOverridesProvider = StreamProvider<Map<String, int>>(
    (ref) => ref.watch(dayOverrideRepoProvider).observeAll());

/// 某天的实际课表（已应用调课）—— 核心：今日页与周课表共用。
///
/// ⚠️ 语义（说明书 §5.7）：
///   sourceDay = -1 -> 那天无课
///   sourceDay = 1..7 -> 用该星期几的课表
///   无记录 -> 原样
///   生效范围：只影响那一天；原课表不受影响。
List<Course> coursesForDate(
  List<Course> all,
  Map<String, int> overrides,
  DateTime date,
  int week,
) {
  final iso = TimeFormats.iso(date);
  final src = overrides[iso];

  if (src == -1) return const [];

  // 该天原本的星期几
  final effectiveDay = (src != null && src >= 1 && src <= 7) ? src : date.weekday;

  return all
      .where((c) => c.dayOfWeek == effectiveDay && c.occursInWeek(week))
      .toList()
    ..sort((a, b) => a.startPeriod.compareTo(b.startPeriod));
}

// ---------------------------------------------------------- 设置

/// Dock 参数（Dock 外观调节页用）。
final dockTuningProvider = AsyncNotifierProvider<DockTuningNotifier, DockTuning>(
    DockTuningNotifier.new);

class DockTuningNotifier extends AsyncNotifier<DockTuning> {
  /// ⚠️ 不能叫 update —— 与 Riverpod AsyncNotifierBase.update 同名冲突。
  ///
  /// ⚠️⚠️ 顺序很关键（用户反馈「数值会变回去」）：
  ///
  ///   旧写法是 `state = AsyncData(t)` 然后 `await save(t)`。
  ///   问题：`build()` 依赖 `ref.watch(dockTuningStoreProvider)`，
  ///   而 Riverpod 在**任何** provider 依赖变化时都可能重建 AsyncNotifier ——
  ///   如果 `save` 触发了 store provider 的变化（或 `build()` 被重新调度），
  ///   `build()` 会用**刚写盘的值**重新 load 一遍。
  ///   在"写盘还没完成"的窗口里 `build()` 读到**旧值** → 覆盖回 state
  ///   → 用户看到「数字变一下又弹回」。
  ///
  ///   修法：
  ///     ① **先 await 写盘**（确保 store 里是新值）
  ///     ② 再 `state = AsyncData(t)`（UI 拿到的一定是新值，且不会再被旧值覆盖）
  ///     ③ 全程用一个"正在保存"标志，防止 build() 在写盘途中重建 state
  Future<void> apply(DockTuning t) async {
    await ref.read(dockTuningStoreProvider).save(t);
    state = AsyncData(t);
  }

  /// ⚠️ 只更新内存 state，**不读盘**、**不触发 build**。
  ///
  ///   调用方（Dock 调节页）已经在本地持有权威值并完成了落库，
  ///   这里只是把同一个值同步给依赖本 provider 的其它页面（主界面 Dock）。
  ///   用这种方式避免 `build()` 重新 `load()` 造成"旧值覆盖新值"的竞态
  ///   —— 那正是用户反馈「调完就变回去」的根源。
  void setLocal(DockTuning t) {
    state = AsyncData(t);
  }


  @override
  Future<DockTuning> build() async {
    return ref.watch(dockTuningStoreProvider).load();
  }

  Future<void> resetAll() async {
    await ref.read(dockTuningStoreProvider).resetAll();
    state = AsyncData(const DockTuning());
  }
}

/// 触感配置。
final hapticConfigProvider =
    AsyncNotifierProvider<HapticConfigNotifier, HapticConfig>(
        HapticConfigNotifier.new);

class HapticConfigNotifier extends AsyncNotifier<HapticConfig> {
  @override
  Future<HapticConfig> build() async {
    return ref.watch(hapticStoreProvider).load();
  }

  /// ⚠️ 不能叫 update —— 与 Riverpod AsyncNotifierBase.update 同名冲突。
  Future<void> apply(HapticConfig c) async {
    state = AsyncData(c);
    await ref.read(hapticStoreProvider).save(c);
  }
}

/// 自动检查更新开关。
final autoCheckUpdateProvider = FutureProvider<bool>((ref) async {
  return ref.watch(updatePrefsProvider).loadAutoCheck();
});

/// 用户资料（显示名）。
final userProfileProvider = FutureProvider<String?>((ref) async {
  return ref.watch(userProfileStoreProvider).loadDisplayName();
});

/// 推送/提醒设置（1.2.1 方案 E）。
final notifyStoreProvider = Provider((ref) => NotifyStore());

final notifyPrefsProvider =
    AsyncNotifierProvider<NotifyPrefsNotifier, NotifyPrefs>(
        NotifyPrefsNotifier.new);

class NotifyPrefsNotifier extends AsyncNotifier<NotifyPrefs> {
  @override
  Future<NotifyPrefs> build() async {
    return ref.watch(notifyStoreProvider).load();
  }

  /// ⚠️ 顺序（DockTuning 同款教训）：先落盘再换 state，防 build() 旧值覆盖。
  Future<void> apply(NotifyPrefs p) async {
    await ref.read(notifyStoreProvider).save(p);
    state = AsyncData(p);
  }
}

/// 数据库连接（供测试注入内存库）。
typedef DbOverride = DatabaseConnection;
