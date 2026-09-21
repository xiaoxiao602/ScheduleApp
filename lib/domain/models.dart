import 'package:flutter/foundation.dart' show immutable;

import 'core_logic.dart' show TimeFormats;

/// ============================================================
/// 领域模型（一比一对应原 Android 版 domain/model/*.kt）
/// ============================================================
///
/// ⚠️ Course 定义在 `data/zhengfang/schedule_parser.dart`（含 term 字段，
///    与 Room 实体字段完全对齐），此处**不再重复定义**，只做导出，
///    避免同一概念出现两份定义导致类型不兼容。
export '../data/zhengfang/schedule_parser.dart' show Course, WeekRange, ZfStudent;

/// 考试（对应 domain/model/Course.kt 的 Exam）。
///
/// ⚠️ date/startTime/endTime 可为 null —— 教务有时不给日期。
@immutable
class Exam {
  const Exam({
    this.id = 0,
    required this.courseName,
    this.date,
    this.startTime,
    this.endTime,
    this.location = '',
    this.seat = '',
    this.format = '',
    this.note = '',
    this.term = '',
  });

  final int id;
  final String courseName;

  /// ISO 日期 "2026-01-15"，可为 null。
  final String? date;

  /// "HH:mm"，可为 null。
  final String? startTime;
  final String? endTime;

  final String location;

  /// 座位号
  final String seat;

  /// 考试形式（闭卷/开卷）
  final String format;
  final String note;
  final String term;
}

/// 成绩（对应 domain/model/Course.kt 的 Grade）。
///
/// ⚠️ score 是 String —— 可能是数值也可能是等级（优秀/良好/通过）。
@immutable
class Grade {
  const Grade({
    this.id = 0,
    required this.term,
    required this.courseName,
    required this.score,
    this.gpa = '',
    this.credit = '',
    this.category = '',
    this.creditGpa = '',
  });

  final int id;
  final String term; // "2025-2026-1"
  final String courseName;
  final String score;
  final String gpa;
  final String credit;
  final String category; // 必修/选修
  final String creditGpa;
}

/// 学期（对应 domain/model/Course.kt 的 Term）。
///
/// ⚠️ 与教务接口参数用的 `Term`（session_client.dart）不同名同形：
///    那个是 (year, term) 请求参数，这个是展示用模型。
///    故这里叫 TermInfo 以避免冲突。
@immutable
class TermInfo {
  const TermInfo({required this.code, required this.displayName});

  final String code;
  final String displayName;

  /// 原 Kotlin：displayOf("2025-2026-1") -> "2025-2026 学年第一学期"
  static String displayOf(String code) {
    final idx = code.lastIndexOf('-');
    if (idx <= 0) return code;
    final year = code.substring(0, idx);
    final n = code.substring(idx + 1);
    final label = switch (n) {
      '1' => '第一学期',
      '2' => '第二学期',
      '3' => '第三学期',
      _ => '$n 学期',
    };
    return '$year 学年$label';
  }
}

/// 当日调课（对应 data/local/DayOverride.kt 的 sealed interface DayOverride）。
sealed class DayOverride {
  const DayOverride();

  /// 无调课（原样显示）
  static const DayOverride none = _None();

  /// 临时无课
  static const DayOverride noClass = _NoClass();

  /// 用第 sourceDay 天（1..7）的课表
  static DayOverride useDay(int sourceDay) => _UseDay(sourceDay);
}

class _None extends DayOverride {
  const _None();
}

class _NoClass extends DayOverride {
  const _NoClass();
}

class _UseDay extends DayOverride {
  const _UseDay(this.sourceDay);
  final int sourceDay;
}

/// 假日区间（对应 domain/HolidayCalendar.kt）。
@immutable
class HolidayRange {
  const HolidayRange({required this.start, required this.end});

  /// ISO "2026-10-01"
  final String start;
  final String end;

  bool contains(DateTime d) {
    final s = _parse(start);
    final e = _parse(end);
    if (s == null || e == null) return false;
    final day = DateTime(d.year, d.month, d.day);
    return !day.isBefore(s) && !day.isAfter(e);
  }

  static DateTime? _parse(String s) {
    try {
      final p = s.split('-');
      if (p.length != 3) return null;
      return DateTime(int.parse(p[0]), int.parse(p[1]), int.parse(p[2]));
    } catch (_) {
      return null;
    }
  }

  Map<String, dynamic> toJson() => {'start': start, 'end': end};

  static HolidayRange? fromJson(Map<String, dynamic> j) {
    final s = j['start'];
    final e = j['end'];
    if (s is! String || e is! String) return null;
    return HolidayRange(start: s, end: e);
  }
}

/// 假日工具（对应 domain/HolidayCalendar.kt）。
///
/// ⚠️ 渲染时**直接跳过**假日的课（不做任何标记）——
///    用户明确要求"假期就当没课"。
abstract final class HolidayCalendar {
  static String toJson(List<HolidayRange> ranges) {
    final list = ranges.map((r) => r.toJson()).toList();
    // 手写 JSON 避免引入额外依赖；结构简单且固定。
    final parts = list.map((m) =>
        '{"start":"${m['start']}","end":"${m['end']}"}').join(',');
    return '[$parts]';
  }

  /// 解析假日 JSON；失败返回空列表（调用方据此**决定不覆盖**已有数据）。
  static List<HolidayRange> fromJson(String? json) {
    if (json == null || json.trim().isEmpty) return [];
    try {
      final decoded = _decodeArray(json);
      return decoded;
    } catch (_) {
      return [];
    }
  }

  static List<HolidayRange> _decodeArray(String json) {
    // 极简解析：匹配 {"start":"...","end":"..."} 对
    final re = RegExp(r'\{"start":"([^"]+)","end":"([^"]+)"\}');
    return re
        .allMatches(json)
        .map((m) => HolidayRange(start: m.group(1)!, end: m.group(2)!))
        .toList();
  }

  /// 某天是否在假期内。
  static bool isHoliday(List<HolidayRange> ranges, DateTime d) =>
      ranges.any((r) => r.contains(d));
}

/// 近期考试过滤（对应 domain/UpcomingExamFilter.kt）。
///
/// ⚠️ 只显示**未来 30 天内**的考试；已过的不显示。
///    date 为 null 的考试（教务有时不给日期）也排除，
///    否则无法判断是否"近期"。
abstract final class UpcomingExamFilter {
  /// 未来天数窗口。
  static const int windowDays = 30;

  /// 筛选并按时间升序返回。
  static List<Exam> filter(List<Exam> exams, DateTime now) {
    final today = DateTime(now.year, now.month, now.day);
    final limit = today.add(const Duration(days: windowDays));

    final out = <Exam>[];
    for (final e in exams) {
      final d = e.date;
      if (d == null) continue;
      final dt = TimeFormats.parseIso(d);
      if (dt == null) continue;
      // 今天及以后、窗口内
      if (!dt.isBefore(today) && !dt.isAfter(limit)) out.add(e);
    }
    out.sort((a, b) {
      final c = (a.date ?? '').compareTo(b.date ?? '');
      if (c != 0) return c;
      return (a.startTime ?? '').compareTo(b.startTime ?? '');
    });
    return out;
  }
}
