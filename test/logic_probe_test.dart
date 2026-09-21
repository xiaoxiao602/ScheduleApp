// 核心逻辑 + 教务解析 综合测试（70+ 项断言）。
//
// 覆盖：节次表 / 周次计算 / 倒计时 / 状态与进度 / 版本比较 /
//       颜色分配 / 时间格式化 / 正方教务各解析器（课表·考试·日期·假日）。
import 'package:flutter_test/flutter_test.dart';

import 'package:gzuschedule/data/zhengfang/schedule_parser.dart';
import 'package:gzuschedule/data/zhengfang/zf_parsers.dart';
import 'package:gzuschedule/domain/colors.dart';
import 'package:gzuschedule/domain/core_logic.dart';
import 'package:gzuschedule/domain/models.dart';


void ck(String label, bool ok, [String detail = '']) =>
    expect(ok, isTrue, reason: '$label  $detail');

void main() {
  test('核心逻辑 + 教务解析（70+ 项断言）', () {
  // ---- PeriodTime（节次表照抄）----
  ck('PeriodTime.rangeOf(3,4)', PeriodTime.rangeOf(3, 4) == '10:40 - 12:00',
      PeriodTime.rangeOf(3, 4));
  ck('PeriodTime.blockOf(3)==2', PeriodTime.blockOf(3) == 2);
  ck('PeriodTime.blockOf(1)==1', PeriodTime.blockOf(1) == 1);
  ck('PeriodTime.blockOf(0)==0', PeriodTime.blockOf(0) == 0);
  ck('PeriodTime.startOf(13)==19:00', PeriodTime.startOf(13) == '19:00',
      PeriodTime.startOf(13));
  ck('PeriodTime 共 8 大节', PeriodTime.blockCount == 8);

  // ---- WeekCalculator ----
  final mon = DateTime(2026, 9, 14);
  ck('weekOf(起始日)==1', WeekCalculator.weekOf(mon, mon) == 1);
  ck('weekOf(+6天) 仍第1周',
      WeekCalculator.weekOf(mon, DateTime(2026, 9, 20)) == 1);
  ck('weekOf(+7天)==2', WeekCalculator.weekOf(mon, DateTime(2026, 9, 21)) == 2);
  ck('weekOf(+14天)==3', WeekCalculator.weekOf(mon, DateTime(2026, 9, 28)) == 3);
  ck('weekOf(早于起始) clamp 到 1',
      WeekCalculator.weekOf(mon, DateTime(2026, 9, 1)) == 1);
  ck('weekOf 上限 30',
      WeekCalculator.weekOf(mon, DateTime(2027, 12, 31)) == 30);
  ck('mondayOf(周日)==本周一',
      WeekCalculator.mondayOf(DateTime(2026, 9, 20)) == mon);

  // ---- Course.occursInWeek（单双周）----
  Course mk(int sw, int ew, int par) => Course(
        name: 'x',
        dayOfWeek: 1,
        startPeriod: 1,
        endPeriod: 2,
        startWeek: sw,
        endWeek: ew,
        weekParity: par,
      );
  ck('每周课 第3周有', mk(1, 17, 0).occursInWeek(3));
  ck('单周课 第3周有', mk(1, 17, 1).occursInWeek(3));
  ck('单周课 第4周无', !mk(1, 17, 1).occursInWeek(4));
  ck('双周课 第4周有', mk(1, 17, 2).occursInWeek(4));
  ck('双周课 第3周无', !mk(1, 17, 2).occursInWeek(3));
  ck('超出 endWeek 无', !mk(1, 9, 0).occursInWeek(10));
  ck('早于 startWeek 无', !mk(5, 9, 0).occursInWeek(4));

  // ---- 倒计时 ----
  final t0 = DateTime(2026, 9, 20, 10, 0);
  ck('倒计时 已过->null',
      CourseCountdown.text(t0, DateTime(2026, 9, 20, 9, 0)) == null);
  ck('倒计时 <1分->马上开始',
      CourseCountdown.text(t0, DateTime(2026, 9, 20, 10, 0, 30)) == '马上开始');
  ck('倒计时 8h16分',
      CourseCountdown.text(t0, DateTime(2026, 9, 20, 18, 16)) == '距上课 8h16分',
      CourseCountdown.text(t0, DateTime(2026, 9, 20, 18, 16)) ?? 'null');
  ck('倒计时 整小时',
      CourseCountdown.text(t0, DateTime(2026, 9, 20, 12, 0)) == '距上课 2h',
      CourseCountdown.text(t0, DateTime(2026, 9, 20, 12, 0)) ?? 'null');
  ck('倒计时 分钟级',
      CourseCountdown.text(t0, DateTime(2026, 9, 20, 10, 30)) == '距上课 30 分钟');
  ck('倒计时 跨天',
      CourseCountdown.text(t0, DateTime(2026, 9, 22, 10, 0)) == '距上课 2 天');

  // ---- 状态/进度 ----
  final s = DateTime(2026, 9, 20, 10, 0), e = DateTime(2026, 9, 20, 11, 0);
  ck('状态 未开始',
      CourseStatus.of(DateTime(2026, 9, 20, 9, 0), s, e) == CourseStatus.notStarted);
  ck('状态 进行中',
      CourseStatus.of(DateTime(2026, 9, 20, 10, 30), s, e) == CourseStatus.ongoing);
  ck('状态 已结束',
      CourseStatus.of(DateTime(2026, 9, 20, 12, 0), s, e) == CourseStatus.finished);
  ck('进度 中点~0.5',
      (CourseProgress.fraction(DateTime(2026, 9, 20, 10, 30), s, e) - 0.5).abs() < 0.01);
  ck('进度 前夹 0', CourseProgress.fraction(DateTime(2026, 9, 20, 9, 0), s, e) == 0);
  ck('进度 后夹 1', CourseProgress.fraction(DateTime(2026, 9, 20, 12, 0), s, e) == 1);

  // ---- 版本比较 ----
  ck('1.0.10 > 1.0.9', VersionCompare.isNewer('1.0.10', '1.0.9'));
  ck('1.0.9 不> 1.0.10', !VersionCompare.isNewer('1.0.9', '1.0.10'));
  ck('1.1 > 1.0.9', VersionCompare.isNewer('1.1', '1.0.9'));
  ck('相同不更新', !VersionCompare.isNewer('1.0.3', '1.0.3'));

  // ---- 颜色分配 ----
  final names = List.generate(10, (i) => '课程$i');
  final asg = CourseColorAssigner.assign(names, CoursePalette.autoAssign.length);
  ck('分配覆盖全部', asg.length == 10);
  ck('10 课不撞色', asg.values.toSet().length == 10);
  final asg2 = CourseColorAssigner.assign(names.reversed, CoursePalette.autoAssign.length);
  ck('顺序无关（稳定）', asg.toString() == asg2.toString());
  ck('同课必同色', asg['课程3'] == asg2['课程3']);
  ck('autoAssign 10 色', CoursePalette.autoAssign.length == 10);
  ck('presets 24 色', CoursePalette.presets.length == 24);
  final f1 = CourseColorAssigner.assignFallback('高等数学', 12);
  final f2 = CourseColorAssigner.assignFallback('高等数学', 12);
  ck('兜底散列稳定且在界内', f1 == f2 && f1 >= 0 && f1 < 12);

  // ---- 格式化 ----
  ck('dateWithWeekday',
      TimeFormats.dateWithWeekday(DateTime(2026, 9, 20)) == '2026年9月20日 周日',
      TimeFormats.dateWithWeekday(DateTime(2026, 9, 20)));
  ck('iso', TimeFormats.iso(DateTime(2026, 9, 20)) == '2026-09-20');
  ck('parseIso 往返', TimeFormats.parseIso('2026-09-20') == DateTime(2026, 9, 20));
  ck('syncStamp',
      TimeFormats.syncStamp(DateTime(2026, 9, 20, 2, 7)) == '9月20日 02:07');
  ck('TermTitle AY26-27 Term1',
      TermTitle.of('2026-2027', '1') == 'AY26-27 Term1',
      TermTitle.of('2026-2027', '1'));
  ck('TermInfo.displayOf',
      TermInfo.displayOf('2025-2026-1') == '2025-2026 学年第一学期',
      TermInfo.displayOf('2025-2026-1'));

  // ---- 教务：周次表达式 ----
  final w1 = ZfScheduleParser.parseWeeks('1-18周');
  ck('parseWeeks("1-18周")',
      w1.start == 1 && w1.end == 18 && w1.parity == 0, w1.toString());
  final w2 = ZfScheduleParser.parseWeeks('1-17周(单)');
  ck('parseWeeks("1-17周(单)")',
      w2.start == 1 && w2.end == 17 && w2.parity == 1, w2.toString());
  final w3 = ZfScheduleParser.parseWeeks('2-18周(双)');
  ck('parseWeeks("2-18周(双)")',
      w3.start == 2 && w3.end == 18 && w3.parity == 2, w3.toString());

  // ---- 教务：课表 JSON ----
  const schedJson = '{"kbList":[{"kcmc":"数据库原理与应用","xm":"张老师",'
      '"cdmc":"A102","xqj":"2","jcs":"3-4","zcd":"1-9周","xf":"3"}],'
      '"xsxx":{"XH":"2026000001","XM":"张同学","XNMC":"2026-2027",'
      '"XQMMC":"1","BJMC":"示例班级","ZYMC":"智能科学与技术"}}';
  final pr = ZfScheduleParser.parse(schedJson);
  ck('课表解析出 1 门', pr.courses.length == 1, '${pr.courses.length}');
  if (pr.courses.isNotEmpty) {
    final c = pr.courses.first;
    ck('课程名', c.name == '数据库原理与应用', c.name);
    ck('教师', c.teacher == '张老师', c.teacher);
    ck('地点', c.location == 'A102', c.location);
    ck('星期', c.dayOfWeek == 2, '${c.dayOfWeek}');
    ck('节次', c.startPeriod == 3 && c.endPeriod == 4,
        '${c.startPeriod}-${c.endPeriod}');
  }
  ck('学生 XQMMC 作 termName（非 XQM）',
      pr.student?.termName == '1', pr.student?.termName ?? 'null');
  ck('HTML 响应 -> 空不崩',
      ZfScheduleParser.parse('<html><body>login</body></html>').courses.isEmpty);

  // ---- 教务：考试 ----
  const examJson = '{"items":[{"kcmc":"高等数学",'
      '"kssj":"2026-01-05 09:00~11:00","cdmc":"A101","zwh":"32","ksxs":"闭卷"}],'
      '"totalResult":1}';
  final exams = ZfExamParser.parse(examJson, '2026-2027学年第1学期');
  ck('考试解析出 1 条', exams.length == 1, '${exams.length}');
  if (exams.isNotEmpty) {
    ck('考试日期', exams.first.date == '2026-01-05', exams.first.date ?? 'null');
    ck('考试开始', exams.first.startTime == '09:00', exams.first.startTime ?? 'null');
    ck('考试结束', exams.first.endTime == '11:00', exams.first.endTime ?? 'null');
    ck('考场', exams.first.location == 'A101', exams.first.location);
    ck('座位', exams.first.seat == '32', exams.first.seat);
  }
  ck('考试 HTML -> 空', ZfExamParser.parse('<html/>', 't').isEmpty);
  ck('考试 null -> 空', ZfExamParser.parse(null, 't').isEmpty);

  // ---- 教务：日期区间 ----
  final fm = ZfDateRangeParser.firstMonday('{"xqkssj":"2026-09-16"}');
  ck('日期区间 归一化到周一', fm == DateTime(2026, 9, 14), '$fm');
  ck('日期区间 兜底取最早',
      ZfDateRangeParser.firstMonday('a 2026-09-20 b 2026-09-01 c') ==
          DateTime(2026, 8, 31));

  // ---- 教务：假日 ----
  final hs = ZfCalendarParser.parse('国庆 2026-10-01 ~ 2026-10-07 放假');
  ck('假日解析出 1 段', hs.length == 1, '${hs.length}');
  if (hs.isNotEmpty) {
    ck('假日起点', hs.first.start == '2026-10-01', hs.first.start);
    ck('假日终点', hs.first.end == '2026-10-07', hs.first.end);
    ck('含 10-03', HolidayCalendar.isHoliday(hs, DateTime(2026, 10, 3)));
    ck('不含 10-09', !HolidayCalendar.isHoliday(hs, DateTime(2026, 10, 9)));
  }
  ck('假日 null -> 空（不覆盖已有）', ZfCalendarParser.parse(null).isEmpty);

  // ---- 假日 JSON 往返 ----
  final hj = HolidayCalendar.toJson(
      [HolidayRange(start: '2026-10-01', end: '2026-10-07')]);
  final hb = HolidayCalendar.fromJson(hj);
  ck('假日 JSON 往返', hb.length == 1 && hb.first.start == '2026-10-01', hj);
  });
}
