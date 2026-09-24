import '../data/zhengfang/schedule_parser.dart' show Course;
import 'core_logic.dart';

/// ============================================================
/// 提醒引擎（1.2.1 方案 E）—— 纯逻辑，无 IO，可单测
/// ============================================================
///
/// 两阶段状态机（讨论文档 §5.2）：
///   课前 [remindAt, start) → 上课 [start, end) → 已下课 [end, …)
/// 通知内容、进度条、秒表（系统 Chronometer 走字）都由这三个时刻推导。

/// 一节课的时间锚点。
class CourseMoment {
  const CourseMoment({
    required this.course,
    required this.date,
    required this.start,
    required this.end,
    required this.leadMinutes,
  });

  final Course course;

  /// 上课日（0 点）。
  final DateTime date;

  /// 上课时刻 / 下课时刻。
  final DateTime start;
  final DateTime end;

  /// 提前量（分钟）。
  final int leadMinutes;

  /// 提醒时刻 = start - leadMinutes。
  DateTime get remindAt => start.subtract(Duration(minutes: leadMinutes));

  /// 稳定标识（同一天同一节次唯一），用于「结束显示」抑制与阶段跟踪。
  String get key => '${TimeFormats.iso(date)}-${course.startPeriod}';
}

/// 通知阶段。
enum NotifyStage {
  /// [remindAt, start) —— 课前倒计时。
  before,

  /// [start, end) —— 上课中（距下课倒计时 + 课程进度）。
  inClass,

  /// [end, …) —— 已下课。
  after,
}

abstract final class ReminderEngine {
  /// "HH:mm" + 日期 → 时刻；空串/非法返回 null。
  static DateTime? at(DateTime date, String hhmm) {
    if (hhmm.length < 5) return null;
    final parts = hhmm.split(':');
    final h = int.tryParse(parts[0]);
    final m = int.tryParse(parts[1]);
    if (h == null || m == null) return null;
    return DateTime(date.year, date.month, date.day, h, m);
  }

  /// 某天的全部时刻（dayCourses 需已应用调课/周次过滤）。
  static List<CourseMoment> momentsOfDay(
    List<Course> dayCourses,
    DateTime date, {
    required int leadMinutes,
  }) {
    final res = <CourseMoment>[];
    for (final c in dayCourses) {
      final s = at(date, PeriodTime.startOf(c.startPeriod));
      final e = at(date, PeriodTime.endOf(c.endPeriod));
      if (s == null || e == null || !e.isAfter(s)) continue;
      res.add(CourseMoment(
        course: c,
        date: date,
        start: s,
        end: e,
        leadMinutes: leadMinutes,
      ));
    }
    res.sort((a, b) => a.start.compareTo(b.start));
    return res;
  }

  /// 当日课表（应用调课语义，与 ui/providers.dart 的 coursesForDate 一致）。
  ///
  /// ⚠️ 有意复制而不是 import providers —— 提醒引擎要能在后台 isolate
  ///    无 Flutter 依赖地运行，且 providers 反过来依赖本模块的通知服务。
  static List<Course> dayCoursesOf(
    List<Course> all,
    Map<String, int> overrides,
    DateTime date,
    int week,
  ) {
    final iso = TimeFormats.iso(date);
    final src = overrides[iso];
    if (src == -1) return const [];
    final effectiveDay = (src != null && src >= 1 && src <= 7) ? src : date.weekday;
    return all
        .where((c) => c.dayOfWeek == effectiveDay && c.occursInWeek(week))
        .toList()
      ..sort((a, b) => a.startPeriod.compareTo(b.startPeriod));
  }

  /// now 所处阶段。
  static NotifyStage stageOf(DateTime now, CourseMoment m) {
    if (now.isBefore(m.start)) return NotifyStage.before;
    if (now.isBefore(m.end)) return NotifyStage.inClass;
    return NotifyStage.after;
  }

  /// 进度 0.0~1.0：
  ///   课前 = 等待进度（remindAt → start）；上课 = 课程进度（start → end）；下课 = 1。
  static double progressOf(DateTime now, CourseMoment m) {
    switch (stageOf(now, m)) {
      case NotifyStage.before:
        final total = m.start.difference(m.remindAt).inSeconds;
        if (total <= 0) return 1;
        return (now.difference(m.remindAt).inSeconds / total).clamp(0.0, 1.0);
      case NotifyStage.inClass:
        return CourseProgress.fraction(now, m.start, m.end);
      case NotifyStage.after:
        return 1;
    }
  }

  /// 正在进行中的时刻（含课前窗口）；没有则 null。
  static CourseMoment? activeOf(List<CourseMoment> ms, DateTime now) {
    for (final m in ms) {
      if (!now.isBefore(m.remindAt) && now.isBefore(m.end)) return m;
    }
    return null;
  }

  /// 最近一节已下课的时刻；没有则 null。
  static CourseMoment? endedOf(List<CourseMoment> ms, DateTime now) {
    CourseMoment? best;
    for (final m in ms) {
      if (now.isBefore(m.end)) continue;
      if (best == null || m.end.isAfter(best.end)) best = m;
    }
    return best;
  }
}
