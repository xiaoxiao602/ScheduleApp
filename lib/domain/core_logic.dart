import 'dart:math' as math;

/// ============================================================
/// 节次 → 时间映射（对应 domain/PeriodTime.kt）
/// ============================================================
///
/// ⚠️ 本校作息：16 小节 = 8 个大节，每大节 80 分钟。
///    表必须逐项照抄，不要"优化"。
///
/// 索引 = 大节序号（1-based），第 0 项占位。
abstract final class PeriodTime {
  static const List<(String, String)> _blocks = [
    ('', ''), // 占位
    ('09:00', '10:20'), // 第 1-2 节
    ('10:40', '12:00'), // 第 3-4 节
    ('12:30', '13:50'), // 第 5-6 节
    ('14:00', '15:20'), // 第 7-8 节
    ('15:30', '16:50'), // 第 9-10 节
    ('17:00', '18:20'), // 第 11-12 节
    ('19:00', '20:20'), // 第 13-14 节
    ('20:30', '21:50'), // 第 15-16 节
  ];

  /// 小节号 → 大节序号。
  ///
  /// 原 Kotlin：`blockOf(period) = if (period <= 0) 0 else (period + 1) / 2`
  /// 例：3-4 节 → 大节 2 → "10:40 - 12:00"
  static int blockOf(int period) => period <= 0 ? 0 : (period + 1) ~/ 2;

  /// 大节序号 → (开始, 结束)；越界返回空串。
  static (String, String) blockRange(int block) {
    if (block < 1 || block >= _blocks.length) return ('', '');
    return _blocks[block];
  }

  /// 小节区间 → 展示用时间串，如 "10:40 - 12:00"。
  static String rangeOf(int startPeriod, int endPeriod) {
    final b = blockRange(blockOf(startPeriod));
    if (b.$1.isEmpty) return '';
    // 结束时间取"结束小节所在大节"的结束时刻
    final be = blockRange(blockOf(endPeriod));
    final end = be.$2.isNotEmpty ? be.$2 : b.$2;
    return '${b.$1} - $end';
  }

  /// 开始时间（"HH:mm"），用于倒计时计算。
  static String startOf(int startPeriod) => blockRange(blockOf(startPeriod)).$1;

  /// 结束时间（"HH:mm"）。
  static String endOf(int endPeriod) => blockRange(blockOf(endPeriod)).$2;

  /// 大节总数（8）。
  static int get blockCount => _blocks.length - 1;
}

/// ============================================================
/// 周次计算（对应 domain/WeekCalculator.kt）
/// ============================================================
///
/// ⚠️ 为什么必须有「第一周星期一」：
///    教务只返回**相对周次**（"第 1-9 周"），不返回学期起始日。
///    没有它算不出"今天是第几周"。旧版用"最早课程的 startWeek"估算，
///    必然偏小 → "今天有课"被误过滤成"0 门"。
abstract final class WeekCalculator {
  /// 周次上限（原版 coerceIn(1, 30)）。
  static const int maxWeeks = 30;

  /// 给定学期第一周星期一，算某天属于第几周。
  ///
  /// 原 Kotlin：
  ///   val days = ChronoUnit.DAYS.between(firstMonday, date)
  ///   val week = Math.floorDiv(days, 7L) + 1L
  ///   return week.coerceIn(1L, 30L).toInt()
  ///
  /// ⚠️ Dart 的 ~/ 对负数向零取整，与 Kotlin 的 floorDiv 不同 —— 用 floor()。
  static int weekOf(DateTime firstMonday, DateTime date) {
    final a = DateTime(firstMonday.year, firstMonday.month, firstMonday.day);
    final b = DateTime(date.year, date.month, date.day);
    final days = b.difference(a).inDays;
    final week = (days / 7).floor() + 1;
    return week.clamp(1, maxWeeks);
  }

  /// 某天所在周的星期一。
  ///
  /// 原 Kotlin：`date.minusDays(date.dayOfWeek.value - 1)`
  static DateTime mondayOf(DateTime date) {
    final d = DateTime(date.year, date.month, date.day);
    return d.subtract(Duration(days: d.weekday - 1));
  }
}

/// ============================================================
/// 倒计时文案（对应 domain/CourseCountdown.kt）
/// ============================================================
///
/// ⚠️ 不显示秒（每秒刷新太耗电），粒度到分钟。
abstract final class CourseCountdown {
  /// now → start 的文案；已开始/已过返回 null。
  ///
  /// 原 Kotlin：
  ///   minutes < 1            -> "马上开始"
  ///   days >= 1              -> "距上课 N 天"
  ///   minutes >= 60          -> "距上课 Nh" / "距上课 Nh M分"
  ///   else                   -> "距上课 N 分钟"
  static String? text(DateTime now, DateTime start) {
    final d = start.difference(now);
    if (d.isNegative || d == Duration.zero) return null;
    final minutes = d.inMinutes;
    if (minutes < 1) return '马上开始';
    final days = d.inDays;
    if (days >= 1) return '距上课 $days 天';
    if (minutes >= 60) {
      final h = minutes ~/ 60;
      final m = minutes % 60;
      return m == 0 ? '距上课 ${h}h' : '距上课 ${h}h${m}分';
    }
    return '距上课 $minutes 分钟';
  }
}

/// ============================================================
/// 课程状态（对应 domain/CourseStatus.kt）
/// ============================================================
enum CourseStatus {
  /// now < start —— 显示倒计时（仅最近一节）
  notStarted,

  /// start <= now < end —— 显示进度条 + "进行中 · 还剩 N 分钟"
  ongoing,

  /// now >= end —— 右上角蓝色圆底白勾
  finished;

  static CourseStatus of(DateTime now, DateTime start, DateTime end) {
    if (now.isBefore(start)) return CourseStatus.notStarted;
    if (now.isBefore(end)) return CourseStatus.ongoing;
    return CourseStatus.finished;
  }
}

/// ============================================================
/// 上课进度（对应 domain/CourseProgress.kt）
/// ============================================================
abstract final class CourseProgress {
  /// 0.0 ~ 1.0；未开始 0，已结束 1。
  static double fraction(DateTime now, DateTime start, DateTime end) {
    final total = end.difference(start).inSeconds;
    if (total <= 0) return 1;
    final done = now.difference(start).inSeconds;
    return (done / total).clamp(0.0, 1.0);
  }

  /// "进行中 · 还剩 N 分钟"
  static String? ongoingText(DateTime now, DateTime end) {
    final left = end.difference(now);
    if (left.isNegative) return null;
    final m = left.inMinutes;
    return m <= 0 ? '进行中 · 不到 1 分钟' : '进行中 · 还剩 $m 分钟';
  }
}

/// ============================================================
/// 版本比较（对应 domain/UpdateChecker.kt 的 isNewer）
/// ============================================================
///
/// ⚠️ 必须逐段数字比较，不能字符串比（"1.0.10" < "1.0.9" 是错的）。
abstract final class VersionCompare {
  static List<int> parse(String v) => v
      .split('.')
      .map((s) => int.tryParse(s.replaceAll(RegExp(r'[^0-9]'), '')) ?? 0)
      .toList();

  static bool isNewer(String latest, String current) {
    final a = parse(latest);
    final b = parse(current);
    final n = math.max(a.length, b.length);
    for (var i = 0; i < n; i++) {
      final x = i < a.length ? a[i] : 0;
      final y = i < b.length ? b[i] : 0;
      if (x != y) return x > y;
    }
    return false;
  }
}

/// 时间格式化（对应 domain/TimeFormats.kt）。
abstract final class TimeFormats {
  static const _weekdays = ['一', '二', '三', '四', '五', '六', '日'];

  /// "2026年9月20日"
  static String dateCn(DateTime d) => '${d.year}年${d.month}月${d.day}日';

  /// "周一"
  static String weekdayCn(DateTime d) => '周${_weekdays[d.weekday - 1]}';

  /// "2026年9月20日 周日"
  static String dateWithWeekday(DateTime d) => '${dateCn(d)} ${weekdayCn(d)}';

  /// "9月20日 02:07"（用于"上次同步"）
  static String syncStamp(DateTime d) {
    final hh = d.hour.toString().padLeft(2, '0');
    final mm = d.minute.toString().padLeft(2, '0');
    return '${d.month}月${d.day}日 $hh:$mm';
  }

  /// 「xx小时xx分钟」—— 用户要求的倒计时显示格式。
  ///
  /// ⚠️ 规则（按用户要求）：
  ///   · 不足 1 小时   -> 「xx分钟」（省略"0小时"）
  ///   · 整小时       -> 「xx小时」
  ///   · 其余         -> 「xx小时xx分钟」
  ///   · 已过/负数     -> 「0分钟」
  ///
  /// 例：95 分钟 -> 「1小时35分钟」；45 分钟 -> 「45分钟」；120 分钟 -> 「2小时」
  static String hoursMinutes(int totalMinutes) {
    if (totalMinutes <= 0) return '0分钟';
    final h = totalMinutes ~/ 60;
    final m = totalMinutes % 60;
    if (h == 0) return '$m分钟';
    if (m == 0) return '$h小时';
    return '$h小时$m分钟';
  }

  /// 只要分钟数（不足 1 分钟显示「不到 1 分钟」）。
  ///
  /// ⚠️ 对应原版 CourseProgress.minutesLeft + CourseProgressBinder:
  ///    `"上课中 · 还剩 ${result.minutesLeft} 分钟"` —— 原版这里**只给分钟**，
  ///    不折算小时（一节课最多 80 分钟，说"1小时20分钟"反而绕）。
  static String minutesOnly(DateTime target, DateTime now) {
    final m = target.difference(now).inMinutes;
    return m <= 0 ? '不到 1 分钟' : '$m分钟';
  }

  /// 倒计时（距某个时刻还有多久），格式为「xx小时xx分钟」。
  static String untilNow(DateTime target, DateTime now) =>
      hoursMinutes(target.difference(now).inMinutes);

  /// ISO 日期（存 meta / day_override 用）
  static String iso(DateTime d) =>
      '${d.year.toString().padLeft(4, '0')}-'
      '${d.month.toString().padLeft(2, '0')}-'
      '${d.day.toString().padLeft(2, '0')}';

  /// ISO 日期时间（存 last_sync_at 用）
  static String isoTimestamp(DateTime d) =>
      '${iso(d)}T'
      '${d.hour.toString().padLeft(2, '0')}:'
      '${d.minute.toString().padLeft(2, '0')}:'
      '${d.second.toString().padLeft(2, '0')}';

  /// 解析 ISO 日期，失败返回 null。
  static DateTime? parseIso(String s) {
    try {
      final parts = s.split('-');
      if (parts.length != 3) return null;
      return DateTime(
        int.parse(parts[0]),
        int.parse(parts[1]),
        int.parse(parts[2]),
      );
    } catch (_) {
      return null;
    }
  }
}

/// 学期标题（对应 domain/TermTitle.kt）。
abstract final class TermTitle {
  /// 由 academic_year_name + term_name 拼 "AY26-27 Term1"。
  ///
  /// ⚠️ term_name 来自 XQMMC（值 "1"），不是 XQM（值 "3"）。
  static String of(String? academicYearName, String? termName) {
    if (academicYearName == null || academicYearName.isEmpty) return '';
    final ay = _shortYear(academicYearName);
    final t = (termName == null || termName.isEmpty) ? '' : ' Term$termName';
    return 'AY$ay$t';
  }

  static String _shortYear(String full) {
    // "2026-2027" -> "26-27"
    final parts = full.split('-');
    if (parts.length != 2) return full;
    String short(String s) => s.length >= 2 ? s.substring(s.length - 2) : s;
    return '${short(parts[0])}-${short(parts[1])}';
  }
}
