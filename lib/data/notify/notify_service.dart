import 'dart:io';

import 'package:android_alarm_manager_plus/android_alarm_manager_plus.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:path_provider/path_provider.dart';

import '../../domain/core_logic.dart';
import '../../domain/models.dart';
import '../../domain/reminder_engine.dart';
import '../local/app_database.dart';
import '../local/repositories.dart';
import '../local/settings_stores.dart';

/// ============================================================
/// 通知服务（1.2.1 方案 E：自研常驻倒计时通知）—— 讨论文档 §5
/// ============================================================
///
/// 设计要点（全部经 pub cache 源码核对 API 后实现）：
///   · 秒表：`when` + `usesChronometer` + `chronometerCountDown` ——
///     系统每秒自动走字，我们零刷新成本。
///   · 进度条：`showProgress/maxProgress/progress`，每分钟自链式闹钟刷新一次
///     （档 B，用户拍板）+ `onlyAlertOnce` 静默更新。
///   · 软常驻：⚠️ Android `ongoing: true` 不可划掉（且 FGS 通知同样不可划掉，
///     源码注释实锤）—— 改用可划掉 + 划掉回调（`dismissIsolate`，v22.2.0+，
///     app 被杀也能收到）自动重推。
///   · 体面出口：通知带「结束显示」action —— 点了本节课不再回来。
///   · 定时：android_alarm_manager_plus 自链式精确闹钟（每次触发算出下一个
///     动作点：阶段切换 or 分钟 tick），rescheduleOnReboot 免注册开机广播。

/// 后台划掉/动作回调（app 被终止也能唤醒本函数）。
@pragma('vm:entry-point')
void notifyBackgroundResponse(NotificationResponse r) {
  NotifyService.instance.onResponse(r);
}

/// 自链式闹钟回调（独立 isolate）。
///
/// ⚠️ 链路健壮性（+21 全量审计）：任何异常都**不能让自链断掉** ——
///    异常时落盘 + 兜底重排下一个动作点（否则一次 DB 抖动 = 通知永久冻结）。
@pragma('vm:entry-point')
Future<void> notifyChainAlarm(int id, Map<String, dynamic> params) async {
  try {
    await NotifyService.instance.onTimer();
  } catch (e) {
    try {
      await NotifyService.instance.debugLog('chain error: $e');
    } catch (_) {}
    try {
      await NotifyService.instance.reschedule();
    } catch (_) {}
  }
}

/// 测试倒计时 tick（独立 isolate，闹钟驱动）。
///
/// ⚠️ +22：测试 3 原来用页面内 Timer —— 离开页面被 dispose、
///    App 退后台被 MIUI 冻结都会断更（用户实测「一直显示5分钟」）。
///    改为与真实链路同机制的闹钟驱动：冻结/退页/杀进程都不影响。
@pragma('vm:entry-point')
Future<void> notifyTestTickAlarm(int id, Map<String, dynamic> params) async {
  try {
    final total = (params['total'] as num?)?.toInt() ?? 300;
    final endMs = (params['endMs'] as num?)?.toInt() ?? 0;
    await NotifyService.instance.onTestTick(total, endMs);
  } catch (e) {
    try {
      await NotifyService.instance.debugLog('test tick error: $e');
    } catch (_) {}
  }
}

class NotifyService {
  NotifyService._();

  static final NotifyService instance = NotifyService._();

  // ---------- 渠道 ----------
  /// 统一渠道（HIGH）—— 用户拍板（+17）：
  ///
  /// ⚠️ 之前倒计时类用 cls_count（LOW 静默渠道）—— MIUI 会把 LOW 归进
  ///    「静默通知」组（不悬浮、优先级低），影响展示与 HyperIsland 转岛。
  ///    现在全部走 cls_remind（HIGH）；「更新不再响」由 onlyAlertOnce 保证。
  static const _chRemind = AndroidNotificationChannel(
    'cls_remind',
    '上课提醒',
    description: '课前提醒、课程倒计时与下课提示',
    importance: Importance.max,
  );

  // ---------- id ----------
  static const int idClass = 100; // 真实课表通知（同 id 演化）
  static const int _idTestBase = 9000; // 测试通知 9001..9005
  static const int _alarmId = 2001;

  static const String _actEnd = 'act_end_display';
  static const String _icon = '@mipmap/ic_launcher';

  final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();
  final NotifyStore _store = NotifyStore();
  bool _inited = false;

  // ============================================================
  // 初始化
  // ============================================================

  Future<void> init() async {
    if (_inited) return;
    _inited = true;

    const settings = InitializationSettings(
      android: AndroidInitializationSettings(_icon),
    );
    await _plugin.initialize(
      settings: settings,
      onDidReceiveNotificationResponse: onResponse,
      onDidReceiveBackgroundNotificationResponse: notifyBackgroundResponse,
    );

    final android = _plugin.resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>();
    await android?.createNotificationChannel(_chRemind);

    await AndroidAlarmManager.initialize();
  }

  /// 请求通知权限（Android 13+）。返回是否已授权。
  Future<bool> requestNotifyPermission() async {
    final android = _plugin.resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>();
    final ok = await android?.requestNotificationsPermission();
    await _log('requestNotifyPermission -> $ok');
    return ok ?? false;
  }

  /// 当前通知权限状态。
  Future<bool> areNotificationsEnabled() async {
    final android = _plugin.resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>();
    return await android?.areNotificationsEnabled() ?? false;
  }

  /// 精确闹钟权限状态。
  Future<bool> canScheduleExact() async {
    final android = _plugin.resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>();
    return await android?.canScheduleExactNotifications() ?? false;
  }

  /// 落盘日志（MIUI 无 logcat，唯一日志通道；写到 app 外部私有目录，USB 可读）。
  ///
  /// ⚠️ +21：超过 32KB 自动裁剪保留末尾 200 行 —— tick 每次落一条，
  ///    不裁剪会无限增长吃存储。
  Future<void> _log(String msg) async {
    try {
      final dir = await getExternalStorageDirectory() ??
          await getApplicationDocumentsDirectory();
      final f = File('${dir.path}/notify_log.txt');
      if (await f.exists() && await f.length() > 32 * 1024) {
        final lines = await f.readAsLines();
        final tail = lines.length > 200 ? lines.sublist(lines.length - 200) : lines;
        await f.writeAsString('${tail.join('\n')}\n');
      }
      await f.writeAsString(
        '${DateTime.now().toIso8601String()} $msg\n',
        mode: FileMode.append,
        flush: true,
      );
    } catch (_) {}
  }

  /// 供闹钟回调等外部入口写日志。
  Future<void> debugLog(String msg) => _log(msg);

  /// 请求精确闹钟权限（Android 12+）。
  Future<bool> requestExactAlarmPermission() async {
    final android = _plugin.resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>();
    final ok = await android?.requestExactAlarmsPermission();
    return ok ?? false;
  }

  // ============================================================
  // 响应处理：划掉 → 重推；「结束显示」→ 本节课消失
  // ============================================================

  /// 主/后台共用的响应入口。
  ///
  /// ⚠️ 源码语义（notification_details.dart 注释）：
  ///    · 划掉以 notificationDismissed 上报（tap/cancel 永不上报）；
  ///    · dismissIsolate=background 时 app 被杀也能收到。
  void onResponse(NotificationResponse r) {
    _log('onResponse id=${r.id} type=${r.notificationResponseType} '
        'action=${r.actionId} payload=${r.payload}');
    switch (r.notificationResponseType) {
      case NotificationResponseType.notificationDismissed:
        _onDismissed(r);
      case NotificationResponseType.selectedNotification:
      case NotificationResponseType.selectedNotificationAction:
        if (r.actionId == _actEnd) {
          _onEndDisplay(r);
        }
        // tap 本身：系统默认拉起 App，无需处理。
    }
  }

  /// 划掉重推（用户拍板：立即重推、无上限；「结束显示」为体面出口）。
  Future<void> _onDismissed(NotificationResponse r) async {
    if (r.id == idClass) {
      await _repushClass();
      return;
    }
    final p = r.payload ?? '';
    if (p.startsWith('test:')) {
      final n = int.tryParse(p.substring(5)) ?? 0;
      if (n > 0) await showTest(n);
    }
  }

  /// 「结束显示」action：本节课（或该测试通知）不再回来。
  Future<void> _onEndDisplay(NotificationResponse r) async {
    if (r.id == idClass) {
      final prefs = await _store.load();
      final now = DateTime.now();
      final ms = await _loadMoments(now, prefs);
      final act = ReminderEngine.activeOf(ms, now);
      final ended = ReminderEngine.endedOf(ms, now);
      final key = act?.key ?? ended?.key;
      if (key != null) await _store.suppress(key);
      await _plugin.cancel(id: idClass);
    }
    // 测试通知的 cancelNotification:true 已自动消失，且 cancel 不触发重推。
  }

  Future<void> _repushClass() async {
    final prefs = await _store.load();
    if (!prefs.enabled) return;
    final now = DateTime.now();
    final ms = await _loadMoments(now, prefs);
    final act = ReminderEngine.activeOf(ms, now);
    if (act != null) {
      if (await _store.isSuppressed(act.key)) return;
      // activeOf 保证 now < end —— 只可能是 before/inClass
      // （after 场景由下面的 endedOf 分支处理）。
      switch (ReminderEngine.stageOf(now, act)) {
        case NotifyStage.before:
          if (prefs.remindOn || prefs.countdownOn) {
            await _showBefore(act, alert: false);
          }
        case NotifyStage.inClass:
          if (prefs.countdownOn) await _showInClass(act);
        case NotifyStage.after:
          break; // 不可达（保留以满足 switch 穷举）
      }
      return;
    }
    final ended = ReminderEngine.endedOf(ms, now);
    if (ended != null &&
        prefs.afterOn &&
        !await _store.isSuppressed(ended.key)) {
      await _showAfter(ended, ms, now);
    }
  }

  // ============================================================
  // 自链式闹钟：每个动作点（阶段切换 / 分钟 tick）触发一次
  // ============================================================

  /// 重排下一个动作点（设置变更/启动后调用；onTimer 末尾自链）。
  ///
  /// ⚠️ +21：支持复用 onTimer 刚查过的数据 —— 之前每 tick 开两次库（浪费）。
  Future<void> reschedule({
    List<CourseMoment>? cachedMoments,
    NotifyPrefs? cachedPrefs,
  }) async {
    final prefs = cachedPrefs ?? await _store.load();
    if (!prefs.enabled) {
      await AndroidAlarmManager.cancel(_alarmId);
      await _plugin.cancel(id: idClass);
      return;
    }

    final now = DateTime.now();
    final ms = cachedMoments ?? await _loadMoments(now, prefs);
    DateTime? next;

    DateTime minOf(DateTime? a, DateTime b) => a == null || b.isBefore(a) ? b : a;

    for (final m in ms) {
      for (final t in [m.remindAt, m.start, m.end]) {
        if (t.isAfter(now)) next = minOf(next, t);
      }
    }

    // tick：课前/上课中显示期间每 15 秒刷新（+19 用户拍板分钟制后定档）。
    // ⚠️ 双重职责：show(同 id) 会把被划掉的通知**重新拉起** —— tick 即重推看门狗。
    // ⚠️ 为什么 15s：每秒刷新疑似被系统限流（60 秒实验刷 7 次即冻结）；
    //    分钟制文本 + 15s 刷新既省电又碰不到限流。
    // ⚠️ 秒级「活」感靠系统秒表（Chronometer，系统渲染零刷新）——
    //    +19 曾按「不显示秒」移除，结果通知/岛完全静止（用户报「不会更新」），+20 回归。
    // ⚠️ Doze 深睡时 setExact 可能被系统按维护窗口推迟 —— 醒屏下准时。
    final act = ReminderEngine.activeOf(ms, now);
    if (act != null && prefs.countdownOn && !await _store.isSuppressed(act.key)) {
      final tick = now.add(const Duration(seconds: 15));
      next = minOf(next, tick);
    }

    // 兜底：明天早上 7 点重算（防课表跨天/数据迟到）。
    final fallback =
        DateTime(now.year, now.month, now.day + 1, 7, 0, 5);
    next ??= fallback;
    if (next.difference(now).inSeconds < 3) {
      next = now.add(const Duration(seconds: 10));
    }

    await AndroidAlarmManager.oneShotAt(
      next,
      _alarmId,
      notifyChainAlarm,
      // ⚠️ +26 回退：+25 的自排程(setAlarmClock)未验证且为最新嫌疑 ——
      //    回到 +22 有日志实证会跳的插件排程。带时间戳做显示侧受控实验。
      exact: true,
      wakeup: true,
      allowWhileIdle: true,
      rescheduleOnReboot: true,
    );
  }

  /// 闹钟触发：按当前时刻决定显示/更新/消失，并链出下一个动作点。
  Future<void> onTimer() async {
    final prefs = await _store.load();
    final now = DateTime.now();
    if (!prefs.enabled) {
      await _plugin.cancel(id: idClass);
      return;
    }

    final ms = await _loadMoments(now, prefs);
    final act = ReminderEngine.activeOf(ms, now);

    if (act != null) {
      final stage = ReminderEngine.stageOf(now, act);
      final tag = '${stage.name}|${act.key}';
      final first = await _store.loadStage() != tag;
      if (await _store.isSuppressed(act.key)) {
        // 「结束显示」过 —— 本节课静默。
      } else if (stage == NotifyStage.before) {
        if (prefs.remindOn || prefs.countdownOn) {
          await _showBefore(act, alert: first && prefs.remindOn, forceNew: first);
          await _store.saveStage(tag);
        }
      } else if (stage == NotifyStage.inClass) {
        if (prefs.countdownOn) {
          await _showInClass(act, forceNew: first);
          await _store.saveStage(tag);
        } else {
          await _plugin.cancel(id: idClass);
        }
      }
    } else {
      final ended = ReminderEngine.endedOf(ms, now);
      if (ended != null &&
          prefs.afterOn &&
          !await _store.isSuppressed(ended.key)) {
        final tag = 'after|${ended.key}';
        final first = await _store.loadStage() != tag;
        await _showAfter(ended, ms, now, forceNew: first);
        await _store.saveStage(tag);
      } else {
        await _plugin.cancel(id: idClass);
      }
    }

    await reschedule(cachedMoments: ms, cachedPrefs: prefs);
  }

  // ============================================================
  // 通知渲染（同 id 演化；阶段首帖 cancel+show，分钟更新只 show）
  // ============================================================

  Future<void> _showBefore(CourseMoment m,
      {required bool alert, bool forceNew = true}) async {
    final details = AndroidNotificationDetails(
      _chRemind.id,
      _chRemind.name,
      channelDescription: _chRemind.description,
      importance: Importance.max,
      priority: Priority.high,
      when: m.start.millisecondsSinceEpoch,
      usesChronometer: true,
      chronometerCountDown: true,
      // ⚠️ 用户拍板：课前倒计时**不要进度条**（+12）。
      autoCancel: false,
      ongoing: false,
      onlyAlertOnce: !alert,
      visibility: NotificationVisibility.public,
      largeIcon: const DrawableResourceAndroidBitmap(_icon),
      actions: const [
        AndroidNotificationAction(_actEnd, '结束显示',
            showsUserInterface: false, cancelNotification: true),
      ],
      dismissIsolate: NotificationDismissedIsolate.background,
    );
    await _post(
      id: idClass,
      // ⚠️ 用户拍板（+19）：课前 = 课程名 + 地点 + 时间（去「下节课」前缀与倒计时文字）。
      title: m.course.name,
      body: '${m.course.location} · ${_hm(m.start)}',
      details: details,
      forceNew: forceNew,
    );
  }

  Future<void> _showInClass(CourseMoment m, {bool forceNew = true}) async {
    final now = DateTime.now();
    final details = AndroidNotificationDetails(
      _chRemind.id,
      _chRemind.name,
      channelDescription: _chRemind.description,
      importance: Importance.max,
      priority: Priority.high,
      when: m.end.millisecondsSinceEpoch,
      usesChronometer: true,
      chronometerCountDown: true,
      showProgress: true,
      maxProgress: 100,
      progress: (ReminderEngine.progressOf(now, m) * 100).round(),
      autoCancel: false,
      ongoing: false,
      onlyAlertOnce: true,
      visibility: NotificationVisibility.public,
      largeIcon: const DrawableResourceAndroidBitmap(_icon),
      actions: const [
        AndroidNotificationAction(_actEnd, '结束显示',
            showsUserInterface: false, cancelNotification: true),
      ],
      dismissIsolate: NotificationDismissedIsolate.background,
    );
      await _post(
        id: idClass,
        // ⚠️ 用户拍板（+18/19）：不带教室号、不写「上课中」——
        //    课程名 + 距下课时间（分钟制，不显示秒）。
        // ⚠️ +28：剩余分钟用 ceil（与测试 3 一致）——floor 会在临下课
        //    不足 1 分钟时显示「0分钟」。
        title: m.course.name,
        body: '距下课 ${TimeFormats.hoursMinutes((m.end.difference(now).inSeconds / 60).ceil())}',
        details: details,
        forceNew: forceNew,
      );
  }

  Future<void> _showAfter(CourseMoment m, List<CourseMoment> ms, DateTime now,
      {bool forceNew = true}) async {
    // ⚠️ 用户拍板（+18）：下课通知 = 课程名 +「已下课」，极简（优化灵动岛显示）。
    //    原「下节 xx · HH:mm」正文与满格进度条都去掉。
    final details = AndroidNotificationDetails(
      _chRemind.id,
      _chRemind.name,
      channelDescription: _chRemind.description,
      importance: Importance.max,
      priority: Priority.high,
      showWhen: true,
      autoCancel: false,
      ongoing: false,
      onlyAlertOnce: true,
      visibility: NotificationVisibility.public,
      largeIcon: const DrawableResourceAndroidBitmap(_icon),
      actions: const [
        AndroidNotificationAction(_actEnd, '结束显示',
            showsUserInterface: false, cancelNotification: true),
      ],
      dismissIsolate: NotificationDismissedIsolate.background,
    );
    await _post(
      id: idClass,
      title: '${m.course.name} 已下课',
      body: null, // ⚠️ +24：必须 null 不是空串 —— 空串会被 HyperIsland 回退填成第二行标题（重复两行）
      details: details,
      forceNew: forceNew,
    );
  }

  /// 同 id 发布：阶段首帖换渠道时先 cancel（cancel 不触发划掉回调），
  /// 分钟更新直接 show 走 onlyAlertOnce 静默刷新。
  Future<void> _post({
    required int id,
    required String title,
    String? body,
    required AndroidNotificationDetails details,
    required bool forceNew,
    String payload = 'class',
  }) async {
    if (forceNew) await _plugin.cancel(id: id);
    await _log('post id=$id "$title"');
    await _plugin.show(
      id: id,
      title: title,
      body: body,
      notificationDetails: NotificationDetails(android: details),
      payload: payload,
    );
  }

  /// 手动清除真实课表通知。
  Future<void> cancelClass() => _plugin.cancel(id: idClass);

  // ============================================================
  // 推送测试模式（独立 id，不与真实课表通知相撞）
  // ============================================================

  static const List<String> testNames = [
    '上课提醒（铃声）',
    '课前倒计时（软常驻）',
    '上课中常驻（秒表+进度条）',
    '已下课',
    '划掉重推实测',
  ];

  /// n: 1..5。重推入口与测试页共用（payload 'test:n'）。
  ///
  /// ⚠️ +12：2/3/5（常驻类）由测试页前台巡查复活，划掉 2 秒内自动回来；
  ///    1（铃声）/4（已下课）划掉即消失（语义如此）。
  Future<void> showTest(int n) async {
    await _log('showTest($n) begin');
    try {
      await _showTestBody(n);
      await _log('showTest($n) ok');
    } catch (e) {
      await _log('showTest($n) ERROR: $e');
      rethrow;
    }
  }

  Future<void> _showTestBody(int n) async {
    final now = DateTime.now();
    final fake = Course(
      name: '高等数学',
      location: 'A301',
      dayOfWeek: now.weekday,
      startPeriod: 1,
      endPeriod: 2,
    );
    CourseMoment momentAt(int startOffsetMin, int endOffsetMin) => CourseMoment(
          course: fake,
          date: now,
          start: now.add(Duration(minutes: startOffsetMin)),
          end: now.add(Duration(minutes: endOffsetMin)),
          leadMinutes: 30,
        );

    switch (n) {
      case 1: // 上课提醒（响铃）
        final m = momentAt(30, 110);
        final details = AndroidNotificationDetails(
          _chRemind.id,
          _chRemind.name,
          channelDescription: _chRemind.description,
          importance: Importance.max,
          priority: Priority.high,
          when: m.start.millisecondsSinceEpoch,
          usesChronometer: true,
          chronometerCountDown: true,
          autoCancel: false,
          ongoing: false,
          onlyAlertOnce: false,
          largeIcon: const DrawableResourceAndroidBitmap(_icon),
          dismissIsolate: NotificationDismissedIsolate.background,
        );
        await _post(
            id: _idTestBase + 1,
            title: '高等数学',
            body: 'A301 · ${_hm(m.start)}',
            details: details,
            forceNew: true,
            payload: 'test:1');
      case 2: // 课前倒计时
        await _testBody(2, momentAt(20, 100), NotifyStage.before);
      case 3: // 上课中常驻
        await _testBody(3, momentAt(-10, 35), NotifyStage.inClass);
      case 4: // 已下课
        await _testBody(4, momentAt(-50, -10), NotifyStage.after);
      case 5: // 划掉重推实测
        final m = momentAt(-5, 40);
        final details = AndroidNotificationDetails(
          _chRemind.id,
          _chRemind.name,
          channelDescription: _chRemind.description,
          importance: Importance.max,
          priority: Priority.high,
          when: m.end.millisecondsSinceEpoch,
          usesChronometer: true,
          chronometerCountDown: true,
          showProgress: true,
          maxProgress: 100,
          progress: (ReminderEngine.progressOf(now, m) * 100).round(),
          autoCancel: false,
          ongoing: false,
          onlyAlertOnce: false,
          styleInformation: const BigTextStyleInformation(
              '把我划掉试试 —— 我会自己回来。\n点「结束显示」才真正消失。'),
          actions: const [
            AndroidNotificationAction(_actEnd, '结束显示',
                showsUserInterface: false, cancelNotification: true),
          ],
          dismissIsolate: NotificationDismissedIsolate.background,
        );
        await _post(
            id: _idTestBase + 5,
            title: '高等数学',
            body: '把我划掉试试 —— 我会自己回来',
            details: details,
            forceNew: true,
            payload: 'test:5');
    }
  }

  Future<void> _testBody(int n, CourseMoment m, NotifyStage stage) async {
    final now = DateTime.now();
    switch (stage) {
      case NotifyStage.before:
        final details = AndroidNotificationDetails(
          _chRemind.id,
          _chRemind.name,
          channelDescription: _chRemind.description,
          importance: Importance.max,
          priority: Priority.high,
          when: m.start.millisecondsSinceEpoch,
          usesChronometer: true,
          chronometerCountDown: true,
          showProgress: true,
          maxProgress: 100,
          progress: (ReminderEngine.progressOf(now, m) * 100).round(),
          autoCancel: false,
          ongoing: false,
          onlyAlertOnce: false,
          actions: const [
            AndroidNotificationAction(_actEnd, '结束显示',
                showsUserInterface: false, cancelNotification: true),
          ],
          dismissIsolate: NotificationDismissedIsolate.background,
        );
        await _post(
            id: _idTestBase + n,
            title: '高等数学',
            body: 'A301 · ${_hm(m.start)}',
            details: details,
            forceNew: true,
            payload: 'test:$n');
      case NotifyStage.inClass:
        final details = AndroidNotificationDetails(
          _chRemind.id,
          _chRemind.name,
          channelDescription: _chRemind.description,
          importance: Importance.max,
          priority: Priority.high,
          when: m.end.millisecondsSinceEpoch,
          usesChronometer: true,
          chronometerCountDown: true,
          showProgress: true,
          maxProgress: 100,
          progress: (ReminderEngine.progressOf(now, m) * 100).round(),
          autoCancel: false,
          ongoing: false,
          onlyAlertOnce: false,
          actions: const [
            AndroidNotificationAction(_actEnd, '结束显示',
                showsUserInterface: false, cancelNotification: true),
          ],
          dismissIsolate: NotificationDismissedIsolate.background,
        );
        await _post(
            id: _idTestBase + n,
            title: '高等数学',
            body: '距下课 ${TimeFormats.hoursMinutes(m.end.difference(now).inMinutes)}',
            details: details,
            forceNew: true,
            payload: 'test:$n');
      case NotifyStage.after:
        final details = AndroidNotificationDetails(
          _chRemind.id,
          _chRemind.name,
          channelDescription: _chRemind.description,
          importance: Importance.max,
          priority: Priority.high,
          autoCancel: false,
          ongoing: false,
          onlyAlertOnce: false,
          actions: const [
            AndroidNotificationAction(_actEnd, '结束显示',
                showsUserInterface: false, cancelNotification: true),
          ],
          dismissIsolate: NotificationDismissedIsolate.background,
        );
        await _post(
            id: _idTestBase + n,
            title: '高等数学 已下课',
            body: null,
            details: details,
            forceNew: true,
            payload: 'test:$n');
    }
  }

  /// 清除单个测试通知（n: 1..5）。cancel 不触发划掉回调，故不会被重推。
  Future<void> clearTest(int n) => _plugin.cancel(id: _idTestBase + n);

  /// 测试倒计时闹钟 id（与真实链 _alarmId 独立）。
  static const int _testAlarmId = 2002;

  /// 启动测试倒计时（闹钟驱动：300 秒，每 15 秒一跳）。
  Future<void> startTestCountdown({int totalSeconds = 300}) async {
    await AndroidAlarmManager.cancel(_testAlarmId);
    final end = DateTime.now().add(Duration(seconds: totalSeconds));
    await showTestInClassCountdown(totalSeconds: totalSeconds, end: end);
    await _scheduleTestTick(totalSeconds, end);
  }

  /// 停止测试倒计时并清除其通知。
  Future<void> stopTestCountdown() async {
    await AndroidAlarmManager.cancel(_testAlarmId);
    await _plugin.cancel(id: _idTestBase + 3);
  }

  /// 测试倒计时 tick（闹钟回调进入）。
  Future<void> onTestTick(int total, int endMs) async {
    final end = DateTime.fromMillisecondsSinceEpoch(endMs);
    await showTestInClassCountdown(totalSeconds: total, end: end);
    if (end.difference(DateTime.now()).inSeconds > 0) {
      await _scheduleTestTick(total, end);
    }
  }

  Future<void> _scheduleTestTick(int total, DateTime end) {
    return AndroidAlarmManager.oneShotAt(
      DateTime.now().add(const Duration(seconds: 15)),
      _testAlarmId,
      notifyTestTickAlarm,
      exact: true,
      wakeup: true,
      allowWhileIdle: true,
      params: {'total': total, 'endMs': end.millisecondsSinceEpoch},
    );
  }

  /// 测试 3 专用：真倒计时（闹钟每 15 秒驱动刷新一次）。
  ///
  /// ⚠️ +23 修复（用户日志定案）：剩余时间必须按**墙上时钟**算 ——
  ///    之前按「每跳减 15 秒」预算制，闹钟被 Doze 推迟（实测 94~206s 一跳）
  ///    时文本与真实时间越拖越脱节（「5分钟」挂几分钟 = 用户报「不更新」）。
  Future<void> showTestInClassCountdown({
    required int totalSeconds,
    required DateTime end,
  }) async {
    final now = DateTime.now();
    final leftSeconds =
        end.difference(now).inSeconds.clamp(0, totalSeconds);
    final details = AndroidNotificationDetails(
      _chRemind.id,
      _chRemind.name,
      channelDescription: _chRemind.description,
      importance: Importance.max,
      priority: Priority.high,
      when: end.millisecondsSinceEpoch,
      usesChronometer: true,
      chronometerCountDown: true,
      showProgress: true,
      maxProgress: 100,
      progress: totalSeconds <= 0
          ? 100
          : (((totalSeconds - leftSeconds) / totalSeconds) * 100)
              .round()
              .clamp(0, 100),
      autoCancel: false,
      ongoing: false,
      onlyAlertOnce: true,
      visibility: NotificationVisibility.public,
      largeIcon: const DrawableResourceAndroidBitmap(_icon),
      actions: const [
        AndroidNotificationAction(_actEnd, '结束显示',
            showsUserInterface: false, cancelNotification: true),
      ],
      dismissIsolate: NotificationDismissedIsolate.background,
    );
    await _post(
      id: _idTestBase + 3,
      title: '高等数学',
      body: leftSeconds > 0
          ? '距下课 ${TimeFormats.hoursMinutes((leftSeconds / 60).ceil())}'
          : '已下课',
      details: details,
      forceNew: false, // 同 id 更新（不闪断，且能复活被划掉的）
      payload: 'test:3',
    );
  }

  /// 指定 id 的通知是否存活（划掉看门狗用）。
  Future<bool> isTestAlive(int n) async {
    final list = await _plugin
            .resolvePlatformSpecificImplementation<
                AndroidFlutterLocalNotificationsPlugin>()
            ?.getActiveNotifications() ??
        const [];
    return list.any((a) => a.id == _idTestBase + n);
  }

  /// 清除全部测试通知。
  Future<void> clearTests() async {
    for (var i = 1; i <= 5; i++) {
      await _plugin.cancel(id: _idTestBase + i);
    }
  }

  // ============================================================
  // 数据装载（后台 isolate 内独立开库；本地库是唯一数据源）
  // ============================================================

  Future<List<CourseMoment>> _loadMoments(
      DateTime now, NotifyPrefs prefs) async {
    final db = AppDatabase();
    try {
      final meta = await MetaRepository(db).getAll();
      final term = meta[MetaKeys.currentTerm];
      final fmRaw = meta[MetaKeys.firstMonday];
      if (term == null || term.isEmpty || fmRaw == null || fmRaw.isEmpty) {
        return const [];
      }
      final fm = TimeFormats.parseIso(fmRaw);
      if (fm == null) return const [];
      final holidayRaw = meta[MetaKeys.holidays] ?? '';
      final holidays = holidayRaw.isEmpty
          ? const <HolidayRange>[]
          : HolidayCalendar.fromJson(holidayRaw);

      final courses = await CourseRepository(db).observeByTerm(term).first;
      final overrides = await DayOverrideRepository(db).observeAll().first;

      final res = <CourseMoment>[];
      for (final offset in [0, 1]) {
        final date = DateTime(now.year, now.month, now.day + offset);
        if (holidays.any((h) => h.contains(date))) continue;
        final week = WeekCalculator.weekOf(fm, date);
        final day = ReminderEngine.dayCoursesOf(courses, overrides, date, week);
        res.addAll(
            ReminderEngine.momentsOfDay(day, date, leadMinutes: prefs.leadMinutes));
      }
      return res;
    } finally {
      await db.close();
    }
  }

  /// 读取落盘日志（测试页「查看日志」用）。
  Future<String> readLog() async {
    try {
      final dir = await getExternalStorageDirectory() ??
          await getApplicationDocumentsDirectory();
      final f = File('${dir.path}/notify_log.txt');
      if (!await f.exists()) return '（暂无日志）';
      return await f.readAsString();
    } catch (e) {
      return '读取失败：$e';
    }
  }

  static String _hm(DateTime d) =>
      '${d.hour.toString().padLeft(2, '0')}:${d.minute.toString().padLeft(2, '0')}';
}
