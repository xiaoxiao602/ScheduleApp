import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/zhengfang/schedule_parser.dart' show Course;
import '../../domain/core_logic.dart';
import '../theme.dart';
import 'dialogs.dart';

/// ============================================================
/// 今日课程卡片 —— 严格对应原版 item_course.xml + TodayFragment.courseCard()
/// ============================================================
///
/// 原版布局（逐元素对应）：
///
///   ┌──────────────────────────────────────────────────┐
///   │ ┌──────┐ │ 课程名 16sp bold                      │  padding 18dp
///   │ │1-2节 │ │ 时间@地点 · 教师 12sp                 │  底部间距 8dp
///   │ │13sp  │ │ 倒计时 12sp bold 主色                 │
///   │ │08:00 │ │ ▓▓▓▓▓░░░░ 进度条 6dp                  │
///   │ │10sp  │ │ 进度文字 11sp 主色                    │
///   │ └──────┘ │                                       │
///   │   ↑节次列    ↑竖线 1dp×36dp     [✓ 36dp 徽章]    │
///   └──────────────────────────────────────────────────┘
///
/// ⚠️ 关键差异（之前做错的）：
///   · 左侧是**节次+时间两行**，不是色条！
///   · 节次列右侧有 **1dp×36dp 竖分隔线**（颜色 colorOutlineVariant）
///   · 有**倒计时**行（12sp bold 主色）和**进度条**（6dp 高）+ 进度文字
///   · 完成后右侧出现 **36dp 圆形 ✓ 徽章**（不是整卡变色）
///   · 卡片底色是**统一的 surfaceContainerHigh**，**不按课程变色**
///     （原版 ADR-063：颜色只做点缀，整列高彩度色块会「花、吵」）
class CourseCard extends ConsumerWidget {
  const CourseCard({
    super.key,
    required this.course,
    required this.now,
    this.showCountdown = true,
  });

  final Course course;
  final DateTime now;

  /// 是否显示倒计时/进度（只有"下一节课"显示）。
  final bool showCountdown;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = AppColors.of(context);

    final start = PeriodTime.startOf(course.startPeriod);
    final end = PeriodTime.endOf(course.endPeriod);
    // ⚠️ 进度/倒计时直接用 CourseProgress 静态方法算（原版同款逻辑），
    //    不引入额外 provider —— 卡片的 now 由外部传入，每秒/每 20 秒刷新一次即可。
    final startDt = _dateOf(start);
    final endDt = _dateOf(end);
    final frac = CourseProgress.fraction(now, startDt, endDt);
    final begun = !now.isBefore(startDt);
    final finished = !now.isBefore(endDt);
    final done = finished;

    // 倒计时文案（原版 tvCountdown / CourseProgressBinder）
    //
    // ⚠️ 三种状态，文案对齐原版 1.0.3：
    //   · 未开始 →「距上课 xx分钟」/「距上课 x小时xx分钟」
    //   · 进行中 →「上课中 · 还剩 xx分钟」  ← CourseProgressBinder.kt:101 原文
    //   · 已结束 →「已结束」
    final countdownText = finished
        ? '已结束'
        : (begun
            ? '上课中 · 还剩 ${TimeFormats.minutesOnly(endDt, now)}'
            : '距上课 ${TimeFormats.untilNow(startDt, now)}');

    return Padding(
      padding: const EdgeInsets.only(bottom: 8), // 原版 layout_marginBottom=8dp
      child: Material(
        color: c.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(20), // card_corner_medium
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => showCourseDetailDialog(context, ref, course),
          child: Padding(
            padding: const EdgeInsets.all(18), // 原版 padding=18dp
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                // ---------- 左：节次 + 起止时间 ----------
                //
                // ⚠️ 用户要求「上课起始时间和结束时间移动到左侧」——
                //    左侧一列完整交代"第几节、几点到几点"，
                //    右侧就只剩课程内容本身（名称 / 地点 / 教师）。
                //
                // ⚠️⚠️ 2026-09-22 修 bug：「第一个课程的分割线和文字位置怎么
                //    和下面的不一样」——根因是本列宽度随文字走（"3-4节" 比
                //    "13-14节" 窄 13dp）⇒ 竖分隔线和右侧整块文字逐卡左右漂移。
                //    原版 item_course.xml 用 android:minWidth="52dp" 钉住这一列，
                //    这里补齐同款约束（实测内容最宽 48.3dp < 52dp，故各卡列宽恒定）。
                ConstrainedBox(
                  constraints: const BoxConstraints(minWidth: 52),
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      Text(
                        '${course.startPeriod}-${course.endPeriod}节',
                        style: TextStyle(
                          fontSize: 13, // 原版 tvPeriod 13sp
                          fontWeight: FontWeight.bold,
                          color: c.primary,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        start,
                        style: TextStyle(
                          fontSize: 11,
                          fontWeight: FontWeight.w600,
                          color: c.onSurface,
                        ),
                      ),
                      // 起止之间的短竖线（视觉上"到"的意思）
                      Container(
                        width: 1,
                        height: 8,
                        margin: const EdgeInsets.symmetric(vertical: 1),
                        color: c.outlineVariant,
                      ),
                      Text(
                        end,
                        style: TextStyle(
                          fontSize: 11,
                          color: c.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(width: 14), // 原版 layout_marginEnd=14dp
                // ---------- 竖分隔线 1dp × 36dp ----------
                Container(
                  width: 1,
                  height: 36,
                  color: c.outlineVariant,
                ),
                const SizedBox(width: 14), // 原版 layout_marginEnd=14dp
                // ---------- 中：课程名 + 元信息 + 倒计时 + 进度 ----------
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        course.name,
                        style: TextStyle(
                          fontSize: 16, // 原版 tvName 16sp bold
                          fontWeight: FontWeight.bold,
                          color: c.onSurface,
                        ),
                      ),
                      // 地点 · 教师（时间已移到左侧，这里不再重复）
                      if (_meta.isNotEmpty) ...[
                        const SizedBox(height: 4), // 原版 marginTop=4dp
                        Text(
                          _meta,
                          style: TextStyle(
                            fontSize: 12, // 原版 tvMeta 12sp
                            color: c.onSurfaceVariant,
                          ),
                        ),
                      ],
                      // 倒计时 —— 对齐原版 TodayCourseAdapter.bindCountdown：
                      //   · 进行中 → 必定显示「上课中 · 还剩 N 分钟」
                      //   · 未开始 且是"下一节"（showCountdown）→ 显示「距上课 …」
                      //   · 已结束 / 不是下一节 → 隐藏（不占位）
                      if ((begun && !finished) ||
                          (showCountdown && !begun && countdownText.isNotEmpty)) ...[
                        const SizedBox(height: 6), // 原版 marginTop=6dp
                        Text(
                          countdownText,
                          style: TextStyle(
                            fontSize: 12, // 原版 tvCountdown 12sp bold
                            fontWeight: FontWeight.bold,
                            color: c.primary,
                          ),
                        ),
                      ],

                      // ---------- 进度条 ----------
                      //
                      // ⚠️⚠️ 用户要求「上课之前隐去进度条，上课时再显示，
                      //    和 1.0.3 一致」。
                      //
                      //    原版依据：CourseProgressBinder.bind 里
                      //      `if (result == null || !result.inProgress) {
                      //         row.visibility = View.GONE; return }`
                      //    —— **只有 inProgress（上课中）才 VISIBLE**，
                      //    未开始 / 已结束都 GONE（不占位）。
                      //
                      //    之前我的实现把进度条放在"倒计时块"里，
                      //    于是未开始的下一节课也会显示一条空的 0% 进度条 —— 不符合。
                      if (begun && !finished) ...[
                        const SizedBox(height: 8), // 原版 marginTop=8dp
                        ClipRRect(
                          borderRadius: BorderRadius.circular(3),
                          child: LinearProgressIndicator(
                            value: frac,
                            minHeight: 6, // 原版 progressCourse 6dp
                            backgroundColor: c.outlineVariant,
                            valueColor: AlwaysStoppedAnimation(c.primary),
                          ),
                        ),
                        const SizedBox(height: 4), // 原版 marginTop=4dp
                        Text(
                          '已进行 ${(frac * 100).round()}%',
                          style: TextStyle(
                            fontSize: 11, // 原版 tvProgress 11sp
                            color: c.primary,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                // ---------- 右：完成徽章 36dp ----------
                if (done) ...[
                  const SizedBox(width: 10), // 原版 marginStart=10dp
                  Container(
                    width: 36,
                    height: 36,
                    decoration: BoxDecoration(
                      color: c.primary,
                      shape: BoxShape.circle,
                    ),
                    alignment: Alignment.center,
                    child: const Icon(
                      Icons.check_rounded,
                      size: 22, // 原版 ic_check 22dp
                      color: Colors.white,
                    ),
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }



  /// 把「HH:mm」换算成今天的 DateTime（原版用 LocalTime 直接比较）。
  static DateTime _dateOf(String hhmm) {
    final p = hhmm.split(':');
    final now = DateTime.now();
    return DateTime(now.year, now.month, now.day,
        int.tryParse(p.isNotEmpty ? p[0] : '0') ?? 0,
        int.tryParse(p.length > 1 ? p[1] : '0') ?? 0);
  }

  /// 元信息 = **上课地点 · 任课教师**。
  ///
  /// ⚠️ 用户要求把起止时间移到左侧 → 这里不再包含时间，
  ///    避免同一信息在卡片里出现两次。
  ///    原版 tvMeta 是「时间@地点 · 教师」，本版按用户要求拆开。
  String get _meta {
    final parts = <String>[
      if (course.location.isNotEmpty) course.location,
      if (course.teacher.isNotEmpty) course.teacher,
    ];
    return parts.join(' · ');
  }
}
