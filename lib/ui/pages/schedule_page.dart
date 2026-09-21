import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/local/settings_stores.dart' show CardStyleMode;
import '../../domain/core_logic.dart';
import '../../domain/models.dart';
import '../providers.dart';
import '../theme.dart';
import '../widgets/status_bar_blur.dart';
import '../motion.dart';
import '../widgets/dialogs.dart';

/// ============================================================
/// 周课表页 —— 严格对应原版 fragment_schedule.xml + ScheduleFragment.kt
/// ============================================================
///
/// 原版结构（自上而下，逐行对应）：
///
///   headerBlock (paddingHorizontal=16, paddingTop=14)
///     ├─ 标题行：[「周课表」PageTitle 24sp bold] + [日历图标 24dp]
///     ├─ tvTermName        13sp  「2026-2027 学年 · 第 1 学期」
///     ├─ weekBar (48dp, marginTop=14, 圆角胶囊背景)
///     │    ┌──────────────────────┬─────────────┐
///     │    │ ‹(48dp贴左) [第N周›] │ 回到本周     │
///     │    │         (整体居中)    │  (weight=1) │
///     │    └──────────────────────┴─────────────┘
///     └─ tvWeekLabel        12sp  「9/14 - 9/20 · 共 12 门课」marginTop=8
///
///   dateStrip (HorizontalScrollView, marginTop=6)
///     └─ 7 格 × item_date_chip（固定宽，可横滑）
///
///   scrollContent (ScrollView, paddingH=16, paddingBottom=120)
///     └─ dayList：按天分组
///          · dayHeader  「周三 16日 · 2 门」
///          · 课程卡片（整宽，左侧色条）
///
/// ⚠️ 底部 Dock 是**叠层悬浮**，不占布局空间 —— 底部留白由 paddingBottom=120 负责。
class SchedulePage extends ConsumerStatefulWidget {
  const SchedulePage({super.key});

  @override
  ConsumerState<SchedulePage> createState() => _SchedulePageState();
}

class _SchedulePageState extends ConsumerState<SchedulePage> {
  /// 手动选中的星期（1..7）；null = 跟随今天。
  int? _selectedDay;

  /// 各天的锚点（日期条点选后滚动定位）。
  final Map<int, GlobalKey> _anchors = {};

  /// 日期条横向滚动控制器（用于把选中格滚入视野）。
  final _stripCtrl = ScrollController();


  @override
  void dispose() {
    _stripCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    final week = ref.watch(shownWeekProvider);
    final monday = ref.watch(firstMondayProvider);
    final courses = ref.watch(coursesProvider).valueOrNull ?? const <Course>[];
    final overrides = ref.watch(dayOverridesProvider).valueOrNull ?? const {};
    final holidays = ref.watch(holidaysProvider);
    final today = DateTime.now();
    final isCurWeek = week == ref.watch(currentWeekProvider);
    final active = _selectedDay ?? (isCurWeek ? today.weekday : null);

        // ⚠️ 状态栏渐变模糊（同 settings_page 说明）：模糊区 40dp、内容起点 52dp。
    // ⚠️ 顶部留白 = 状态栏高度 + 12dp（不能写死 blurH+12，会下沉）
    final topPad = MediaQuery.of(context).padding.top;
    // ⚠️ ScrollAwareBlurWrap：只在滑动时显示顶部渐变模糊
    return Stack(
      children: [
        SafeArea(
      top: false,
      bottom: false,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // ============ headerBlock ============
          Padding(
            // ⚠️ top = 模糊区(50) + 12dp 缓冲
            // ⚠️ 顶部留白 = topPad + 26
            //    （= 今日页的 12[外层] + 14[_Header 内] —— 三页标题对齐）
            padding: EdgeInsets.fromLTRB(16, topPad + 24, 16, 0),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                // ---------- 标题行：标题 + 学年学期（同一行）----------
                //
                // ⚠️ 用户要求「把 2026-2027 学年·第1学期 改到周课表标题后面，
                //    上移下面的控件，下面有点拥挤」。
                //    —— 原来学期标题独占一行（标题 24sp + 学期 13sp 竖向叠），
                //       导致下方「周次条 / 日期条」被挤得靠下且拥挤。
                //       现在把学期并到标题右侧（基线对齐），省下一整行。
                Row(
                  crossAxisAlignment: CrossAxisAlignment.baseline,
                  textBaseline: TextBaseline.alphabetic,
                  children: [
                    Text(
                      '周课表',
                      style: TextStyle(
                        fontSize: 26,
                        fontWeight: FontWeight.bold,
                        letterSpacing: -0.24, // 24sp × -0.01
                        color: colors.onSurface,
                      ),
                    ),
                    const SizedBox(width: 8),
                    // 学年学期 13sp —— 与标题同一行，底部对齐
                    Expanded(
                      child: Text(
                        _termTitle(ref),
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 13,
                          color: colors.onSurfaceVariant,
                        ),
                      ),
                    ),
                    Icon(Icons.calendar_month_outlined,
                        size: 24, color: colors.onSurfaceVariant),
                  ],
                ),
                // ⚠️ 省下了一行 → 间距从 14 收到 10，整体上移
                const SizedBox(height: 10),
                // ============ 周次切换条（一体胶囊）============
                _WeekBar(
                  week: week,
                  isCurWeek: isCurWeek,
                  maxWeeks: WeekCalculator.maxWeeks,
                  onPrev: () =>
                      ref.read(selectedWeekProvider.notifier).state = week - 1,
                  onNext: () =>
                      ref.read(selectedWeekProvider.notifier).state = week + 1,
                  onBack: () =>
                      ref.read(selectedWeekProvider.notifier).state = null,
                  onPick: () => showWeekPickerSheet(
                    context,
                    ref,
                    currentWeek: ref.read(currentWeekProvider),
                  ),
                ),
                // ⚠️ 间距 8 → 4：上移下方控件（用户反馈「下面有点拥挤」）
                const SizedBox(height: 4),
                // 日期区间 12sp
                Text(
                  _weekLabel(monday, week, courses, overrides, holidays),
                  style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
                ),
                const SizedBox(height: 6),
                // ============ 日期条 ============
                _DateStrip(
                  controller: _stripCtrl,
                  week: week,
                  monday: monday,
                  active: active,
                  todayIdx: today.weekday,
                  isCurWeek: isCurWeek,
                  courses: courses,
                  holidays: holidays,
                  overrides: overrides,
                  onSelect: (d) {
                    setState(() => _selectedDay = d);
                    final k = _anchors[d];
                    if (k?.currentContext != null) {
                      // ⚠️ 滚动定位也走统一曲线 —— 线性滚动在长列表上很"突"
                      Scrollable.ensureVisible(
                        k!.currentContext!,
                        duration: Motion.medium,
                        curve: Motion.standard,
                        alignment: 0.0,
                      );
                    }
                  },
                ),
                // ⚠️⚠️ 固定缓冲：日期条是**不滚动**的（在 headerBlock 里），
                //    而下方的 _DayList 独立滚动 —— 如果不留缓冲，
                //    卡片上滑时会**紧贴**在日期条底边（用户反馈"接触了"）。
                //    这段空白属于固定区，卡片滑到它下面就"消失"在缓冲带里。
                //    ⚠️ 10 → 15：用户要求再宽一点
                const SizedBox(height: 15),
              ],
            ),
          ),

          // ============ scrollContent（按天分组）============
          Expanded(
            child: _DayList(
              week: week,
              monday: monday,
              courses: courses,
              overrides: overrides,
              holidays: holidays,
              anchors: _anchors,
            ),
          ),
        ],
      ),
      ),
        const StatusBarBlur(height: 65),
      ],
    );
  }

  /// 学年学期标题（对应原版 tvTermName + localizeTerm()）。
  ///
  /// 优先级：学生信息里的 termTitle > meta 里的 current_term。
  /// 原版会把「2026-2027-1」本地化成「2026-2027 学年 · 第 1 学期」。
  static String _termTitle(WidgetRef ref) {
    final info = ref.watch(studentInfoProvider);
    if (info.termTitle.isNotEmpty) return info.termTitle;
    final raw = ref.watch(currentTermProvider);
    if (raw == null || raw.isEmpty) return '';
    return _localizeTerm(raw);
  }

  static const int _dash = 45;


  /// 把「2026-2027-1」本地化成「2026-2027 学年 · 第 1 学期」。
  static String _localizeTerm(String raw) {
    final m = RegExp(r"^(2026|20[0-9]{2})-(20[0-9]{2})-([0-9]+)$").firstMatch(raw);
    if (m == null) return raw;
    final year = m.group(1)! + String.fromCharCode(_dash) + m.group(2)!;
    final term = m.group(3)!;
    final label = term == "3"
        ? "第 1 学期"
        : (term == "12" ? "第 2 学期" : "第 $term 学期");
    return '$year 学年 · $label';
  }

  /// 「9/14 - 9/20 · 共 12 门课」（原版 tvWeekLabel）。
  static String _weekLabel(
    DateTime? monday,
    int week,
    List<Course> courses,
    Map<String, int> overrides,
    List<HolidayRange> holidays,
  ) {
    var n = 0;
    for (var d = 1; d <= 7; d++) {
      final date = monday?.add(Duration(days: (week - 1) * 7 + d - 1));
      if (date != null && holidays.any((h) => h.contains(date))) continue;
      final ov = date == null ? null : overrides[TimeFormats.iso(date)];
      if (ov == -1) continue;
      final eff = (ov != null && ov >= 1 && ov <= 7) ? ov : d;
      n += courses.where((c) => c.dayOfWeek == eff && c.occursInWeek(week)).length;
    }
    if (monday == null) return '第 $week 周 · 共 $n 门课';
    final s = monday.add(Duration(days: (week - 1) * 7));
    final e = s.add(const Duration(days: 6));
    return '${s.month}/${s.day} - ${e.month}/${e.day} · 共 $n 门课';
  }
}

/// ============================================================
/// 周次切换条（ADR-085 / ADR-084）
/// ============================================================
/// ┌────────────────────────┬──────────────┐
/// │ ‹        第 3 周 ›     │   回到本周    │
/// └────────────────────────┴──────────────┘
///   ↑贴左      ↑居中            ↑居中
///
/// ⚠️⚠️ 箭头位置的关键（用户多次反馈「箭头位置不对」）：
///
///   原版 ADR-084 的做法是**嵌套结构**，把三件事彻底分开：
///     [‹ 固定 40dp，贴容器左边]  ← 不参与居中，永远贴左
///     [第N周 ›] 作为**一个整体**在剩余空间居中
///
///   ❌ 错误写法 1：Row[‹, Spacer, 第N周, Spacer, ›]
///      → 箭头被 Spacer 推开，间隙随屏宽变化（横屏尤其明显）
///   ❌ 错误写法 2：Expanded{ Center{ Row[‹, 第N周, ›] } }
///      → Center 把**整组（含 ‹）**推到中间：‹ 不在最左，
///        且 › 与文字之间被拉开巨大空隙
///   ✅ 正确写法：见下 ——
///       外层 Row: [ SizedBox(48) 装 ‹ ] + [ Expanded{ Center{ [第N周 ›] } } ]
///       注意 Center 里那层 Row 必须 mainAxisSize.min，
///       否则它自己会撑满 Expanded，› 又被推到最右。
class _WeekBar extends StatelessWidget {
  /// ⚠️ 调试测量用（static：跨 rebuild 保持同一实例）。
  static final _barKey = GlobalKey();
  static final _weekGroupKey = GlobalKey();
  static final _nextBtnKey = GlobalKey();

  const _WeekBar({
    required this.week,
    required this.isCurWeek,
    required this.maxWeeks,
    required this.onPrev,
    required this.onNext,
    required this.onBack,
    required this.onPick,
  });

  final int week;
  final bool isCurWeek;
  final int maxWeeks;
  final VoidCallback onPrev;
  final VoidCallback onNext;
  final VoidCallback onBack;
  final VoidCallback onPick;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);

    return Container(
      key: _barKey,
      height: 48,
      decoration: BoxDecoration(
        color: colors.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(24),
      ),
      child: Row(
        children: [
          // ---------- 左块：［‹ 第N周 ›］整体居中（方案 B）----------
          //
          // ⚠️ 为什么是 B 而不是原版的 A：
          //   原版 ADR-084 把 ‹ 固定贴左、[第N周›] 在**剩余空间**居中，
          //   结果组中心比左块中心偏左 45.5dp（411dp 屏宽实测计算），
          //   看起来就像"箭头位置不对"。用户明确选择整体居中。
          //
          //   实现：左块用 Center 包裹一个 mainAxisSize.min 的 Row，
          //        三个元素作为一个整体居中 —— 箭头永远对称贴着文字。
          Expanded(
            child: Center(
              child: Row(
                key: _weekGroupKey,
                mainAxisSize: MainAxisSize.min,
                children: [
                  // ‹ 上一周
                  SizedBox(
                    width: 44,
                    height: 44,
                    child: IconButton(
                      padding: EdgeInsets.zero,
                      onPressed: week > 1 ? onPrev : null,
                      icon: const Icon(Icons.chevron_left_rounded),
                      color: colors.primary,
                      disabledColor:
                          colors.onSurfaceVariant.withValues(alpha: 0.38),
                      iconSize: 24,
                      tooltip: '上一周',
                    ),
                  ),
                  // 第 N 周（点击弹周次选择）
                  InkWell(
                    onTap: onPick,
                    borderRadius: BorderRadius.circular(14),
                    child: Container(
                      constraints: const BoxConstraints(minWidth: 72),
                      height: 36,
                      padding: const EdgeInsets.symmetric(horizontal: 8),
                      alignment: Alignment.center,
                      child: Text(
                        '第 $week 周',
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 14,
                          fontWeight: FontWeight.bold,
                          color: colors.primary,
                        ),
                      ),
                    ),
                  ),
                  // › 下一周
                  SizedBox(
                    key: _nextBtnKey,
                    width: 44,
                    height: 44,
                    child: IconButton(
                      padding: EdgeInsets.zero,
                      onPressed: week < maxWeeks ? onNext : null,
                      icon: const Icon(Icons.chevron_right_rounded),
                      color: colors.primary,
                      disabledColor:
                          colors.onSurfaceVariant.withValues(alpha: 0.38),
                      iconSize: 24,
                      tooltip: '下一周',
                    ),
                  ),
                ],
              ),
            ),
          ),
          // ---------- 竖分隔线 ----------
          Container(width: 1, height: 20, color: colors.outlineVariant),
          // ---------- 右块：回到本周（weight=1，居中）----------
          Expanded(
            child: TextButton(
              onPressed: isCurWeek ? null : onBack,
              style: TextButton.styleFrom(
                padding: EdgeInsets.zero,
                minimumSize: const Size(0, 48),
                shape: const RoundedRectangleBorder(),
              ),
              child: Text(
                '回到本周',
                style: TextStyle(
                  fontSize: 14,
                  fontWeight: FontWeight.bold,
                  color: isCurWeek ? colors.onSurfaceVariant : colors.primary,
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// ============================================================
/// 横向日期条（ADR-062 rev2）
/// ============================================================
///
/// ⚠️ 用户反馈「很拥挤」→ 改为可横向滑动 + 单元格**固定宽度**。
///    7 格各 52dp 恒定，不再互相挤压；窄屏放不下时可滑。
///
/// 三态着色（applyChipState）：
///   选中     → 深蓝底 + 白字（最高优先级）
///   今天未选 → 浅灰底 + 深蓝字
///   普通     → 浅灰底 + 深灰字
class _DateStrip extends StatelessWidget {
  const _DateStrip({
    required this.controller,
    required this.week,
    required this.monday,
    required this.active,
    required this.todayIdx,
    required this.isCurWeek,
    required this.courses,
    required this.holidays,
    required this.overrides,
    required this.onSelect,
  });

  final ScrollController controller;
  final int week;
  final DateTime? monday;
  final int? active;
  final int todayIdx;
  final bool isCurWeek;
  final List<Course> courses;
  final List<HolidayRange> holidays;
  final Map<String, int> overrides;
  final ValueChanged<int> onSelect;

  static const _labels = ['一', '二', '三', '四', '五', '六', '日'];

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 64,
      child: ListView.separated(
        controller: controller,
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 2),
        itemCount: 7,
        separatorBuilder: (_, _) => const SizedBox(width: 6),
        itemBuilder: (context, i) {
          final day = i + 1;
          final date = monday?.add(Duration(days: (week - 1) * 7 + i));

          // 课程数（假日当天算 0 —— ADR-060「假期就当没课」）
          final isHoliday =
              date != null && holidays.any((h) => h.contains(date));
          final count = isHoliday ? 0 : _countOf(day, date);

          return _DateChip(
            dayLabel: _labels[i],
            dayNum: date?.day.toString() ?? '-',
            count: count,
            isSelected: day == active,
            isToday: day == todayIdx && isCurWeek,
            onTap: () => onSelect(day),
          );
        },
      ),
    );
  }

  int _countOf(int day, DateTime? date) {
    final ov = date == null ? null : overrides[TimeFormats.iso(date)];
    if (ov == -1) return 0;
    final eff = (ov != null && ov >= 1 && ov <= 7) ? ov : day;
    return courses.where((c) => c.dayOfWeek == eff && c.occursInWeek(week)).length;
  }
}

/// 日期条单元格（固定 52dp × 64dp，对应 item_date_chip.xml）。
class _DateChip extends StatelessWidget {
  const _DateChip({
    required this.dayLabel,
    required this.dayNum,
    required this.count,
    required this.isSelected,
    required this.isToday,
    required this.onTap,
  });

  final String dayLabel;
  final String dayNum;
  final int count;
  final bool isSelected;
  final bool isToday;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);

    // 三态（applyChipState）
    final bg = isSelected ? c.primary : c.surfaceContainerHigh;
    final labelColor = isSelected ? c.onPrimary : c.onSurfaceVariant;
    final numColor = isSelected
        ? c.onPrimary
        : (isToday ? c.primary : c.onSurface);
    final countColor =
        isSelected ? c.onPrimary.withValues(alpha: 0.85) : c.onSurfaceVariant;

    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Container(
        width: 52,
        height: 64,
        decoration: BoxDecoration(
          color: bg,
          borderRadius: BorderRadius.circular(14),
        ),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text('周$dayLabel',
                style: TextStyle(
                    fontSize: 11, fontWeight: FontWeight.w500, color: labelColor)),
            const SizedBox(height: 1),
            Text(dayNum,
                style: TextStyle(
                    fontSize: 16, fontWeight: FontWeight.bold, color: numColor)),
            const SizedBox(height: 2),
            Text('$count门', style: TextStyle(fontSize: 9, color: countColor)),
          ],
        ),
      ),
    );
  }
}

/// ============================================================
/// 按天分组列表（ADR-062 核心）
/// ============================================================
///
/// ⚠️ 只渲染**有课的天**：7 个标题里 3 个写「0 门」是纯噪音。
///    整周没课 → 一句提示，而不是七个空标题。
///
/// ⚠️ 用 SingleChildScrollView + Column 而非 ListView：
///    ListView 懒加载会在小窗模式下回收滚出可视区的卡片，
///    导致「第 3 节课不显示 / 移动小窗又消失」。
class _DayList extends ConsumerWidget {
  const _DayList({
    required this.week,
    required this.monday,
    required this.courses,
    required this.overrides,
    required this.holidays,
    required this.anchors,
  });

  final int week;
  final DateTime? monday;
  final List<Course> courses;
  final Map<String, int> overrides;
  final List<HolidayRange> holidays;
  final Map<int, GlobalKey> anchors;

  static const _labels = ['一', '二', '三', '四', '五', '六', '日'];

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = AppColors.of(context);
    final rows = <Widget>[];
    var dayCount = 0;

    for (var day = 1; day <= 7; day++) {
      final date = monday?.add(Duration(days: (week - 1) * 7 + day - 1));

      // ADR-060：假日跳过
      if (date != null && holidays.any((h) => h.contains(date))) continue;

      // ADR-105：当日调课
      final ov = date == null ? null : overrides[TimeFormats.iso(date)];
      if (ov == -1) continue;
      final eff = (ov != null && ov >= 1 && ov <= 7) ? ov : day;

      final list = courses
          .where((x) => x.dayOfWeek == eff && x.occursInWeek(week))
          .toList()
        ..sort((a, b) => a.startPeriod.compareTo(b.startPeriod));
      if (list.isEmpty) continue;

      final extra = (ov != null && ov >= 1 && ov <= 7)
          ? '（调课：周${_labels[ov - 1]}）'
          : null;

      rows.add(_DayHeader(
        key: anchors.putIfAbsent(day, () => GlobalKey()),
        dayLabel: _labels[day - 1],
        date: date,
        count: list.length,
        extra: extra,
      ));
      for (final x in list) {
        rows.add(_WeekCourseCard(course: x));
      }
      dayCount++;
    }

    if (dayCount == 0) {
      return Center(
        child: Text('本周没有课程',
            style: TextStyle(fontSize: 14, color: c.scheduleTimeText)),
      );
    }

    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(16, 0, 16, 120), // 底部给 Dock 让位
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: rows,
      ),
    );
  }
}

/// 分组标题：「周三 16日 · 2 门」（对应 dayHeader()）。
class _DayHeader extends StatelessWidget {
  const _DayHeader({
    super.key,
    required this.dayLabel,
    required this.date,
    required this.count,
    this.extra,
  });

  final String dayLabel;
  final DateTime? date;
  final int count;
  final String? extra;

  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);
    final now = DateTime.now();
    final isToday = date != null &&
        date!.year == now.year &&
        date!.month == now.month &&
        date!.day == now.day;

    final label = StringBuffer('周$dayLabel');
    if (date != null) label.write(' ${date!.day}日');
    if (isToday) label.write(' · 今天');
    if (extra != null && extra!.isNotEmpty) label.write(' $extra');

    // 原版 dayHeader 内边距：top 14 / bottom 8
    return Padding(
      padding: const EdgeInsets.only(top: 14, bottom: 8),
      child: Row(
        children: [
          Expanded(
            child: Text(
              label.toString(),
              style: TextStyle(
                fontSize: 15, // 原版 15f
                fontWeight: FontWeight.bold,
                color: isToday ? c.primary : c.scheduleHeaderText,
              ),
            ),
          ),
          Text('$count 门',
              style: TextStyle(fontSize: 12, color: c.scheduleTimeText)),
        ],
      ),
    );
  }
}

/// ============================================================
/// 周课表课程卡片（严格对应 item_week_course.xml + courseCard()）
/// ============================================================
///
/// 原版布局：
///   ┌─┬──────────────────────────────────┐
///   │ │ tvName      17sp bold            │  ← 课程名
///   │色│ tvTime      13sp  marginTop 10   │  ← 「1-2节 1-13周」
///   │条│ tvLocation  12.5sp marginTop 6   │  ← 地点（独立一行）
///   │6 │ tvTeacher   12.5sp marginTop 4   │  ← 教师（独立一行）
///   └─┴──────────────────────────────────┘
///   padding: start 14 / end 16 / vertical 14
///   圆角 18dp（ADR-085），bottomMargin 10dp，elevation 0
///
/// ⚠️ ADR-063：颜色只上在**左侧色条**上，卡片底统一近白。
///    整块染色被用户否决过（「好丑」）。
/// ⚠️ ADR-069：ACCENT_BAR → 色条加粗到 8dp；SOLID → 隐藏色条
/// ⚠️ ADR-074：SOLID 用白字，其余用深字
class _WeekCourseCard extends ConsumerWidget {
  const _WeekCourseCard({required this.course});

  final Course course;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final c = AppColors.of(context);
    final mode =
        ref.watch(cardStyleModeProvider).valueOrNull ?? CardStyleMode.accentBar;
    final base = ref.watch(courseColorProvider)(course.name);

    final isSolid = mode == CardStyleMode.solid;
    final showBar = mode == CardStyleMode.accentBar;

    // ADR-074：文字色随样式变
    final titleColor = isSolid ? Colors.white : c.courseCellTitle;
    final metaColor = isSolid
        ? Colors.white.withValues(alpha: 0.80)
        : c.courseCellMeta;

    // 时间行：「1-2节 1-13周」
    final timeText = StringBuffer(
        PeriodTime.rangeOf(course.startPeriod, course.endPeriod));
    final wk = _weekRange(ref, course);
    if (wk.isNotEmpty) timeText.write('  $wk');

    return Padding(
      padding: const EdgeInsets.only(bottom: 10), // 原版 bottomMargin 10dp
      child: Material(
        // ADR-064：SOLID → 整块课程色；其余 → 近白底
        color: isSolid ? base : c.courseCellBg,
        borderRadius: BorderRadius.circular(18), // ADR-085：14 → 18dp
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: () => showCourseDetailDialog(context, ref, course),
          child: IntrinsicHeight(
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                // 左侧色条：6dp；ACCENT_BAR 时加粗到 8dp
                if (showBar) Container(width: 8, color: base),
                Expanded(
                  child: Padding(
                    // 原版：start 14 / end 16 / vertical 14
                    padding: const EdgeInsets.fromLTRB(14, 14, 16, 14),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        // 课程名 17sp bold
                        Text(
                          course.name,
                          style: TextStyle(
                            fontSize: 17,
                            fontWeight: FontWeight.bold,
                            color: titleColor,
                          ),
                        ),
                        if (timeText.isNotEmpty) ...[
                          const SizedBox(height: 10),
                          Text(timeText.toString(),
                              style: TextStyle(fontSize: 13, color: metaColor)),
                        ],
                        // 地点（独立一行）12.5sp
                        if (course.location.isNotEmpty) ...[
                          const SizedBox(height: 6),
                          Text(course.location,
                              style:
                                  TextStyle(fontSize: 12.5, color: metaColor)),
                        ],
                        // 教师（独立一行）12.5sp
                        if (course.teacher.isNotEmpty) ...[
                          const SizedBox(height: 4),
                          Text(course.teacher,
                              style:
                                  TextStyle(fontSize: 12.5, color: metaColor)),
                        ],
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// 周次范围标签（对应 weekRangeLabel()）：
  /// 恰好覆盖整学期 → 不写周次（避免「1-18周」这种废话）。
  static String _weekRange(WidgetRef ref, Course course) {
    final total = WeekCalculator.maxWeeks;
    final parity = switch (course.weekParity) {
      1 => '单周',
      2 => '双周',
      _ => '',
    };
    if (course.startWeek <= 1 && course.endWeek >= total && parity.isEmpty) {
      return '';
    }
    final range = course.startWeek == course.endWeek
        ? '第${course.startWeek}周'
        : '${course.startWeek}-${course.endWeek}周';
    return parity.isEmpty ? range : '$range($parity)';
  }
}
