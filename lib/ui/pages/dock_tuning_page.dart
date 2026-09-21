import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/local/settings_stores.dart' show DockTuning;
import '../providers.dart';
import '../theme.dart';
import '../widget/liquid_glass_dock.dart';

/// ============================================================
/// Dock 外观调节页（对应原版 fragment_dock_tuning.xml）
/// ============================================================
///
/// ⚠️⚠️ 本页经过一次彻底重构，请勿再退回旧写法。
///
/// 【重构原因】
///   旧实现同时存在 **4 层状态**，互相覆盖：
///     ① _Slider 内部的 _drag / _fingerValue（拖动影子值）
///     ② 本页的 _t（本地）
///     ③ dockTuningProvider（AsyncNotifier）
///     ④ SharedPreferences（磁盘）
///   于是「拖动 → 松手 → 值弹回」反复出现，改一处坏一处：
///     · onChangeEnd 里先 cancel 防抖再清影子值 → 松手瞬间回退
///     · 新增字段忘了在 Store 的 load/save 里同步 → 读回默认值
///     · AsyncNotifier.apply 先设 state 后写盘 → 竞态覆盖
///
/// 【现在的架构：单层真相】
///
///   DockTuningPage (ConsumerStatefulWidget)
///     └ _t : DockTuning        ← **唯一真相**（内存）
///
///   拖动 → setState(_t = 新值)              ← 同步，立即生效
///        → notifier.setLocal(新值)          ← 同步，通知主界面
///        → Timer(300ms) 写 SharedPreferences ← 仅持久化
///
///   滑块是**完全受控组件**（Stateless），
///   value 直接来自 _t，自身不存任何状态 →
///   **物理上不存在"影子值回退"的可能**。
///
/// 【纪律】
///   · 绝不在 _Slider 里加 setState / 影子值
///   · 绝不在 _update 里 await 写盘后再改 UI
///   · 新增参数必须同步改 Store 的 load / save / resetAll 三处
class DockTuningPage extends ConsumerStatefulWidget {
  const DockTuningPage({super.key});

  @override
  ConsumerState<DockTuningPage> createState() => _DockTuningPageState();
}

class _DockTuningPageState extends ConsumerState<DockTuningPage> {
  /// 唯一真相。null = 尚未从磁盘载入。
  DockTuning? _t;

  /// 落库防抖（只作用于"写磁盘"这一步）。
  Timer? _saveTimer;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _saveTimer?.cancel();
    // ⚠️ 退出前补一次落库 —— 用户可能drag完立刻返回，
    //    此时防抖定时器还没触发。
    final t = _t;
    if (t != null) {
      ref.read(dockTuningStoreProvider).save(t);
    }
    super.dispose();
  }

  Future<void> _load() async {
    final t = await ref.read(dockTuningStoreProvider).load();
    if (!mounted) return;
    setState(() => _t = t);
  }

  /// 唯一的状态写入口。
  ///
  /// ⚠️ 三步里 ① ② 全是**同步、零 IO**，因此不存在"回退窗口"；
  ///    只有 ③ 写磁盘是异步的，它慢/失败都**不影响界面**。
  void _update(DockTuning v) {
    setState(() => _t = v);                            // ① 本页立即生效
    ref.read(dockTuningProvider.notifier).setLocal(v);  // ② 同步主界面

    _saveTimer?.cancel();                               // ③ 仅落库防抖
    _saveTimer = Timer(const Duration(milliseconds: 300), () {
      ref.read(dockTuningStoreProvider).save(v);
    });
  }

  Future<void> _reset() async {
    await ref.read(dockTuningStoreProvider).resetAll();
    if (!mounted) return;
    const fresh = DockTuning();
    setState(() => _t = fresh);
    ref.read(dockTuningProvider.notifier).setLocal(fresh);
  }

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    final t = _t;

    return Scaffold(
      backgroundColor: colors.surface,
      appBar: AppBar(
        title: const Text('Dock 外观调节'),
        actions: [
          TextButton(onPressed: _reset, child: const Text('恢复默认')),
        ],
      ),
      body: t == null
          ? const Center(child: CircularProgressIndicator())
          : Column(
              children: [
                _DockPreview(t: t),
                Expanded(
                  child: ListView(
                    padding: const EdgeInsets.fromLTRB(20, 0, 20, 40),
                    children: [
                      // ============ 一、Dock 本体 ============
                      _Label('Dock 本体'),
                      _Slider(
                        title: '距屏幕底部',
                        suffix: '%vdp',
                        value: t.dockBottomOffset.toDouble(),
                        min: DockTuning.MIN_BOTTOM_OFFSET.toDouble(),
                        max: DockTuning.MAX_BOTTOM_OFFSET.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(dockBottomOffset: v.round())),
                      ),
                      _Slider(
                        title: 'Dock 宽度',
                        // ⚠️ 明确语义：这是"距屏幕左右边缘的距离"，
                        //    数值越大 Dock 越窄（用户反馈过"调节无效"，
                        //    其实是在大范围里看不出变化）。
                        suffix: '%vdp（距屏幕左右；越大越窄）',
                        value: t.dockWidth.toDouble(),
                        min: DockTuning.MIN_DOCK_WIDTH.toDouble(),
                        max: DockTuning.MAX_DOCK_WIDTH.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(dockWidth: v.round())),
                      ),
                      _Slider(
                        title: 'Dock 圆角',
                        suffix: '%vdp',
                        value: t.dockCorner.toDouble(),
                        min: DockTuning.MIN_CORNER.toDouble(),
                        max: DockTuning.MAX_CORNER.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(dockCorner: v.round())),
                      ),
                      _Slider(
                        title: 'Dock 厚度',
                        suffix: '%vdp',
                        value: t.dockHeight.toDouble(),
                        min: DockTuning.MIN_HEIGHT.toDouble(),
                        max: DockTuning.MAX_HEIGHT.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(dockHeight: v.round())),
                      ),
                      const SizedBox(height: 18),

                      // ============ 二、液态玻璃 ============
                      //  ⚠️ 这四个滑块直接驱动 GlassCard 的 premium 3D shader
                      //     （经 dockGlassSettings → kDockGlassSettings.copyWith）。
                      //     预览区实时可见；主界面同样读取。
                      _Label('液态玻璃'),
                      _Slider(
                        title: '白度',
                        suffix: '%v%（越大越乳白，0 = 全通透）',
                        value: t.glassWhitePct.toDouble(),
                        min: DockTuning.MIN_GLASS_WHITE.toDouble(),
                        max: DockTuning.MAX_GLASS_WHITE.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(glassWhitePct: v.round())),
                      ),
                      _Slider(
                        title: '模糊',
                        suffix: '%v（磨砂程度）',
                        value: t.blurSigma.toDouble(),
                        min: DockTuning.MIN_BLUR_SIGMA.toDouble(),
                        max: DockTuning.MAX_BLUR_SIGMA.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(blurSigma: v.round())),
                      ),
                      _Slider(
                        title: '折射厚度',
                        suffix: '%v（越大背后的东西越扭曲）',
                        value: t.glassThickness.toDouble(),
                        min: DockTuning.MIN_GLASS_THICKNESS.toDouble(),
                        max: DockTuning.MAX_GLASS_THICKNESS.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(glassThickness: v.round())),
                      ),
                      _Slider(
                        title: '色散',
                        suffix: '%v（×0.1，边缘彩虹色边）',
                        value: t.glassChromaTenths.toDouble(),
                        min: DockTuning.MIN_GLASS_CHROMA.toDouble(),
                        max: DockTuning.MAX_GLASS_CHROMA.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(glassChromaTenths: v.round())),
                      ),
                      const SizedBox(height: 18),

                      // ============ 三、滑动滑块 ============
                      _Label('滑动滑块'),
                      _Slider(
                        title: '滑块高度',
                        suffix: '%vdp',
                        value: t.sliderHeight.toDouble(),
                        min: DockTuning.MIN_SLIDER_H.toDouble(),
                        max: DockTuning.MAX_SLIDER_H.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(sliderHeight: v.round())),
                      ),
                      _Slider(
                        title: '滑块圆角',
                        suffix: '%vdp',
                        value: t.sliderCorner.toDouble(),
                        min: DockTuning.MIN_SLIDER_CORNER.toDouble(),
                        max: DockTuning.MAX_SLIDER_CORNER.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(sliderCorner: v.round())),
                      ),
                      _Slider(
                        title: '滑块宽度增量',
                        suffix: '%vdp',
                        value: t.sliderExtraWidth.toDouble(),
                        min: DockTuning.MIN_SLIDER_W.toDouble(),
                        max: DockTuning.MAX_SLIDER_W.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(sliderExtraWidth: v.round())),
                      ),
                      _Slider(
                        title: '滑块间隙',
                        suffix: '%vdp（左右各留一半；越大滑块越窄）',
                        value: t.sliderGap.toDouble(),
                        min: DockTuning.MIN_SLIDER_GAP.toDouble(),
                        max: DockTuning.MAX_SLIDER_GAP.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(sliderGap: v.round())),
                      ),
                      const SizedBox(height: 18),

                      // ============ 四、文字与动效 ============
                      _Label('文字与动效'),
                      _Slider(
                        title: '文字大小',
                        suffix: '%v（sp × 10）',
                        value: t.textSizeTenths.toDouble(),
                        min: DockTuning.MIN_TEXT.toDouble(),
                        max: DockTuning.MAX_TEXT.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(textSizeTenths: v.round())),
                      ),
                      _Slider(
                        title: '滑动时长',
                        suffix: '%vms',
                        value: t.animDurationMs.toDouble(),
                        min: DockTuning.MIN_ANIM_MS.toDouble(),
                        max: DockTuning.MAX_ANIM_MS.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(animDurationMs: v.round())),
                      ),
                      _Slider(
                        title: '阻尼回弹',
                        suffix: '%v（×100）',
                        value: t.dampingOvershoot.toDouble(),
                        min: DockTuning.MIN_DAMPING.toDouble(),
                        max: DockTuning.MAX_DAMPING.toDouble(),
                        onChanged: (v) =>
                            _update(t.copyWith(dampingOvershoot: v.round())),
                      ),
                    ],
                  ),
                ),
              ],
            ),
    );
  }
}

/// 分组标题。
class _Label extends StatelessWidget {
  const _Label(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 14, bottom: 2),
      child: Text(text,
          style: TextStyle(fontSize: 12, color: c.onSurfaceVariant)),
    );
  }
}

/// 完全受控滑块 —— **不得有任何本地状态**。
///
/// ⚠️⚠️ 这是本页重构的核心纪律：
///   `value` 完全来自上层 `_t`；`onChanged` 同步回调给上层。
///   上层 setState 后本组件重建 → 显示值**永远等于真相**。
///
///   旧实现曾在内部存 `_drag` / `_fingerValue` 作为"手指影子"，
///   并在 onChangeEnd 里 `setState(影子 = null)` —— 那一帧显示值
///   会回退到旧的 `widget.value`，表现为「松手就弹回」。
///   **不要再加回来。**
class _Slider extends StatelessWidget {
  const _Slider({
    required this.title,
    required this.suffix,
    required this.value,
    required this.min,
    required this.max,
    required this.onChanged,
  });

  final String title;

  /// 后缀模板：`%v` 会被当前值替换。
  final String suffix;
  final double value;
  final double min;
  final double max;
  final ValueChanged<double> onChanged;

  String get _label => suffix.replaceAll('%v', value.round().toString());

  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);
    final v = value.clamp(min, max);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text('$title · $_label',
              style: TextStyle(fontSize: 12, color: c.onSurface)),
        ),
        Slider(
          value: v,
          min: min,
          max: max,
          // ⚠️ 不设 divisions：会量化成台阶，且影响 onChangeEnd 派发。
          onChanged: onChanged,
        ),
      ],
    );
  }
}

/// Dock 实时预览。
///
/// ⚠️ 直接复用主界面同款 LiquidGlassDock，参数来自当前 `t`，
///    所以调任意滑块预览立刻变。
class _DockPreview extends StatelessWidget {
  const _DockPreview({required this.t});

  final DockTuning t;

  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);

    final previewBottom = (t.dockBottomOffset.toDouble() * 0.6).clamp(8.0, 60.0);

    // ⚠️⚠️ 修「Dock 宽度调节无效」（用户反馈）：
    //
    //   预览区原本把 `marginHorizontal` **硬编码成 12**，
    //   完全不读 `t.dockWidth` —— 所以在预览里怎么拖「Dock 宽度」
    //   都看不出变化，用户自然认为"无效"。
    //   （主界面读的是 t.dockWidth，其实是生效的，只是预览没同步。）
    //
    //   这里改为跟随 t.dockWidth，并按预览区宽度做**等比缩放**：
    //   预览区比真机窄，直接套用 60dp 边距会把 Dock 挤到看不见。
    final previewMargin = (t.dockWidth <= 0 ? 12.0 : t.dockWidth.toDouble()) * 0.5;

    final style = LiquidGlassDockStyle(
      height: t.dockHeight.toDouble(),
      radius: t.dockCorner.toDouble(),
      marginHorizontal: previewMargin.clamp(4.0, 60.0),
      marginBottom: previewBottom,
      sliderHeight: t.sliderHeight.toDouble(),
      sliderRadius: t.sliderCorner.toDouble(),
      sliderWidthPadding: t.sliderExtraWidth.toDouble(),
      sliderGap: t.sliderGap.toDouble(),
      fontSize: t.textSizeSp,
      animationDuration: Duration(milliseconds: t.animDurationMs),
      springDamping: t.dampingFactor,
      // ⚠️ 与主界面一致：自绘层不压膜（玻璃完全交给 GlassDockSurface）。
      glassOpacity: 0.0,
    );

    // ⚠️ 可调玻璃参数（与主界面共用同一生成逻辑，调滑块预览实时变）
    final glass = dockGlassSettings(
      blur: t.blurSigma.toDouble(),
      white: t.glassWhite,
      thickness: t.glassThickness.toDouble(),
      chroma: t.glassChroma,
    );

    // ⚠️⚠️ 为什么外层固定高度（用户反馈「直接拖不动」）：
    //    预览区原本高度自适应 → 调大 dockHeight 后它越来越高，
    //    把下面的滑块 ListView 挤到几乎不可点 → 「拖不动」。
    //    这里改为**固定高度**，预览内部用 FittedBox/Clip 自行适配。
    return SizedBox(
      height: 150,
      child: Container(
      margin: const EdgeInsets.fromLTRB(16, 12, 16, 8),
      decoration: BoxDecoration(
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: c.outlineVariant),
      ),
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 6),
            child: Text('实时预览',
                style: TextStyle(
                    fontSize: 11, color: c.onSurfaceVariant)),
          ),
          // ⚠️ 内部用 Expanded 撑满外层固定高度，避免越调越高
          Expanded(
            child: Stack(
              children: [
                const Positioned.fill(child: _PreviewBackdrop()),
                // ⚠️ 玻璃面与主界面**同款共享组件** —— 预览不再自己用
                //    旧的 BlurredDock（普通模糊），两边观感永远一致。
                Positioned(
                  left: style.marginHorizontal,
                  right: style.marginHorizontal,
                  bottom: style.marginBottom,
                  height: style.height,
                  child: GlassDockSurface(style: style, settings: glass),
                ),
                LiquidGlassDock(
                  selectedIndex: 0,
                  onSelected: (_) {},
                  items: const [
                    DockItem(label: '今天'),
                    DockItem(label: '周课表'),
                    DockItem(label: '设置'),
                  ],
                  style: style,
                ),
              ],
            ),
          ),
        ],
      ),
      ),
    );
  }
}

/// 预览背景 —— 保留高频细节（斜条纹），让模糊/折射变化肉眼可见。
///
/// ⚠️ 背景必须是**高频细节**：模糊纯色 = 还是纯色，看不出效果差异。
/// ⚠️ 用户要求去掉多余装饰（大标题文字 / 色块）—— 已删，只留渐变 + 斜条纹。
class _PreviewBackdrop extends StatelessWidget {
  const _PreviewBackdrop();

  @override
  Widget build(BuildContext context) {
    final c = AppColors.of(context);
    return Container(
      decoration: BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            c.primary.withValues(alpha: 0.28),
            c.surfaceContainerHigh,
            c.tertiary.withValues(alpha: 0.26),
          ],
        ),
      ),
      child: CustomPaint(
        painter: _StripePainter(c.onSurfaceVariant),
        child: const SizedBox.expand(),
      ),
    );
  }
}

/// 45° 斜条纹 —— 给模糊层提供高频细节。
class _StripePainter extends CustomPainter {
  _StripePainter(this.color);
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color.withValues(alpha: 0.10)
      ..strokeWidth = 6;
    const gap = 16.0;
    for (var x = -size.height; x < size.width; x += gap) {
      canvas.drawLine(
          Offset(x, size.height), Offset(x + size.height, 0), paint);
    }
  }

  @override
  bool shouldRepaint(covariant _StripePainter old) => old.color != color;
}
