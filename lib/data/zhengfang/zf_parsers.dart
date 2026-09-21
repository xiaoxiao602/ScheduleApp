import 'dart:convert';

import '../../domain/models.dart';
import '../../domain/core_logic.dart';

/// ============================================================
/// 正方【考试安排】解析器（对应 ZfExamParser.kt，ADR-013）
/// ============================================================
///
/// 接口：POST /jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105
/// 响应形如 jqGrid 分页结构：`{"items":[{...}], "totalResult":N}`
///
/// ⚠️ 字段名依据正方 jwglxt 通用命名，但**本校响应尚未用真实数据验证**
///    （学生刚入学、暂无考试），故每个字段都做容错：
///    缺失即回退空串/null，**不抛异常**。
abstract final class ZfExamParser {
  /// 支持的日期格式（对应 Kotlin DATE_FMTS）。
  static final List<RegExp> _datePatterns = [
    RegExp(r'^(\d{4})-(\d{1,2})-(\d{1,2})$'),
    RegExp(r'^(\d{4})/(\d{1,2})/(\d{1,2})$'),
    RegExp(r'^(\d{4})年(\d{1,2})月(\d{1,2})日$'),
  ];

  static final _timeRe = RegExp(r'(\d{1,2}):(\d{2})');

  /// 解析考试列表。
  ///
  /// 无数据或格式不符返回空列表（**不抛异常**）。
  static List<Exam> parse(String? raw, String term) {
    if (raw == null || raw.trim().isEmpty) return [];
    // 会话失效时服务端返回 HTML 登录页
    if (raw.trimLeft().startsWith('<')) return [];

    dynamic decoded;
    try {
      decoded = jsonDecode(raw);
    } catch (_) {
      return [];
    }
    if (decoded is! Map) return [];
    final items = decoded['items'];
    if (items is! List) return [];

    final out = <Exam>[];
    for (final it in items) {
      if (it is! Map) continue;
      final name = _s(it['kcmc']).trim();
      if (name.isEmpty) continue;

      final when = _extractWhen(it);
      final campus = _s(it['xqmc']).trim();
      final room = _s(it['cdmc']).trim();
      final location = [campus, room].where((s) => s.isNotEmpty).join(' ');

      out.add(Exam(
        courseName: name,
        date: when.date,
        startTime: when.start,
        endTime: when.end,
        location: location,
        seat: _s(it['zwh']),
        format: _s(it['ksxs']),
        note: _s(it['bz']),
        term: term,
      ));
    }
    return out;
  }

  static String _s(dynamic v) => v == null ? '' : v.toString();

  /// 从可能的多种字段组合中取出 (日期, 开始, 结束)。
  ///
  /// ⚠️ 容错点：`kssj` 常是 "2026-01-05 09:00~11:00" 这种合并串。
  static ({String? date, String? start, String? end}) _extractWhen(Map it) {
    final merged = _s(it['kssj']);
    var date = _parseDate(_s(it['ksrq'])) ?? _parseDate(merged);
    var start = _parseTime(_s(it['kssjStr']));
    var end = _parseTime(_s(it['jssjStr']));

    if (merged.isNotEmpty) {
      if (date == null) {
        final dp = RegExp(r'\d{4}[-/年]\d{1,2}[-/月]\d{1,2}').firstMatch(merged)?.group(0);
        if (dp != null) date = _parseDate(dp);
      }
      if (start == null || end == null) {
        final times = _timeRe.allMatches(merged).map((m) => m.group(0)!).toList();
        if (start == null && times.isNotEmpty) start = _parseTime(times[0]);
        if (end == null && times.length > 1) end = _parseTime(times[1]);
      }
    }
    return (date: date, start: start, end: end);
  }

  /// 解析日期 → ISO "yyyy-MM-dd"；失败返回 null。
  static String? _parseDate(String? s) {
    if (s == null || s.trim().isEmpty) return null;
    final t = s.trim();
    for (final re in _datePatterns) {
      final m = re.firstMatch(t);
      if (m != null) {
        return _iso(int.parse(m.group(1)!), int.parse(m.group(2)!), int.parse(m.group(3)!));
      }
    }
    // 从长串里抠日期
    final m = RegExp(r'(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})').firstMatch(t);
    if (m == null) return null;
    return _iso(int.parse(m.group(1)!), int.parse(m.group(2)!), int.parse(m.group(3)!));
  }

  static String? _iso(int y, int mo, int d) {
    if (mo < 1 || mo > 12 || d < 1 || d > 31) return null;
    return '${y.toString().padLeft(4, '0')}-'
        '${mo.toString().padLeft(2, '0')}-'
        '${d.toString().padLeft(2, '0')}';
  }

  /// 解析时间 → "HH:mm"；失败返回 null。
  static String? _parseTime(String? s) {
    if (s == null || s.trim().isEmpty) return null;
    final m = _timeRe.firstMatch(s.trim());
    if (m == null) return null;
    final h = int.parse(m.group(1)!);
    final mi = int.parse(m.group(2)!);
    if (h > 23 || mi > 59) return null;
    return '${h.toString().padLeft(2, '0')}:${mi.toString().padLeft(2, '0')}';
  }
}

/// ============================================================
/// 从 `cxRsd`（日程配置）响应提取学期第一周星期一。
/// （对应 ZfDateRangeParser.kt，ADR-011）
/// ============================================================
///
/// ⚠️ 为什么写得这么"宽容"：`cxRsd` 的响应结构**尚未经真实数据确认**。
///    与其猜一个字段名然后在真机上失败，不如：
///      ① 优先按已知候选字段名精确匹配
///      ② 失败则退化到「扫描响应里所有 ISO 日期，取最早的那个」
///      ③ 再失败则由调用方兜底（本周一）
abstract final class ZfDateRangeParser {
  /// 优先尝试的字段名（按可能性排序，均未真机确认）。
  static const List<String> keyCandidates = [
    'xqkssj', 'kkqssj', 'xqksrq', 'kssj', 'qsrq',
    'xqks', 'sjqssj', 'firstDay', 'startDate', 'ksrq',
  ];

  /// 任意位置的 ISO 日期。
  static final _anyIso = RegExp(r'(20\d{2})-(\d{1,2})-(\d{1,2})');

  /// 中文日期（2026年8月31日）。
  static final _anyCn = RegExp(r'(20\d{2})年(\d{1,2})月(\d{1,2})日');

  /// 返回第一周星期一；解析不出返回 null。
  static DateTime? firstMonday(String? raw) {
    if (raw == null || raw.trim().isEmpty) return null;

    // ---- ① 精确字段名优先 ----
    for (final key in keyCandidates) {
      final re = RegExp('"$key"\\s*:\\s*"([^"]{6,20})"');
      final hit = re.firstMatch(raw)?.group(1);
      if (hit == null) continue;
      final d = _parseDate(hit);
      if (d != null) return _normalize(d);
    }

    // ---- ② 兜底：扫描所有日期，取最早的 ----
    final all = <DateTime>[];
    for (final m in _anyIso.allMatches(raw)) {
      final d = _mk(int.parse(m.group(1)!), int.parse(m.group(2)!), int.parse(m.group(3)!));
      if (d != null) all.add(d);
    }
    for (final m in _anyCn.allMatches(raw)) {
      final d = _mk(int.parse(m.group(1)!), int.parse(m.group(2)!), int.parse(m.group(3)!));
      if (d != null) all.add(d);
    }
    if (all.isEmpty) return null;
    all.sort();
    return _normalize(all.first);
  }

  /// 归一化为「该周的星期一」。
  ///
  /// ⚠️ 教务返回的起始日未必正好是周一，而周次计算必须以周一为基准，
  ///    否则整体会偏移 —— 故统一往前归到本周一。
  static DateTime _normalize(DateTime d) => WeekCalculator.mondayOf(d);

  static DateTime? _mk(int y, int mo, int d) {
    if (mo < 1 || mo > 12 || d < 1 || d > 31) return null;
    return DateTime(y, mo, d);
  }

  static DateTime? _parseDate(String s) {
    final iso = _anyIso.firstMatch(s);
    if (iso != null) {
      return _mk(int.parse(iso.group(1)!), int.parse(iso.group(2)!), int.parse(iso.group(3)!));
    }
    final cn = _anyCn.firstMatch(s);
    if (cn != null) {
      return _mk(int.parse(cn.group(1)!), int.parse(cn.group(2)!), int.parse(cn.group(3)!));
    }
    return null;
  }
}

/// ============================================================
/// 校历 / 假日解析（对应 ZfCalendarParser.kt，ADR-060）
/// ============================================================
///
/// ⚠️ 解析失败时返回**空列表** —— 调用方据此决定"不覆盖已有数据"，
///    否则一次网络抖动就把上次同步到的假期抹掉了。
abstract final class ZfCalendarParser {
  static final _isoRange = RegExp(
    r'(20\d{2})-(\d{1,2})-(\d{1,2})\s*[~～\-至到]+\s*(20\d{2})-(\d{1,2})-(\d{1,2})',
  );
  static final _cnRange = RegExp(
    r'(20\d{2})年(\d{1,2})月(\d{1,2})日\s*[~～\-至到]+\s*(?:20\d{2}年)?(\d{1,2})月(\d{1,2})日',
  );

  /// 解析假日区间列表。失败返回 []。
  static List<HolidayRange> parse(String? raw) {
    if (raw == null || raw.trim().isEmpty) return [];
    if (raw.trimLeft().startsWith('<')) return [];

    final out = <HolidayRange>[];

    for (final m in _isoRange.allMatches(raw)) {
      final s = _iso(int.parse(m.group(1)!), int.parse(m.group(2)!), int.parse(m.group(3)!));
      final e = _iso(int.parse(m.group(4)!), int.parse(m.group(5)!), int.parse(m.group(6)!));
      if (s != null && e != null) out.add(HolidayRange(start: s, end: e));
    }
    for (final m in _cnRange.allMatches(raw)) {
      // 中文区间跨年时结束年可能省略，这里用起始年兜底
      final y = int.parse(m.group(1)!);
      final s = _iso(y, int.parse(m.group(2)!), int.parse(m.group(3)!));
      final e = _iso(y, int.parse(m.group(4)!), int.parse(m.group(5)!));
      if (s != null && e != null) out.add(HolidayRange(start: s, end: e));
    }
    return out;
  }

  static String? _iso(int y, int mo, int d) {
    if (mo < 1 || mo > 12 || d < 1 || d > 31) return null;
    return '${y.toString().padLeft(4, '0')}-'
        '${mo.toString().padLeft(2, '0')}-'
        '${d.toString().padLeft(2, '0')}';
  }
}
