import 'dart:io';
import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';
import 'package:path_provider/path_provider.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/core_logic.dart';
import '../../domain/update_checker.dart';
import '../providers.dart';
import '../theme.dart';
import '../routes.dart';
import 'card_appearance_page.dart';
import 'dock_tuning_page.dart';
import 'haptic_page.dart';
import 'list_pages.dart';
import 'login_page.dart';
import 'notify_page.dart';
import 'notify_test_page.dart';
import '../widgets/dialogs.dart';
import '../widgets/status_bar_blur.dart';

/// ============================================================
/// 设置页（对应原版 ui/settings/SettingsFragment.kt + fragment_settings.xml）
/// ============================================================
///
/// 布局（与原版一致）：
///   成绩查询                >
///   考试安排                >
///   ── 学期 ──
///   第一周星期一   2026-09-14 (第1周) >
///   ── 外观 ──
///   课程外观                >
///   Dock 外观调节           >
///   触感反馈                >
///   ── 数据管理 ──
///   清除本地数据            >
///   诊断信息                >
///   ── 关于 ──
///   检查更新                >
///
///   广软课程表
///   XIAOXIAO
///   仅供个人使用 · 永久免费
///
/// ⚠️ 所有设置行用统一样式；图标 tint 统一 onSurfaceVariant（灰）。
///    原版踩坑：用错 tint（colorOnPrimary=白）→ 图标在浅色背景上看不见。
class SettingsPage extends ConsumerWidget {
  const SettingsPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final meta = ref.watch(metaAllProvider).valueOrNull ?? const {};
    final fm = ref.watch(firstMondayProvider);
    final week = ref.watch(currentWeekProvider);

    // ⚠️⚠️ 状态栏渐变模糊（用户要求，iOS / HyperOS 4 风格）：
    //    · 去掉顶部 SafeArea 内边距 → 内容可以"滚到状态栏下面"
    //    · 顶部叠一层渐变模糊遮罩 → 掠过状态栏的内容被糊开、渐隐，
    //      而不是被硬边截断（原来的生硬观感就来自这条硬边）
    //    · 列表首项手动补 top padding，保证初始位置不被状态栏压住
    //
    // ⚠️ 分界（用户最终明确）：
    //    · 遮罩高度 = **70dp**（小米风格渐变蒙层）
    //    · 内容起点 = 70dp + 12dp = 82dp
    //      → 与遮罩下沿留 12dp 缓冲，标题不会被蒙住
    // ⚠️⚠️ 修 bug：顶部留白必须用【状态栏高度 + 12dp】，
    //    之前写死 `blurH + 12` = 82dp → 所有元素下沉（用户报 bug）。
    //    同时恢复 SafeArea（左右/底部安全区），只是不许它处理顶部
    //    —— 顶部留给遮罩和 topPad。
    final topPad = MediaQuery.of(context).padding.top;
    return Stack(
      children: [
        SafeArea(
          top: false,
          bottom: false,
          child: ListView(
          // ⚠️ 顶部留白 = topPad + 26
          //    （= 今日页的 12[外层] + 14[_Header 内] —— 用户要求三页标题对齐）
          padding: EdgeInsets.fromLTRB(16, topPad + 24, 16, 120),
          children: [
          Text(
            '设置',
            style: TextStyle(
              fontSize: 26,
              fontWeight: FontWeight.w700,
              color: colors.onSurface,
            ),
          ),
          const SizedBox(height: 16),

          // ---- 个人信息卡片（对应原版 fragment_settings.xml 的 avatarProfile）----
          //
          // ⚠️ 用户反馈「设置界面没有头像 ID 等个人信息设置卡片，参考 1.0.3」。
          //
          // 原版规格（ADR-012 / ADR-045）：
          //   MaterialCardView  marginH 16 / marginTop 8 / 圆角 20dp
          //                     elevation 1dp / strokeWidth 1dp
          //   └ LinearLayout padding 16dp, gravity center_vertical
          //       ├ AvatarView  56dp
          //       ├ 名字        17sp bold（marginStart 16dp）
          //       ├ 学号·专业   12sp alpha 0.6（marginTop 3dp, maxLines 2）
          //       └ 右箭头      20dp（marginStart 8dp）
          //
          // ADR-045 的设计意图：「颜色和下方背景融为一体，建议加轻微阴影或
          // 纯白背景，使其与页面浅灰背景区分开，增强层次感」——
          // 所以要 **纯白卡 + 1dp 描边 + 1dp elevation**，不能用纯色块。
          //
          // ⚠️ 整卡可点（ADR-046：头像本体不再单独可点），点击进资料编辑。
          // ⚠️ 原来这里是两张卡：「个人信息」+「账号 / 重新同步」，
          //    两处都在显示同样的姓名与学号，中间还夹一个「账号」标签，很割裂。
          //    用户要求合并 → 个人信息卡内部加一行「重新同步教务」，
          //    整卡仍然可点进资料编辑，同步是卡内的独立点击区。
          _ProfileCard(
            onSync: () => showLoginSheet(context, ref),
          ),
          const SizedBox(height: 18),

          // ---- 成绩 / 考试 ----
          _Section(children: [
            _Row(
              icon: Icons.assessment_outlined,
              title: '成绩查询',
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => _openGrades(context),
            ),
            const Divider(height: 1),
            _Row(
              icon: Icons.event_note_outlined,
              title: '考试安排',
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => _openExams(context),
            ),
          ]),
          const SizedBox(height: 18),

          // ---- 学期 ----
          _SectionLabel('学期'),
          _Section(children: [
            _Row(
              icon: Icons.calendar_month_outlined,
              title: '第一周星期一',
              subtitle: fm == null
                  ? '未设置（点此设置）'
                  : '${TimeFormats.iso(fm)}（第 $week 周）',
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => _openFirstWeekSetup(context, ref),
            ),
          ]),
          const SizedBox(height: 18),

          // ---- 外观 ----
          _SectionLabel('外观'),
          _Section(children: [
            _Row(
              icon: Icons.palette_outlined,
              title: '课程外观',
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => _openCardAppearance(context),
            ),
            const Divider(height: 1),
            _Row(
              icon: Icons.view_agenda_outlined,
              title: 'Dock 外观调节',
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => _openDockTuning(context),
            ),
            const Divider(height: 1),
            _Row(
              icon: Icons.vibration_outlined,
              title: '触感反馈',
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => _openHaptic(context),
              ),
              ]),
              const SizedBox(height: 18),

              // ---- 通知（1.2.1 方案 E）----
              _SectionLabel('通知'),
              _Section(children: [
              _Row(
                icon: Icons.notifications_active_outlined,
                title: '上课提醒',
                subtitle: '课前铃声 · 常驻倒计时 · 已下课',
                trailing: const Icon(Icons.chevron_right_rounded, size: 20),
                onTap: () => _openNotify(context),
              ),
              const Divider(height: 1),
              _Row(
                icon: Icons.science_outlined,
                title: '推送测试',
                subtitle: '逐类触发通知试效果',
                trailing: const Icon(Icons.chevron_right_rounded, size: 20),
                onTap: () => _openNotifyTest(context),
              ),
              ]),
              const SizedBox(height: 18),

              // ---- 数据管理 ----
          _SectionLabel('数据管理'),
          _Section(children: [
            _Row(
              icon: Icons.delete_outline_rounded,
              title: '清除本地数据',
              subtitle: '不影响教务系统上的数据',
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => _confirmClearAll(context, ref),
            ),
            const Divider(height: 1),
            _Row(
              icon: Icons.settings_suggest_outlined,
              title: '诊断信息',
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => _openDiagnostics(context, ref, meta),
            ),
          ]),
          const SizedBox(height: 18),

          // ---- 关于 ----
          _SectionLabel('关于'),
          _Section(children: [
            _Row(
              icon: Icons.refresh_rounded,
              title: '检查更新',
              trailing: const Icon(Icons.chevron_right_rounded, size: 20),
              onTap: () => _checkUpdate(context, ref),
            ),
          ]),
          const SizedBox(height: 28),

          // ---- 署名 ----
          Center(
            child: Column(
              children: [
                Text(
                  '广软课程表',
                  style: TextStyle(
                    fontSize: 14,
                    fontWeight: FontWeight.w600,
                    color: colors.onSurface,
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  'XIAOXIAO',
                  style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
                ),
                const SizedBox(height: 2),
                Text(
                  '仅供个人使用 · 永久免费',
                  style: TextStyle(fontSize: 11, color: colors.onSurfaceVariant),
                ),
              ],
            ),
          ),
          ],
          ),
        ),
        const StatusBarBlur(height: 65),
      ],
    );
  }

  // ---------- 二级页 / 弹窗挂载点（后续文件实现）----------

  void _openGrades(BuildContext context) {
    Navigator.of(context).push(
      slideRoute(const GradePage()),
    );
  }

  void _openExams(BuildContext context) {
    Navigator.of(context).push(
      slideRoute(const ExamPage()),
    );
  }

  void _openFirstWeekSetup(BuildContext context, WidgetRef ref) {
    showFirstWeekSetupDialog(context, ref, allowSkip: true);
  }

  void _openCardAppearance(BuildContext context) {
    Navigator.of(context).push(
      slideRoute(const CardAppearancePage()),
    );
  }

  void _openDockTuning(BuildContext context) {
    Navigator.of(context).push(
      slideRoute(const DockTuningPage()),
    );
  }

  void _openNotify(BuildContext context) {
    Navigator.of(context).push(
      slideRoute(const NotifyPage()),
    );
  }

  void _openNotifyTest(BuildContext context) {
    Navigator.of(context).push(
      slideRoute(const NotifyTestPage()),
    );
  }

  void _openHaptic(BuildContext context) {
    Navigator.of(context).push(
      slideRoute(const HapticPage()),
    );
  }

  void _openDiagnostics(
      BuildContext context, WidgetRef ref, Map<String, String> meta) {
    final colors = AppColors.of(context);
    showDialog<void>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('诊断信息'),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              for (final e in meta.entries)
                Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Text(
                    '${e.key}: ${e.value.length > 80 ? '${e.value.substring(0, 80)}…' : e.value}',
                    style: const TextStyle(fontSize: 12),
                  ),
                ),
              if (meta.isEmpty)
                Text('（无数据）',
                    style: TextStyle(
                        fontSize: 12, color: colors.onSurfaceVariant)),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(),
            child: const Text('关闭'),
          ),
        ],
      ),
    );
  }

  void _confirmClearAll(BuildContext context, WidgetRef ref) {
    showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('清除本地数据'),
        content: const Text(
          '将删除本机上的课表、成绩、考试与设置。\n'
          '教务系统上的数据不受影响，之后可重新同步。',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(ctx).pop(),
            child: const Text('取消'),
          ),
          FilledButton(
            onPressed: () async {
              Navigator.of(ctx).pop();
              await _clearAll(ref);
              if (context.mounted) {
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('已清除本地数据')),
                );
              }
            },
            child: const Text('清除'),
          ),
        ],
      ),
    );
  }

  Future<void> _clearAll(WidgetRef ref) async {
    await ref.read(courseRepoProvider).clear();
    await ref.read(examRepoProvider).clear();
    await ref.read(gradeRepoProvider).clear();
    await ref.read(metaRepoProvider).clear();
    // 保留 Dock / 触感等外观偏好（与原版一致：那是"界面偏好"不是"数据"）
    ref.invalidate(metaAllProvider);
  }

  /// 检查更新（对应原版 UpdateChecker.kt）。
  ///
  /// 流程：GitHub Releases API → 比对 tag_name 与本地 versionName →
  ///       有新版则弹对话框（含更新说明 + 「去下载」）。
  ///
  /// ⚠️ 不静默下载安装（系统不允许），只打开 Release 页面让用户自己下 ——
  ///    与原版行为一致。
  Future<void> _checkUpdate(BuildContext context, WidgetRef ref) async {
    try {
      // ⚠️ 版本号从 package_info_plus 读（与 build.gradle 的 versionName 一致），
      //    不要硬编码 —— 否则改版本时会忘。
      final pkg = await PackageInfo.fromPlatform();
      final info = await UpdateChecker.check(pkg.version);
      if (!context.mounted) return;

      if (!info.hasUpdate) {
        // ⚠️ 不要用 SnackBar —— Flutter 默认的 SnackBar 是深色圆角条，
        //    在浅色主题下像"底下一坨黑的"，且与项目其它弹窗风格不一致。
        //    统一走 showMessageDialog。
        if (!context.mounted) return;
        await showMessageDialog(
          context,
          title: '检查更新',
          message: '已是最新版本（${info.currentVersion}）',
        );
        return;
      }

      // 有新版 → 走项目统一弹窗（showMessageDialog），
      // 不要自己手写 AlertDialog —— 那样圆角/字号/按钮样式会和
      // 「第一周设置」「课程详情」等弹窗不一致。
      await showMessageDialog(
        context,
        title: '发现新版本 ${info.latestVersion}',
        message: '', // 用 contentOverride 取代
        contentOverride: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(
                '当前版本：${info.currentVersion}',
                style: const TextStyle(fontSize: 13, height: 1.35),
              ),
              if ((info.notes ?? '').trim().isNotEmpty) ...[
                const SizedBox(height: 10),
                Text(
                  info.notes!.trim(),
                  style: const TextStyle(fontSize: 13, height: 1.35),
                ),
              ],
            ],
          ),
        ),
        secondaryText: '稍后',
        okText: '去下载',
        onOk: () {
          final url = info.downloadUrl ?? info.releaseUrl;
          if (url != null) {
            launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
          }
        },
      );
    } catch (e) {
      if (!context.mounted) return;
      await showMessageDialog(
        context,
        title: '检查更新失败',
        message: '$e',
      );
    }
  }

  /// ⚠️ 2026-09 清理：`_todo`（占位 SnackBar）无任何调用方，已删除。
}

// ============================================================ 通用行/分组

/// 分组标题（"学期"/"外观"/"数据管理"…）。
class _SectionLabel extends StatelessWidget {
  const _SectionLabel(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return Padding(
      padding: const EdgeInsets.only(left: 4, bottom: 8),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: colors.onSurfaceVariant,
          letterSpacing: 0.5,
        ),
      ),
    );
  }
}

/// 设置行分组容器（圆角卡片）。
class _Section extends StatelessWidget {
  const _Section({required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return Container(
      decoration: BoxDecoration(
        color: colors.surfaceContainerHigh,
        borderRadius: BorderRadius.circular(AppTheme.settingsCardRadius),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(children: children),
    );
  }
}

/// 统一样式的设置行。
///
/// ⚠️ 图标 tint 统一用 onSurfaceVariant（灰）—— 原版踩过用错 tint 导致看不见。
class _Row extends StatelessWidget {
  const _Row({
    required this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
  });

  final IconData icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return ListTile(
      onTap: onTap,
      leading: Icon(icon, size: 24, color: colors.primary),
      title: Text(
        title,
        style: TextStyle(fontSize: 15, color: colors.onSurface),
      ),
      subtitle: subtitle == null
          ? null
          : Text(
              subtitle!,
              style: TextStyle(fontSize: 12, color: colors.onSurfaceVariant),
            ),
      trailing: trailing,
      contentPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 8),
      minLeadingWidth: 22,
    );
  }
}


/// ============================================================
/// 设置页顶部的个人信息卡片（对应 fragment_settings.xml 的 avatarProfile）
/// ============================================================
///
/// 用户反馈：「设置界面没有头像 ID 等个人信息设置卡片，参考 1.0.3」。
///
/// ⚠️ 原版 ADR-045 明确要求「纯白卡 + 轻阴影」——
///    优化建议原话：「颜色和下方背景融为一体，建议加轻微阴影或纯白背景，
///    使其与页面浅灰背景区分开，增强层次感」。
///    所以这里用 `surfaceContainerLowest`（浅色=白）+ 1dp 描边 + 1dp 阴影。
///
/// ⚠️ 整卡可点（ADR-046）—— 头像本体不再单独可点，点击整卡进资料编辑。
class _ProfileCard extends ConsumerStatefulWidget {
  const _ProfileCard({required this.onSync});

  /// 卡内「重新同步教务」那一行的回调。
  final VoidCallback onSync;

  @override
  ConsumerState<_ProfileCard> createState() => _ProfileCardState();
}

class _ProfileCardState extends ConsumerState<_ProfileCard> {
  String? _avatarPath;

  @override
  void initState() {
    super.initState();
    _loadAvatar();
  }

  /// 读取自定义头像文件（对应原版 UserProfileStore.avatarFile()）。
  ///
  /// ⚠️ 每次 build 都读一次磁盘太浪费，所以在 initState 读一次 + 编辑弹窗
  ///    关闭后重新读（回到本页时 _ProfileDialog 已写盘）。
  Future<void> _loadAvatar() async {
    try {
      final dir = await getApplicationDocumentsDirectory();
      final f = File('${dir.path}/custom_avatar');
      if (!mounted) return;
      setState(() => _avatarPath = f.existsSync() ? f.path : null);
    } catch (_) {}
  }

  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);
    final info = ref.watch(studentInfoProvider);

    // 名字：优先自定义显示名（原版 tvDisplayName）
    final name = info.displayName ?? info.name ?? info.shownName;
    // 学号 · 专业（原版 tvStudentMeta，最多两行）
    final meta = [
      if ((info.studentNo ?? '').isNotEmpty) info.studentNo!,
      if ((info.major ?? '').isNotEmpty) info.major!,
    ].join(' · ');

    return Container(
      margin: const EdgeInsets.only(top: 8),
      decoration: BoxDecoration(
        // ⚠️ 纯白/近白卡，与浅灰页面背景分层（ADR-045）。
        //    原版用 @color/card_surface（比页面背景更白）——
        //    Flutter 侧用 surfaceContainer（浅色下即近白，深色下为略亮的灰）。
        color: c.surfaceContainerHigh, // 统一白色
        borderRadius: BorderRadius.circular(20), // card_corner_medium
        border: Border.all(color: c.outlineVariant, width: 1), // strokeWidth 1dp
        boxShadow: [
          // elevation 1dp（很轻，只为分层，不是装饰）
          BoxShadow(
            color: Colors.black.withValues(alpha: 0.05),
            blurRadius: 2,
            offset: const Offset(0, 1),
          ),
        ],
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        // ⚠️⚠️ 必须给 Material 也设圆角（用户反馈「点卡片出现灰色矩形遮罩」）！
        //
        //    原理：InkWell 的水波纹是在**最近的 Material 图层**上绘制的；
        //    外层 Container 的 clipBehavior 只裁剪"已绘制内容"，**不裁剪墨水层**。
        //    所以 Material 没圆角时，波纹就是直角矩形 —— 与卡片圆角不匹配。
        //    （此坑不报错、不影响功能，只在点击时可见，极易漏。）
        borderRadius: BorderRadius.circular(20), // 与 Container 的 20 一致
        // ⚠️ 卡片分两段（用户要求把「个人信息」与「重新同步」合并成一张卡）：
        //    上段 = 头像 + 姓名 + 学号，点击 → 资料编辑
        //    下段 = 「重新同步教务」一行，点击 → 登录/同步
        //    两段用 1dp 分隔线隔开，整卡视觉上是一张。
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            InkWell(
              // 上段：进资料编辑
              onTap: () async {
                await showProfileDialog(context, ref);
                await _loadAvatar(); // 回来自动刷新头像
              },
              child: Padding(
                padding: const EdgeInsets.all(16), // 原版 padding 16dp
                child: Row(
              children: [
                // ---------- 头像 56dp（原版 avatarProfileImage）----------
                Container(
                  width: 56,
                  height: 56,
                  decoration: BoxDecoration(
                    color: c.primary,
                    shape: BoxShape.circle,
                    image: _avatarPath != null
                        ? DecorationImage(
                            image: FileImage(File(_avatarPath!)),
                            fit: BoxFit.cover,
                          )
                        : null,
                  ),
                  alignment: Alignment.center,
                  child: _avatarPath != null
                      ? null
                      : Text(
                          name.isEmpty ? '?' : name.characters.first,
                          style: const TextStyle(
                            fontSize: 22,
                            fontWeight: FontWeight.bold,
                            color: Colors.white,
                          ),
                        ),
                ),
                const SizedBox(width: 16), // 原版 layout_marginStart 16dp
                // ---------- 名字 + 学号·专业 ----------
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: TextStyle(
                          fontSize: 17, // 原版 tvDisplayName 17sp bold
                          fontWeight: FontWeight.bold,
                          color: c.onSurface,
                        ),
                      ),
                      if (meta.isNotEmpty) ...[
                        const SizedBox(height: 3), // 原版 marginTop 3dp
                        Text(
                          meta,
                          maxLines: 2,
                          overflow: TextOverflow.ellipsis,
                          style: TextStyle(
                            fontSize: 12, // 原版 12sp
                            color: c.onSurfaceVariant.withValues(alpha: 0.6),
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: 8), // 原版 marginStart 8dp
                // ---------- 右箭头 20dp ----------
                Icon(
                  Icons.chevron_right_rounded,
                  size: 20,
                  color: c.onSurfaceVariant,
                ),
              ],
            ),
                ),
              ),

            // ---------- 分隔线 ----------
            Divider(height: 1, thickness: 1, color: c.outlineVariant),

            // ---------- 下段：重新同步教务 ----------
            InkWell(
              onTap: widget.onSync,
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
                child: Row(
                  children: [
                    Icon(Icons.sync_rounded, size: 22, color: c.primary),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Text(
                        '重新同步教务',
                        style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.w600,
                          color: c.onSurface,
                        ),
                      ),
                    ),
                    Icon(
                      Icons.chevron_right_rounded,
                      size: 20,
                      color: c.onSurfaceVariant,
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
