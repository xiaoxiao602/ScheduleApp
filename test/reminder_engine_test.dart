import 'package:flutter_test/flutter_test.dart';
import 'package:gzuschedule/data/zhengfang/schedule_parser.dart';
import 'package:gzuschedule/domain/reminder_engine.dart';

void main() {
  // 2026-09-28 是周一。
  final date = DateTime(2026, 9, 28);
  Course course({int sp = 1, int ep = 2, String name = '高等数学', int dayOfWeek = 1}) => Course(
        name: name,
        location: 'A301',
        dayOfWeek: dayOfWeek,
        startPeriod: sp,
        endPeriod: ep,
      );

  group('momentsOfDay', () {
    test('节次→时刻（1-2 节 = 09:00-10:20，提前 30 分钟提醒）', () {
      final ms =
          ReminderEngine.momentsOfDay([course()], date, leadMinutes: 30);
      expect(ms, hasLength(1));
      expect(ms.single.start, DateTime(2026, 9, 28, 9, 0));
      expect(ms.single.end, DateTime(2026, 9, 28, 10, 20));
      expect(ms.single.remindAt, DateTime(2026, 9, 28, 8, 30));
      expect(ms.single.key, '2026-09-28-1');
    });

    test('13-14 节晚课（19:00-20:20）', () {
      final ms = ReminderEngine.momentsOfDay([course(sp: 13, ep: 14)], date,
          leadMinutes: 30);
      expect(ms.single.start, DateTime(2026, 9, 28, 19, 0));
      expect(ms.single.end, DateTime(2026, 9, 28, 20, 20));
    });

    test('按开始时刻排序', () {
      final ms = ReminderEngine.momentsOfDay(
          [course(sp: 13, ep: 14, name: '晚课'), course(sp: 1, ep: 2, name: '早课')],
          date,
          leadMinutes: 30);
      expect(ms.first.course.name, '早课');
      expect(ms.last.course.name, '晚课');
    });
  });

  group('stageOf / progressOf', () {
    final m = ReminderEngine.momentsOfDay([course()], date, leadMinutes: 30)
        .single;

    test('课前 / 上课中 / 已下课三阶段', () {
      expect(ReminderEngine.stageOf(DateTime(2026, 9, 28, 8, 45), m),
          NotifyStage.before);
      expect(ReminderEngine.stageOf(DateTime(2026, 9, 28, 9, 30), m),
          NotifyStage.inClass);
      expect(ReminderEngine.stageOf(DateTime(2026, 9, 28, 11, 0), m),
          NotifyStage.after);
    });

    test('进度 0~1 且单调', () {
      final p1 = ReminderEngine.progressOf(DateTime(2026, 9, 28, 8, 30), m);
      final p2 = ReminderEngine.progressOf(DateTime(2026, 9, 28, 8, 45), m);
      expect(p1, 0.0);
      expect(p2, greaterThan(p1));
      expect(p2, lessThan(1.0));

      // 上课中：9:00 → 0，10:20 → 1。
      expect(ReminderEngine.progressOf(DateTime(2026, 9, 28, 9, 0), m), 0.0);
      expect(ReminderEngine.progressOf(DateTime(2026, 9, 28, 10, 20), m), 1.0);
    });
  });

  group('activeOf / endedOf', () {
    final ms = ReminderEngine.momentsOfDay(
        [course(sp: 1, ep: 2), course(sp: 3, ep: 4, name: '大学英语')],
        date,
        leadMinutes: 30);

    test('课前窗口即算 active', () {
      final a = ReminderEngine.activeOf(ms, DateTime(2026, 9, 28, 8, 40));
      expect(a?.course.name, '高等数学');
    });

    test('两节课之间 → active 为下一节的课前，ended 为上一节', () {
      final now = DateTime(2026, 9, 28, 10, 30);
      final a = ReminderEngine.activeOf(ms, now);
      expect(a?.course.name, '大学英语'); // 10:40 前 30 分钟窗口未到，应为 null？
      // 10:30 < remindAt(10:10)? 3-4 节 10:40 开课，提前 30 = 10:10 —— 已进入窗口。
      final e = ReminderEngine.endedOf(ms, now);
      expect(e?.course.name, '高等数学');
    });

    test('全部结束后 ended 取最晚一节', () {
      final e =
          ReminderEngine.endedOf(ms, DateTime(2026, 9, 28, 21, 0));
      expect(e?.course.name, '大学英语');
    });
  });

  group('dayCoursesOf（调课语义与 providers.coursesForDate 一致）', () {
    final all = [
      course(sp: 1, ep: 2), // 周一 1-2 节
      course(sp: 3, ep: 4, dayOfWeek: 2, name: '周二课', ), // 周二 3-4 节
    ];

    test('无调课记录 → 原样', () {
      final day = ReminderEngine.dayCoursesOf(all, const {}, date, 1);
      expect(day, hasLength(1));
      expect(day.single.name, '高等数学');
    });

    test('调课：sourceDay=2 → 用周二课表', () {
      final day = ReminderEngine.dayCoursesOf(all, {'2026-09-28': 2}, date, 1);
      expect(day.single.name, '周二课');
    });

    test('调课：sourceDay=-1 → 无课', () {
      final day = ReminderEngine.dayCoursesOf(all, {'2026-09-28': -1}, date, 1);
      expect(day, isEmpty);
    });

    test('周次过滤（不在 startWeek..endWeek 的课不出）', () {
      final c = Course(
          name: '单周课',
          dayOfWeek: 1,
          startPeriod: 5,
          endPeriod: 6,
          startWeek: 3,
          endWeek: 3);
      expect(ReminderEngine.dayCoursesOf([c], const {}, date, 1), isEmpty);
      expect(ReminderEngine.dayCoursesOf([c], const {}, date, 3), hasLength(1));
    });
  });
}
