import 'dart:convert';

/// 课程数据模型（对应 Kotlin `domain/model/Course.kt`）。
class Course {
  /// 本地主键（自增），0 表示未入库。
  final int id;

  /// 课程名称。
  final String name;
  final String teacher;
  final String location;

  /// 星期几，1=周一 … 7=周日。
  final int dayOfWeek;

  /// 第几节开始（1-based）。
  final int startPeriod;
  final int endPeriod;

  /// 起始周（含）。
  final int startWeek;

  /// 结束周（含）。
  final int endWeek;

  /// 单双周：0=每周, 1=单周, 2=双周。
  final int weekParity;

  final String credit;
  final String note;

  /// 所属学期（如 "2026-2027学年第1学期"）。
  final String term;

  const Course({
    this.id = 0,
    required this.name,
    this.teacher = '',
    this.location = '',
    required this.dayOfWeek,
    required this.startPeriod,
    required this.endPeriod,
    this.startWeek = 1,
    this.endWeek = 20,
    this.weekParity = 0,
    this.credit = '',
    this.note = '',
    this.term = '',
  });

  /// 是否在指定周次上课。
  bool occursInWeek(int week) {
    if (week < startWeek || week > endWeek) return false;
    switch (weekParity) {
      case 1:
        return week % 2 == 1;
      case 2:
        return week % 2 == 0;
      default:
        return true;
    }
  }

  /// 节次范围，如 "1-2"。
  String get periodRange =>
      startPeriod == endPeriod ? '$startPeriod' : '$startPeriod-$endPeriod';
}

/// 正方课表解析器（对应 Kotlin `ZfScheduleParser.kt`）。
///
/// ⚠️ 已处理的三个坑（均由真实数据发现）：
///  1. 周次含单双周：`"1-17周(单)"` / `"2-18周(双)"` / `"1-18周"`
///  2. 节次有两个字段：`jcor`（纯数字）与 `jc`（带"节"字），优先 `jcor`
///  3. 同一门课一周可有多条记录（不同时段/教室），**不可去重**
class ZfScheduleParser {
  /// 解析课表 JSON。
  ///
  /// ⚠️ 必须容错：教务系统在会话失效/出错时会返回 HTML 登录页而非 JSON，
  /// 若直接抛异常会导致 App 崩溃。因此所有解析异常都收敛为空结果。
  static ZfParseResult parse(String json) {
    if (json.trim().isEmpty) return const ZfParseResult([], null);
    if (json.trimLeft().startsWith('<')) {
      return const ZfParseResult([], null); // HTML 登录页
    }

    dynamic resp;
    try {
      resp = jsonDecode(json);
    } catch (_) {
      return const ZfParseResult([], null);
    }
    if (resp is! Map) return const ZfParseResult([], null);

    final kbList = resp['kbList'];
    final courses = <Course>[];
    if (kbList is List) {
      for (final item in kbList) {
        if (item is! Map) continue;
        final c = _toDomain(item);
        if (c != null) courses.add(c);
      }
    }

    final student = resp['xsxx'] is Map
        ? ZfStudent.fromJson(resp['xsxx'] as Map)
        : null;

    return ZfParseResult(courses, student);
  }

  static Course? _toDomain(Map item) {
    String s(String key) {
      final v = item[key];
      return v == null ? '' : v.toString().trim();
    }

    final name = s('kcmc');
    if (name.isEmpty) return null;

    final day = int.tryParse(s('xqj'));
    if (day == null || day < 1 || day > 7) return null;

    final periods = _parsePeriods(
      s('jcor').isNotEmpty ? s('jcor') : s('jcs'),
    );
    if (periods == null) return null;

    final week = parseWeeks(s('zcd'));

    return Course(
      name: name,
      teacher: s('xm'),
      location: s('cdmc'),
      dayOfWeek: day,
      startPeriod: periods[0],
      endPeriod: periods[1],
      startWeek: week.start,
      endWeek: week.end,
      weekParity: week.parity,
      credit: s('xf'),
      note: _buildNote(item),
    );
  }

  /// 解析节次，如 `"3-4"` -> [3, 4]，`"9"` -> [9, 9]。
  /// 也容忍 `"3-4节"` 这种带单位的输入。
  static List<int>? _parsePeriods(String raw) {
    if (raw.trim().isEmpty) return null;
    final cleaned = raw.replaceAll('节', '').trim();
    final parts = cleaned.split('-').map((e) => e.trim()).toList();
    if (parts.length == 1) {
      final v = int.tryParse(parts[0]);
      return v == null ? null : [v, v];
    }
    if (parts.length == 2) {
      final a = int.tryParse(parts[0]);
      final b = int.tryParse(parts[1]);
      if (a == null || b == null) return null;
      if (a <= 0 || b < a) return null;
      return [a, b];
    }
    return null;
  }

  /// 解析周次字符串。
  ///
  /// 支持的格式（均由真实数据确认）：
  ///  - `"1-18周"`       -> start=1, end=18, parity=0（每周）
  ///  - `"1-17周(单)"`    -> start=1, end=17, parity=1（单周）
  ///  - `"2-18周(双)"`    -> start=2, end=18, parity=2（双周）
  ///
  /// 解析失败时回退为「全学期每周」，避免丢课。
  static WeekRange parseWeeks(String? raw) {
    const fallback = WeekRange(1, 20, 0);
    if (raw == null || raw.trim().isEmpty) return fallback;

    final text = raw.trim();

    // 单双周标记
    final parity = text.contains('单') ? 1 : (text.contains('双') ? 2 : 0);

    // 提取 "起-止"
    final nums = RegExp(r'(\d+)\s*-\s*(\d+)').firstMatch(text);
    if (nums != null) {
      final a = int.tryParse(nums.group(1)!);
      final b = int.tryParse(nums.group(2)!);
      if (a == null || b == null) return fallback;
      if (a >= 1 && a <= 30 && b >= a && b <= 30) {
        return WeekRange(a, b, parity);
      }
      return fallback;
    }

    // 单个数字，如 "5周"
    final single = RegExp(r'(\d+)').firstMatch(text);
    if (single != null) {
      final a = int.tryParse(single.group(1)!);
      if (a != null && a >= 1 && a <= 30) return WeekRange(a, a, parity);
    }
    return fallback;
  }

  /// 组装备注：校区 + 课程性质 + 调课标记。
  static String _buildNote(Map item) {
    String s(String key) {
      final v = item[key];
      return v == null ? '' : v.toString().trim();
    }

    final parts = <String>[];
    final campus = s('xqmc');
    if (campus.isNotEmpty) parts.add(campus);
    final type = s('kcxz');
    if (type.isNotEmpty) parts.add(type);
    if (s('jxbsftkbj') == '1') parts.add('调课');
    return parts.join(' · ');
  }
}

/// 周次解析结果。
class WeekRange {
  final int start;
  final int end;
  final int parity;
  const WeekRange(this.start, this.end, this.parity);

  @override
  String toString() => 'WeekRange($start-$end, parity=$parity)';
}

/// 学生信息（对应 Kotlin `ZfStudent`）。
class ZfStudent {
  final String? studentId;
  final String? name;

  /// ⚠️ 来自 `XNMC`，如 "2026-2027"
  final String? academicYear;

  /// ⚠️ 来自 `XQMMC`（值是 "1"），**不是 XQM**（值是 "3"）。
  /// 用错会显示成 "Term3"。
  final String? termName;

  final String? className;
  final String? major;

  const ZfStudent({
    this.studentId,
    this.name,
    this.academicYear,
    this.termName,
    this.className,
    this.major,
  });

  factory ZfStudent.fromJson(Map m) {
    String? g(String k) {
      final v = m[k];
      if (v == null) return null;
      final s = v.toString().trim();
      return s.isEmpty ? null : s;
    }

    return ZfStudent(
      studentId: g('XH'),
      name: g('XM'),
      academicYear: g('XNMC'),
      termName: g('XQMMC'), // ⚠️ 不是 XQM
      className: g('BJMC'),
      major: g('ZYMC'),
    );
  }
}

/// 解析结果。
class ZfParseResult {
  final List<Course> courses;
  final ZfStudent? student;

  /// 学期第一周星期一（若响应里带了）。
  ///
  /// ⚠️ 教务课表响应**通常不含**此字段，故可为 null；
  ///    调用方按优先级兜底（cxRsd 接口 -> 本字段 -> 本周一）。
  ///    见 SyncUseCase 与 ADR-011。
  final DateTime? firstMonday;

  const ZfParseResult(this.courses, this.student, [this.firstMonday]);
}

/// ============ 自检 ============
void main() {
  var ok = true;

  void check(String label, bool pass) {
    if (!pass) ok = false;
    print(' ${pass ? "✅" : "❌"} $label');
  }

  print('=== 周次解析 ===');
  final w1 = ZfScheduleParser.parseWeeks('1-18周');
  check('"1-18周" -> $w1 (期望 WeekRange(1-18, 0))',
      w1.start == 1 && w1.end == 18 && w1.parity == 0);

  final w2 = ZfScheduleParser.parseWeeks('1-17周(单)');
  check('"1-17周(单)" -> $w2 (期望 WeekRange(1-17, 1))',
      w2.start == 1 && w2.end == 17 && w2.parity == 1);

  final w3 = ZfScheduleParser.parseWeeks('2-18周(双)');
  check('"2-18周(双)" -> $w3 (期望 WeekRange(2-18, 2))',
      w3.start == 2 && w3.end == 18 && w3.parity == 2);

  final w4 = ZfScheduleParser.parseWeeks('5周');
  check('"5周" -> $w4 (期望 WeekRange(5-5, 0))',
      w4.start == 5 && w4.end == 5 && w4.parity == 0);

  final w5 = ZfScheduleParser.parseWeeks(null);
  check('null -> $w5 (回退 WeekRange(1-20, 0))',
      w5.start == 1 && w5.end == 20 && w5.parity == 0);

  print('\n=== 节次解析 ===');
  final p1 = ZfScheduleParser.parseWeeks('2-18周(双)');
  check('parseWeeks 双周 -> parity=${p1.parity}', p1.parity == 2);

  print('\n=== 完整 JSON 解析（模拟真实响应）===');
  const sampleJson = '''
{
  "kbList": [
    {
      "kcmc": "数据库原理与应用",
      "xqj": "2",
      "jcor": "3-4",
      "zcd": "1-17周(单)",
      "cdmc": "A102",
      "xm": "张老师",
      "xf": "3.0",
      "xqmc": "示例校区",
      "kcxz": "必修"
    },
    {
      "kcmc": "高等数学II(理)",
      "xqj": "3",
      "jcor": "1-2",
      "zcd": "1-18周",
      "cdmc": "A101",
      "xm": "王老师",
      "xf": "4.0"
    }
  ],
  "xsxx": {
    "XH": "2026000001",
    "XM": "张同学",
    "XNMC": "2026-2027",
    "XQMMC": "1",
    "BJMC": "示例班级"
  }
}
''';
  final r = ZfScheduleParser.parse(sampleJson);
  check('解析出 2 门课 (实际 ${r.courses.length})', r.courses.length == 2);
  if (r.courses.isNotEmpty) {
    final c = r.courses[0];
    print('  课程1: ${c.name} | 周${c.dayOfWeek} | ${c.startPeriod}-${c.endPeriod}节 '
        '| ${c.startWeek}-${c.endWeek}周 parity=${c.weekParity} | ${c.location} | ${c.teacher}');
    check('课程1 名为「数据库原理与应用」', c.name == '数据库原理与应用');
    check('课程1 单周 parity=1', c.weekParity == 1);
    check('课程1 节次 3-4', c.startPeriod == 3 && c.endPeriod == 4);
    check('课程1 备注含「示例校区」', c.note.contains('示例校区'));
    // occursInWeek 验证
    check('occursInWeek(3)=true (单周第3周)', c.occursInWeek(3));
    check('occursInWeek(4)=false (双周第4周)', !c.occursInWeek(4));
    check('occursInWeek(19)=false (超出17周)', !c.occursInWeek(19));
  }
  check('学生姓名=张同学', r.student?.name == '张同学');
  check('⚠️ termName=XQMMC="1" (不是 XQM)', r.student?.termName == '1');
  check('academicYear=2026-2027', r.student?.academicYear == '2026-2027');

  print('\n=== 容错：HTML 登录页 ===');
  final html = ZfScheduleParser.parse('<html><body>统一身份认证</body></html>');
  check('HTML 输入 -> 空结果，不抛异常', html.courses.isEmpty);

  print('\n=== 容错：空/非法 JSON ===');
  check('空串 -> 空结果', ZfScheduleParser.parse('').courses.isEmpty);
  check('非法 JSON -> 空结果', ZfScheduleParser.parse('{bad json').courses.isEmpty);

  print('\n${ok ? "✅ 全部通过 —— 课表解析器 Dart 移植行为正确" : "❌ 存在失败项"}');
}
