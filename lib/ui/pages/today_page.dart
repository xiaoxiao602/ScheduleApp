import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';
import 'package:path_provider/path_provider.dart';

import '../../domain/core_logic.dart';
import '../../domain/models.dart';
import '../providers.dart';
import '../theme.dart';
import '../routes.dart';
import '../widgets/course_card.dart';
import '../widgets/status_bar_blur.dart';
import '../widgets/dialogs.dart';
import 'list_pages.dart';
import 'login_page.dart';

/// ============================================================
/// 今日页 —— 严格对应原版 TodayFragment.kt + fragment_today.xml
/// ============================================================
///
/// 区块顺序（照 XML）：
///   ① 头部：校徽 42dp + 「广软课程表」+「广州软件学院 · 个人课表」11sp + 头像 40dp
///   ② 渐变信息卡（#06459B→#1057BC→#2E74D6）：日期 13sp / 大字周次 26sp / 同步状态 12sp / 按钮
///   ③ 「今日课程」18sp + 计数
///   ④ 「最近成绩」18sp + 「全部」
///   ⑤ 「近期考试」18sp + 「全部」
///   ⑥ 更新时间 11sp
///
/// ⚠️ 滚动用 SingleChildScrollView + Column（非 ListView）——
///    ListView 懒加载会在小窗/分屏下回收卡片，导致「第 3 节课消失」。
class TodayPage extends ConsumerStatefulWidget {
  const TodayPage({super.key});

  @override
  ConsumerState<TodayPage> createState() => _TodayPageState();
}

class _TodayPageState extends ConsumerState<TodayPage> {
  DateTime _now = DateTime.now();

  /// ⚠️「分钟桶」—— 页面上所有依赖 _now 的展示值（距上课 xx分钟 /
  ///    上课中·还剩 xx分钟 / 下节课高亮）的**最小变化粒度都是分钟**。
  ///
  ///    2026-09 内存优化：原来每 20s 无条件 setState → 每 20s 整页重建 +
  ///    重栅格化 → Dock 玻璃的 backdrop 跟着重捕获。实测空闲 1 分钟
  ///    原生堆 +2MB、EGL 还多出过一整屏纹理。现在「分钟没变就跳过」，
  ///    空闲重建从 3 次/分降到 ≤1 次/分。
  int _minuteBucket = -1;

  static int _bucketOf(DateTime t) => t.hour * 60 + t.minute;

  @override
  void initState() {
    super.initState();
    _minuteBucket = _bucketOf(_now);
    _tick();
  }

  void _tick() {
    Future.doWhile(() async {
      await Future<void>.delayed(const Duration(seconds: 20));
      if (!mounted) return false;
      final now = DateTime.now();
      final bucket = _bucketOf(now);
      if (bucket != _minuteBucket) {
        _minuteBucket = bucket;
        setState(() => _now = now);
      }
      return true;
    });
  }

  @override
  Widget build(BuildContext context) {
    final courses = ref.watch(coursesProvider).valueOrNull ?? const <Course>[];
    final overrides = ref.watch(dayOverridesProvider).valueOrNull ?? const {};
    final week = ref.watch(currentWeekProvider);
    final holidays = ref.watch(holidaysProvider);
    final today = DateTime.now();

    final isHoliday = holidays.any((h) => h.contains(today));
    final todayCourses =
        isHoliday ? const <Course>[] : coursesForDate(courses, overrides, today, week);
    final nextIdx = _nextCourseIndex(todayCourses, _now);

    // ⚠️ 状态栏渐变模糊（见 settings_page 同款说明）：
    //    去掉顶部 SafeArea，内容可滚到状态栏下；顶部叠渐变模糊遮罩。
    //
    // ⚠️ 分界：模糊区 = 40dp（模糊与渐隐同一块）；内容起点 = 52dp。
    // ⚠️ 顶部留白 = 状态栏高度 + 12dp（不能写死 blurH+12，会下沉）
    final topPad = MediaQuery.of(context).padding.top;
    // ⚠️ ScrollAwareBlurWrap：只在滑动时显示顶部渐变模糊（同 settings_page）
    return Stack(
      children: [
        SafeArea(
      top: false,
      bottom: false,
      // ⚠️ 下拉刷新：触发一次教务同步。
      //    未登录 → 走登录弹层；已登录 → 直接重新同步。
      //    （原版没有这个手势，但它是 Android 列表页的通用预期。）
      child: RefreshIndicator(
        onRefresh: () => refreshFromServer(context, ref),
        child: SingleChildScrollView(
        // ⚠️ AlwaysScrollable：内容不满一屏时也要能下拉
        physics: const AlwaysScrollableScrollPhysics(),
        // ⚠️ top = 模糊区(40) + 12dp 缓冲；bottom 给 Dock 让位
        padding: EdgeInsets.only(top: topPad + 10, bottom: 120),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const _Header(),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: _HeroCard(
                week: week,
                onAction: () => showLoginSheet(context, ref),
              ),
            ),
            // ⚠️ ADR-108：点右侧计数/调课文案 → 弹「当日调课」。
            //    （之前只在"今天无课"的空态卡片里才有入口，
            //      结果有课时用户根本找不到 —— 用户反馈"调课功能不见了"。）
            _SectionTitle(
              '今日课程',
              showCount: true,
              onCountTap: () => showDayOverrideDialog(context, ref, DateTime.now()),
            ),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 16),
              child: todayCourses.isEmpty
                  ? _EmptyToday(
                      onTap: () => showDayOverrideDialog(context, ref, today))
                  : Column(
                      children: [
                        for (var i = 0; i < todayCourses.length; i++)
                          CourseCard(
                            course: todayCourses[i],
                            now: _now,
                            showCountdown: i == nextIdx,
                          ),
                      ],
                    ),
            ),
            _SectionTitle(
              '最近成绩',
              trailing: TextButton(
                onPressed: () => Navigator.of(context).push(
                  slideRoute(const GradePage()),
                ),
                child: const Text('全部', style: TextStyle(fontSize: 13)),
              ),
            ),
            const _RecentGrades(),
            _SectionTitle(
              '近期考试',
              trailing: TextButton(
                onPressed: () => Navigator.of(context).push(
                  slideRoute(const ExamPage()),
                ),
                child: const Text('全部', style: TextStyle(fontSize: 13)),
              ),
            ),
            _UpcomingExams(now: _now),
            const _UpdatedAt(),
          ],
        ),
        ),
        ),
        ),
        const StatusBarBlur(height: 65),
      ],
    );
  }

  static int _nextCourseIndex(List<Course> list, DateTime now) {
    for (var i = 0; i < list.length; i++) {
      final t = PeriodTime.startOf(list[i].startPeriod).split(':');
      final start = DateTime(now.year, now.month, now.day,
          int.tryParse(t[0]) ?? 0, int.tryParse(t.length > 1 ? t[1] : '0') ?? 0);
      if (start.isAfter(now)) return i;
    }
    return -1;
  }
}

/// ① 头部（对应 fragment_today.xml 的 ivLogo / 标题 / avatarHeader）。
///
/// ⚠️ 校徽用**真实的 ic_gzus_logo.png**（从原工程 drawable-*dpi 复制到 assets/），
///    不是 Material 图标 —— 之前用 Icons.school_rounded 是错的。
class _Header extends ConsumerWidget {
  const _Header();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final avatarPath = ref.watch(userProfileProvider).valueOrNull ?? '';
    final name = ref.watch(studentInfoProvider).name ?? '';

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 14, 16, 16),
      child: Row(
        children: [
          // 校徽 42dp
          Image.asset(
            'assets/ic_gzus_logo_xxxhdpi.png',
            width: 42,
            height: 42,
            fit: BoxFit.contain,
            errorBuilder: (_, _, _) => Icon(
              Icons.school_rounded,
              size: 42,
              color: colors.primary,
            ),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('广软课程表',
                    style: TextStyle(
                        fontSize: 22,
                        fontWeight: FontWeight.w700,
                        color: colors.onSurface)),
                Text('广州软件学院 · 个人课表',
                    style: TextStyle(
                        fontSize: 11, color: colors.onSurfaceVariant)),
              ],
            ),
          ),
          // 头像 40dp（对应 avatarHeader → AvatarView）
          _Avatar(path: avatarPath, name: name, size: 40),
        ],
      ),
    );
  }
}

/// 头像控件（对应 view_avatar.xml + AvatarView.kt）。
///
/// ⚠️ 原版机制：固定路径 `avatar/custom_avatar` 存文件 ——
///    有自定义图就显示图（centerCrop），否则显示姓氏首字（白字 18sp bold）。
///    点击 → 选图 → 存到该路径。
class _Avatar extends ConsumerWidget {
  const _Avatar({required this.path, required this.name, required this.size});

  final String path;
  final String name;
  final double size;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final hasImage = path.isNotEmpty && File(path).existsSync();

    return GestureDetector(
      onTap: () => _pickImage(context, ref),
      child: Container(
        width: size,
        height: size,
        decoration: BoxDecoration(
          color: colors.primary,
          shape: BoxShape.circle,
          image: hasImage
              ? DecorationImage(image: FileImage(File(path)), fit: BoxFit.cover)
              : null,
        ),
        alignment: Alignment.center,
        child: hasImage
            ? null
            : Text(
                name.isEmpty ? '?' : name.characters.first,
                style: const TextStyle(
                  fontSize: 18, // ⚠️ 原版 tvInitial 18sp
                  fontWeight: FontWeight.bold,
                  color: Colors.white,
                ),
              ),
      ),
    );
  }

  /// 从相册选头像（对应原版 PickVisualMedia）并持久化到应用目录。
  ///
  /// ⚠️ 选完立刻复制到固定路径 —— 否则系统可能会回收临时 URI，
  ///    下次启动图就没了（原版 AvatarView.kt 的注释提过同款问题）。
  static Future<void> _pickImage(BuildContext context, WidgetRef ref) async {
    try {
      final picker = ImagePicker();
      final x = await picker.pickImage(
        source: ImageSource.gallery,
        maxWidth: 512,
        maxHeight: 512,
      );
      if (x == null) return;
      final dir = await getApplicationDocumentsDirectory();
      final target = File('${dir.path}/custom_avatar');
      await x.saveTo(target.path);
      ref.invalidate(userProfileProvider);
      // 强制刷新 _Avatar（userProfileProvider 是 FutureProvider）
      ref.read(_avatarTickProvider.notifier).state++;
    } catch (e) {
      if (context.mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('选择头像失败：$e')));
      }
    }
  }
}

/// 头像刷新信号（选图后 bump 一下，让依赖它的 widget 重建）。
final _avatarTickProvider = StateProvider<int>((ref) => 0);

/// ② 渐变信息卡（ADR-019 + ADR-021）。
class _HeroCard extends ConsumerWidget {
  const _HeroCard({required this.week, required this.onAction});

  final int week;
  final VoidCallback onAction;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final lastSync = ref.watch(lastSyncAtProvider);
    final hasData = ref.watch(currentTermProvider) != null;

    return Container(
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFF06459B), Color(0xFF1057BC), Color(0xFF2E74D6)],
          stops: [0.0, 0.5, 1.0],
        ),
        borderRadius: BorderRadius.circular(28),
      ),
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            TimeFormats.dateWithWeekday(DateTime.now()),
            style: TextStyle(
                fontSize: 13, color: Colors.white.withValues(alpha: 0.9)),
          ),
          const SizedBox(height: 4),
          Text(
            '第 $week 周 · 今天',
            style: const TextStyle(
              fontSize: 26,
              fontWeight: FontWeight.bold,
              letterSpacing: -0.5,
              color: Colors.white,
            ),
          ),
          const SizedBox(height: 6),
          Text(
            hasData
                ? (lastSync == null
                    ? '登录后同步你的课程'
                    : '上次同步：${TimeFormats.syncStamp(lastSync)}')
                : '登录后同步你的课程',
            style: TextStyle(
                fontSize: 12, color: Colors.white.withValues(alpha: 0.8)),
          ),
          const SizedBox(height: 14),
          FilledButton.icon(
            onPressed: onAction,
            icon: const Icon(Icons.sync_rounded, size: 18),
            label: Text(hasData ? '重新同步' : '登录教务系统',
                style: const TextStyle(fontSize: 14)),
            style: FilledButton.styleFrom(
              backgroundColor: const Color(0x33FFFFFF),
              foregroundColor: Colors.white,
              shape:
                  RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
              padding:
                  const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            ),
          ),
        ],
      ),
    );
  }
}

/// 区块标题（18sp + 右侧「全部」/计数）。
class _SectionTitle extends ConsumerWidget {
  const _SectionTitle(this.text,
      {this.trailing, this.showCount = false, this.onCountTap});

  final String text;
  final Widget? trailing;
  final bool showCount;

  /// ⚠️ ADR-108：点计数文案 → 弹「当日调课」弹窗。
  ///    （原版 TodayFragment:298 明确「这块现在可点击」，用户要求。）
  final VoidCallback? onCountTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    Widget? t = trailing;
    if (t == null && showCount) {
      final courses = ref.watch(coursesProvider).valueOrNull ?? const <Course>[];
      final overrides = ref.watch(dayOverridesProvider).valueOrNull ?? const {};
      final week = ref.watch(currentWeekProvider);
      final holidays = ref.watch(holidaysProvider);
      final today = DateTime.now();
      final n = holidays.any((h) => h.contains(today))
          ? 0
          : coursesForDate(courses, overrides, today, week).length;

      // ⚠️ 有调课时显示「调课：周X」，否则显示「N 门 / 无课」——
      //    否则用户调完课看不出有没有生效（原版 TodayFragment:281 同）。
      final iso = TimeFormats.iso(today);
      final ov = overrides[iso];
      final String label;
      if (ov == null) {
        label = n == 0 ? '无课' : '$n 门';
      } else if (ov == -1) {
        label = '已调课：无课';
      } else {
        const names = ['一', '二', '三', '四', '五', '六', '日'];
        label = '调课：周${names[(ov - 1).clamp(0, 6)]}';
      }

      final chip = Padding(
        // ⚠️ 右侧多留 10dp：用户要求「3门」往左移一点（不要贴边）。
        padding: const EdgeInsets.fromLTRB(4, 0, 10, 0),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Text(
              label,
              style: TextStyle(
                // ⚠️ 用户先要求「与标题一致」，调到 18 后反馈「看起来比标题还大」，
                //    故此降一档到 16 —— 次级信息的正确定位是**略小于标题**。
                fontSize: 16,
                // 调课时用主色，一眼能看出"生效了"
                color: ov == null ? colors.onSurfaceVariant : colors.primary,
                fontWeight: ov == null ? FontWeight.normal : FontWeight.w600,
              ),
            ),
            if (onCountTap != null) ...[
              const SizedBox(width: 4),
              // ⚠️ 图标随文字一起降档（16），保持与计数等大。
              Icon(Icons.edit_calendar_outlined,
                  size: 16,
                  color: ov == null ? colors.onSurfaceVariant : colors.primary),
            ],
          ],
        ),
      );

      t = onCountTap == null
          ? chip
          : InkWell(
              onTap: onCountTap,
              borderRadius: BorderRadius.circular(8),
              child: chip,
            );
    }

    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 22, 16, 8),
      child: Row(
        children: [
          Expanded(
            child: Text(text,
                style: TextStyle(
                    fontSize: 18,
                    fontWeight: FontWeight.bold,
                    color: colors.onSurface)),
          ),
          ?t,
        ],
      ),
    );
  }
}

/// 今日无课空状态（原版 todayEmpty）。
class _EmptyToday extends StatelessWidget {
  const _EmptyToday({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 24),
      decoration: BoxDecoration(
        color: colors.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(28),
      ),
      child: Column(
        children: [
          Text('✦', style: TextStyle(fontSize: 24, color: colors.primary)),
          const SizedBox(height: 6),
          Text('尚未同步课表',
              style: TextStyle(fontSize: 14, color: colors.onSurface)),
          const SizedBox(height: 10),
          OutlinedButton(
            onPressed: onTap,
            child: const Text('调课', style: TextStyle(fontSize: 13)),
          ),
        ],
      ),
    );
  }
}

/// ④ 最近成绩（原版 gradeSection，只显示最近 3 条）。
class _RecentGrades extends ConsumerWidget {
  const _RecentGrades();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final grades = ref.watch(gradesProvider).valueOrNull ?? const <Grade>[];
    if (grades.isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Text('还没有成绩数据',
            style: TextStyle(fontSize: 13, color: colors.onSurfaceVariant)),
      );
    }
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        children: [
          for (final g in grades.take(3))
            Container(
              margin: const EdgeInsets.only(bottom: 8),
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: colors.surfaceContainerHigh,
                borderRadius: BorderRadius.circular(20),
              ),
              child: Row(
                children: [
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Text(g.courseName,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: TextStyle(
                                fontSize: 15, color: colors.onSurface)),
                        if (g.credit.isNotEmpty || g.category.isNotEmpty) ...[
                          const SizedBox(height: 2),
                          Text(
                            [
                              if (g.credit.isNotEmpty) '${g.credit} 学分',
                              if (g.category.isNotEmpty) g.category,
                            ].join(' · '),
                            style: TextStyle(
                                fontSize: 12,
                                color: colors.onSurfaceVariant),
                          ),
                        ],
                      ],
                    ),
                  ),
                  Text(g.score,
                      style: TextStyle(
                          fontSize: 19,
                          fontWeight: FontWeight.bold,
                          color: colors.primary)),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// ⑤ 近期考试（原版 examSection）。
class _UpcomingExams extends ConsumerWidget {
  const _UpcomingExams({required this.now});

  final DateTime now;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final exams = ref.watch(examsProvider).valueOrNull ?? const <Exam>[];
    final upcoming = UpcomingExamFilter.filter(exams, now);

    if (upcoming.isEmpty) {
      return Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Text('近期无考试',
            style: TextStyle(fontSize: 13, color: colors.onSurfaceVariant)),
      );
    }

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16),
      child: Column(
        children: [
          for (final e in upcoming)
            Container(
              margin: const EdgeInsets.only(bottom: 8),
              padding: const EdgeInsets.all(14),
              decoration: BoxDecoration(
                color: colors.surfaceContainerHigh,
                borderRadius: BorderRadius.circular(20),
                border: Border(
                    left: BorderSide(color: colors.tertiary, width: 5)),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(e.courseName,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style:
                          TextStyle(fontSize: 15, color: colors.onSurface)),
                  const SizedBox(height: 2),
                  Text(
                    [
                      if ((e.date ?? '').isNotEmpty) e.date!,
                      if ((e.startTime ?? '').isNotEmpty) e.startTime!,
                      if (e.location.isNotEmpty) e.location,
                      if (e.seat.isNotEmpty) '座 ${e.seat}',
                    ].join(' · '),
                    style: TextStyle(
                        fontSize: 12, color: colors.onSurfaceVariant),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

/// ⑥ 底部更新时间（原版 tvUpdatedAt，11sp）。
class _UpdatedAt extends ConsumerWidget {
  const _UpdatedAt();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final t = ref.watch(lastSyncAtProvider);
    if (t == null) return const SizedBox(height: 12);
    return Padding(
      padding: const EdgeInsets.only(top: 12, bottom: 8),
      child: Center(
        child: Text(
          '数据更新于 ${TimeFormats.syncStamp(t)}',
          style: TextStyle(fontSize: 11, color: colors.scheduleTimeText),
        ),
      ),
    );
  }
}
