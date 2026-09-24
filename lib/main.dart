import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

import 'ui/pages/login_page.dart';
import 'ui/pages/schedule_page.dart';
import 'ui/pages/settings_page.dart';
import 'data/notify/notify_service.dart';
import 'domain/haptic_engine.dart';
import 'ui/widgets/edge_haptic.dart';
import 'ui/motion.dart';
import 'ui/pages/today_page.dart';
import 'ui/providers.dart';
import 'ui/theme.dart';
import 'ui/widget/liquid_glass_dock.dart';

/// 广软课程表 · Flutter 版
///
/// 原 Android 版（Kotlin）的一比一移植。
/// 数据全在本地（drift），断网可看；同步只是往库里写。
Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await LiquidGlassWidgets.initialize(); // 预热液态玻璃 shader

  // 1.2.1 推送/提醒（方案 E）：初始化渠道与回调，启动即排程。
  await NotifyService.instance.init();
  NotifyService.instance.reschedule();

  runApp(
    LiquidGlassWidgets.wrap(
      child: const ProviderScope(child: GzuScheduleApp()),
      // ⚠️⚠️ 不要开 adaptiveQuality！
      //
      //  实测排查发现（2026-09）：adaptiveQuality 安装的 GlassAdaptiveScope
      //  会先把画质种到 standard，再跑 3s 基准测试，**只有通过才提升 premium**。
      //  而官方质量优先级规则写明：自适应上限会裁掉一切，**包括 widget 上
      //  显式写的 `quality: premium`**（"premium means premium if the device
      //  can handle it — GlassAdaptiveScope is the arbiter"）。
      //
      //  我们的 Dock 显式要求 premium 的 3D shader，但被这个上限截回
      //  standard → 走轻量 2D shader → 只剩毛玻璃+高光，没有折射/色散。
      //  关掉它，premium 才能真正生效。
    ),
  );
}

class GzuScheduleApp extends StatelessWidget {
  const GzuScheduleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return EdgeHapticScope(
      child: MaterialApp(
        title: '广软课程表',
        debugShowCheckedModeBanner: false,
        theme: AppTheme.light(),
        darkTheme: AppTheme.dark(),
        themeMode: ThemeMode.system, // 跟随系统（原版行为）
        home: const AppRoot(),
      ),
    );
  }
}

/// 根路由：进主界面；未同步时**弹一次**登录引导。
///
/// ⚠️ 与原版一致的行为：本地库是唯一数据源。
///    未同步（无 current_term）-> 主界面 + 登录引导弹层（可关闭）。
///
/// ⚠️ 必须是有状态组件 —— 弹窗只能弹**一次**。
///    之前写成 ConsumerWidget 并在 build() 里注册 addPostFrameCallback，
///    导致每次 rebuild（切深色模式、任何 provider 变化）都再注册一次，
///    弹窗层层叠加。现在用 _loginPrompted 标志位保证幂等。
class AppRoot extends ConsumerStatefulWidget {
  const AppRoot({super.key});

  @override
  ConsumerState<AppRoot> createState() => _AppRootState();
}

class _AppRootState extends ConsumerState<AppRoot> {
  /// 本次进程内是否已弹过登录引导（幂等保护）。
  ///
  /// ⚠️ 用普通字段而非 State：主题切换不会重建 State，
  ///    所以这个标志能跨 rebuild 保持 —— 正是我们要的。
  bool _loginPrompted = false;

  @override
  Widget build(BuildContext context) {
    final term = ref.watch(currentTermProvider);
    final metaAsync = ref.watch(metaAllProvider);

    if (metaAsync.isLoading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }

    final hasSynced = term != null && term.isNotEmpty;
    if (!hasSynced && !_loginPrompted) {
      _loginPrompted = true; // 先置位，再注册 —— 防重入
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) showLoginSheet(context, ref);
      });
    }

    return const MainShell();
  }
}

/// 三页容器 + 底部 Dock。
///
/// ⚠️ 与原版的关键差异（刻意为之）：
///   原版用自绘 DockBarView（620 行 Canvas）+ show/hide 切 Fragment，
///   代价是每页必须实现 onHiddenChanged 才能刷新（反复踩的坑）。
///   Flutter 版改用标准 NavigationBar + IndexedStack：
///     · 图标/文字/涟漪/无障碍由框架保证（不会"漏画文字"）
///     · IndexedStack 保活各页状态，且**不需要 onHiddenChanged**
///   液态玻璃/物理形变暂不做 —— 先保证功能一比一。
class MainShell extends ConsumerStatefulWidget {
  const MainShell({super.key});

  @override
  ConsumerState<MainShell> createState() => _MainShellState();
}

class _MainShellState extends ConsumerState<MainShell> {
  int _tab = 0;

  /// 切页控制器 —— 方向由**索引差**天然决定，无需额外标志位。
  final _pageController = PageController();

  /// 三页实例（常量化，避免每次 build 重新 new）。
  ///
  /// ⚠️ 每页套一层 RepaintBoundary（2026-09 性能优化）：
  ///    切页滑动时页面**内容本身没变**、只有 paint offset 在动 ——
  ///    有独立图层后 GPU 直接平移已有光栅，不必每帧 CPU 重画整页。
  ///    （RepaintBoundary 构造函数是 const 的，_pages 保持 const。）
  static const _pages = <Widget>[
    RepaintBoundary(child: TodayPage()),
    RepaintBoundary(child: SchedulePage()),
    RepaintBoundary(child: SettingsPage()),
  ];

  @override
  void dispose() {
    _pageController.dispose();
    super.dispose();
  }

  /// 构建 Dock（**液态玻璃 + 实时模糊**，用户明确要求）。
  ///
  /// ⚠️ 玻璃本体的参数（白度/模糊/折射厚度/色散）来自 DockTuningStore
  ///    的「液态玻璃」分区，可在「Dock 外观调节」页实时调；
  ///    其余光学参数是 [kDockGlassSettings] 的定稿值。
  Widget _buildDock() {
    final t = ref.watch(dockTuningProvider).valueOrNull;

    final style = t == null
        ? const LiquidGlassDockStyle()
        : LiquidGlassDockStyle(
            height: t.dockHeight.toDouble(),
            radius: t.dockCorner.toDouble(),
            // ⚠️ dockWidth 语义 = 「Dock 距屏幕左右边缘的距离」（dp）。
            //    0 表示"贴边"（但为美观给 12dp 最小边距，否则圆角会顶到屏幕边）。
            //    用户在「Dock 宽度」滑块上的值就是边距本身，直接使用。
            marginHorizontal:
                t.dockWidth <= 0 ? 12 : t.dockWidth.toDouble(),
            marginBottom: t.dockBottomOffset.toDouble(),
            sliderHeight: t.sliderHeight.toDouble(),
            sliderRadius: t.sliderCorner.toDouble(),
            sliderWidthPadding: t.sliderExtraWidth.toDouble(),
            sliderGap: t.sliderGap.toDouble(),
            fontSize: t.textSizeSp,
            animationDuration: Duration(milliseconds: t.animDurationMs),
            springDamping: t.dampingFactor,
            glassOpacity: t.glassOpacity,
          );

    // ⚠️ 可调玻璃参数 → LiquidGlassSettings（主界面与预览共用生成逻辑）
    final glass = t == null
        ? kDockGlassSettings
        : dockGlassSettings(
            blur: t.blurSigma.toDouble(),
            white: t.glassWhite,
            thickness: t.glassThickness.toDouble(),
            chroma: t.glassChroma,
          );

    // ⚠️⚠️ 自绘层不再画"玻璃底"（关键修复，2026-09）：
    //
    //   之前这里给 painter 传 glassOpacity ≈ 0.5 —— 它会画一层 **50% 不透明的
    //   白膜（深色模式=黑膜）压在库的 GlassCard 玻璃之上**，把 3D 折射、
    //   高光全洗掉一半；深色模式下那层黑膜更是让 Dock"看起来像被挡住了"。
    //
    //   现在置 0：玻璃本体完全交给库的 GlassCard 渲染，自绘层只负责
    //   滑块 + 文字 + 拖动形变。
    final adjustedStyle = style.copyWith(
      glassOpacity: 0.0, // 不再压膜（参考上方说明）
    );

    // ⚠️ 结构：Stack[ 库 GlassCard 玻璃层 + 自绘滑块/文字层 ]
    //
    //   ⚠️ 不要用 ClipRRect 包 GlassCard！
    //   GlassCard 的 LiquidShape 自带裁剪；外层再套 ClipRRect 会多一层
    //   saveLayer，破坏 Impeller 的 backdrop 捕获（库源码/issue #99 同类问题）。
    //
    //   ⚠️ 玻璃面 = 共享组件 GlassDockSurface（与「Dock 外观调节」页预览同款）。
    return SizedBox(
      height: style.height + style.marginBottom,
      child: Stack(
        children: [
          Positioned(
            left: adjustedStyle.marginHorizontal,
            right: adjustedStyle.marginHorizontal,
            bottom: adjustedStyle.marginBottom,
            height: adjustedStyle.height,
            child: GlassDockSurface(style: adjustedStyle, settings: glass),
          ),
          LiquidGlassDock(
            selectedIndex: _tab,
            onSelected: (i) {
              setState(() => _tab = i);
              _pageController.animateToPage(
                i,
                duration: Motion.long,
                curve: Motion.standard,
              );
              _haptic();
            },
            items: const [
              DockItem(label: '今天'),
              DockItem(label: '周课表'),
              DockItem(label: '设置'),
            ],
            style: adjustedStyle,
          ),
        ],
      ),
    );
  }

  /// 切页触感（受「触感反馈」设置控制）。
  ///
  /// ⚠️ +13 修复：之前写死 selectionClick() —— 风格设置在主页完全无效
  ///    （用户反馈「切换之后回到主页还是原来的效果」）。
  ///    现在走 HapticEngine（风格 → 原生 HapticFeedbackConstants）。
  void _haptic() {
    final cfg = ref.read(hapticConfigProvider).valueOrNull;
    if (cfg == null || !cfg.enabled || !cfg.onTab) return;
    HapticEngine.play(style: cfg.style, strength: cfg.strength);
  }

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);

    return Scaffold(
      backgroundColor: colors.surface,
      // ⚠️⚠️ Dock 是**叠层悬浮**，不占布局空间（与 1.0.3 一致）。
      //
      //   之前用 bottomNavigationBar: _buildDock() ——
      //   Scaffold 会为它**预留等高空间**，导致 Dock 下方多出一块空白，
      //   看起来像「底下有一层同高的遮罩」。
      //
      //   改成 Stack 叠层后：
      //     · 内容区延伸到屏幕底部（Dock 浮在内容之上）
      //     · 没有预留空白 → 无遮罩
      //     · 各页自己的 paddingBottom(120) 负责让内容不被 Dock 挡住
      body: Stack(
        children: [
          // ---------- 三页内容（PageView 滑动切换）----------
          //
          // ⚠️ 用户要求「dock 切换界面时加动画，滑块往哪边滑界面就往哪边滑」。
          //
          //   实现：`PageView` + `PageController.animateToPage`。
          //     · 唯一的方向真相 = 两个 tab 的**索引差**
          //       （不需额外的 _slideFromRight 标志，PageView 天然按索引方向滚）
          //     · 两页**同时**平移（一进一出），像翻页而不是叠影
          //     · 支持手势横滑（额外好处）
          //     · 子页**被保活**（PageView 默认 keepsAlive 需要在子页用
          //       AutomaticKeepAliveClientMixin；这里不强制，
          //       因为三页都很快，重新 build 无感）
          //
          //   ❌ 之前用 AnimatedSwitcher + SlideTransition + FadeTransition：
          //      两页**同时**渲染在 Stack 里叠着平移，还叠了透明度 →
          //      观感是"糊成一团、忽明忽暗"，用户明确反馈"不好看"。
          //      页面切换不该有淡入淡出，只该有位移。
          PageView(
            controller: _pageController,
            // ⚠️ 由 Dock 驱动切换；禁用用户横滑，避免与课程列表的横滑冲突
            physics: const NeverScrollableScrollPhysics(),
            children: _pages,
          ),
          // Dock 浮在最上层
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: _buildDock(),
          ),
        ],
      ),
    );
  }
}
