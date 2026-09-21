import 'package:flutter/material.dart';
import 'package:flutter/physics.dart';
import 'package:liquid_glass_widgets/liquid_glass_widgets.dart';

/// ============================================================
/// 液态玻璃 / 实时模糊 / 物理形变 Dock
/// ============================================================
///
/// ⚠️ 玻璃本体 = 库 `liquid_glass_widgets` 的 GlassCard（premium 3D shader）；
///    本文件的自绘层只负责**滑块 + 文字 + 拖动形变**。
///
/// 排查记录（2026-09）—— 之前"只有毛玻璃+高光、没有折射"的三个坑：
///   ① `wrap(adaptiveQuality: true)` 会把显式 premium 封顶回 standard
///      （库的自适应上限是最终裁决者），必须关闭；
///   ② `thickness` 单位是逻辑像素，默认 20、README 推荐 30~40——
///      写 1.0 就是薄 30 倍，折射位移≈0，肉眼无折射；
///   ③ 自绘层**不能压半透明膜**（会洗掉折射/高光），也不要 ClipRRect
///      包 GlassCard（多一层 saveLayer 破坏 backdrop 捕获）。
class LiquidGlassDock extends StatefulWidget {
  final int itemCount;
  final int selectedIndex;
  final ValueChanged<int> onSelected;
  final LiquidGlassDockStyle style;

  /// 各项的图标与标签。
  ///
  /// ⚠️ 有 items 时以 items 为准（itemCount 由它推导）；
  ///    为空则退化为「只有滑块、不画图标文字」的旧行为。
  final List<DockItem> items;

  const LiquidGlassDock({
    super.key,
    this.itemCount = 0,
    required this.selectedIndex,
    required this.onSelected,
    this.items = const [],
    this.style = const LiquidGlassDockStyle(),
  });

  @override
  State<LiquidGlassDock> createState() => _LiquidGlassDockState();
}

/// Dock 里的一项（图标 + 标签）。
///
/// ⚠️ 1.0.3 原版的 Dock 是「图标 + 文字 + 药丸滑块」三件套，
///    之前的实现只画了滑块 —— 这里把缺的图标与文字补上。
class DockItem {
  /// ⚠️ icon 现在是**可选**的 —— 用户要求「Dock 不要图标只留文字」。
  ///    传 null（或不传）就只渲染文字，与原版 1.0.3 的纯文字 Dock 一致。
  const DockItem({required this.label, this.icon});

  /// 图标（可选；为 null 时只画文字）。
  final IconData? icon;

  /// 标签（如「今天」「课表」「设置」）。
  final String label;
}

/// Dock 外观参数（对应原版 DockTuningStore 的可调项）。
class LiquidGlassDockStyle {
  final double height;
  final double radius;
  final double marginHorizontal;
  final double marginBottom;
  final double sliderHeight;
  final double sliderRadius;
  final double sliderWidthPadding;

  /// 滑块左右间隙（dp，**总间隙**，左右各一半）。
  ///
  /// ⚠️ 用户要求「滑块左右两边留有一点间隙」。
  ///    实现方式：滑块宽度 = 槽宽 − sliderGap，
  ///    位置仍以**槽位中心**对齐 → 与文字同心，永不错位。
  final double sliderGap;

  final double fontSize;

  /// ⚠️ 玻璃不透明度（自绘层压膜强度 0~1）。
  ///
  /// ⚠️ 主界面/预览都**传 0**（不压膜，玻璃完全交给 GlassDockSurface）。
  ///    保留字段：painter 内部仍按它绘制底色填充。
  final double glassOpacity;
  final Duration animationDuration;
  final double springDamping;

  const LiquidGlassDockStyle({
    this.height = 58,
    this.radius = 28,
    // ⚠️ 左右边距：1.0.3 的 dockWidth=0 表示「自适应，左右各 16dp」。
    //    之前这里写 45 —— Dock 被挤得又窄又小，与背景也贴不紧。
    this.marginHorizontal = 16,
    this.marginBottom = 30,
    this.sliderHeight = 48,
    this.sliderRadius = 30,
    // 滑块比槽位多出的宽度（原版 DEF_SLIDER_W = 0）。
    // ⚠️ 保持 0：滑块宽度 == 槽位宽度时，滑块中心与文字中心严格重合，
    //    两端也天然贴边对称。调大后滑块会向相邻槽位溢出。
    this.sliderWidthPadding = 0,
    this.sliderGap = 6,
    this.fontSize = 12,
    this.glassOpacity = 0.75,
    this.animationDuration = const Duration(milliseconds: 480),
    this.springDamping = 1.30,
  });

  LiquidGlassDockStyle copyWith({
    double? height,
    double? radius,
    double? marginHorizontal,
    double? marginBottom,
    double? sliderHeight,
    double? sliderRadius,
    double? sliderWidthPadding,
    double? sliderGap,
    double? fontSize,
    double? glassOpacity,
    Duration? animationDuration,
    double? springDamping,
  }) {
    return LiquidGlassDockStyle(
      height: height ?? this.height,
      radius: radius ?? this.radius,
      marginHorizontal: marginHorizontal ?? this.marginHorizontal,
      marginBottom: marginBottom ?? this.marginBottom,
      sliderHeight: sliderHeight ?? this.sliderHeight,
      sliderRadius: sliderRadius ?? this.sliderRadius,
      sliderWidthPadding: sliderWidthPadding ?? this.sliderWidthPadding,
    sliderGap: sliderGap ?? this.sliderGap,
      fontSize: fontSize ?? this.fontSize,
      glassOpacity: glassOpacity ?? this.glassOpacity,
      animationDuration: animationDuration ?? this.animationDuration,
      springDamping: springDamping ?? this.springDamping,
    );
  }
}

class _LiquidGlassDockState extends State<LiquidGlassDock>
    with SingleTickerProviderStateMixin {
  /// 滑块位置控制器（0.0 = 第 1 项，1.0 = 最后一项，连续的槽位坐标）。
  late AnimationController _controller;

  /// 形变参数用 ValueNotifier 传给 painter，**不走 setState**。
  ///
  /// ⚠️ 性能关键：`setState` 会触发整棵树 `build()`（含 BackdropFilter，
  ///    全屏卷积），拖动时每帧一次 = 掉帧。painter 通过 `repaint:` 监听
  ///    这些 notifier，只重绘 paint()。
  final _stretch = ValueNotifier<double>(0);
  final _dragDir = ValueNotifier<double>(1);


  int get _n => widget.items.isNotEmpty
      ? widget.items.length
      : (widget.itemCount > 0 ? widget.itemCount : 1);
  LiquidGlassDockStyle get s => widget.style;

  @override
  void initState() {
    super.initState();
    _controller = AnimationController.unbounded(vsync: this);
    _controller.value = widget.selectedIndex.toDouble();
    // ⚠️ 这里**不再** addListener(setState) ——
    //    painter 已通过 `repaint:` 监听 _controller，
    //    动画每帧只触发 paint()，不触发 build()。
    //    （旧代码的 setState 是「不丝滑」的主因）
  }

  @override
  void didUpdateWidget(covariant LiquidGlassDock oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 外部改变选中项 -> 用弹簧动画滑过去
    if (widget.selectedIndex != oldWidget.selectedIndex) {
      _springTo(widget.selectedIndex.toDouble());
    }
  }

  @override
  void dispose() {
    _controller.dispose();
    _stretch.dispose();
    _dragDir.dispose();
    super.dispose();
  }

  /// 用弹簧物理滑到目标槽位。
  ///
  /// ⚠️ 原 Android 版是自研 `SpringInterpolator`（自定义插值器）。
  ///    Flutter 内置物理引擎 `SpringSimulation` 更精确，
  ///    且能真实模拟阻尼（dampingRatio）与刚度（stiffness）。
  void _springTo(double target) {
    final from = _controller.value;
    final sim = SpringSimulation(
      SpringDescription(
        mass: 1.0,
        // ⚠️ 刚度**固定**，只用 dampingRatio 表达"回弹感"。
        //    旧代码把 stiffness 和 damping **同比例**放大 ——
        //    阻尼比虽不变，但**固有频率升高** → 动画更快更"硬"，
        //    观感就是用户说的「不丝滑」。
        //    现在固定刚度、只调阻尼：1.0 = 临界阻尼（不过冲），
        //    <1 回弹，>1 更黏。
        stiffness: 320.0,
        damping: 30.0 * s.springDamping,
      ),
      from,
      target,
      0.0, // 初速度
    );
    _controller.animateWith(sim);
  }

  /// 拖动中的实时跟随 + 形变。
  void _onDragUpdate(double dx, double slotWidth, double totalWidth) {
    final delta = dx / slotWidth;
    var v = _controller.value + delta;
    var stretchValue = 0.0;

    // ⚠️ 边界对称：与原版 coerceIn(minSliderX, maxSliderX) 等价
    final clamped = v.clamp(0.0, (_n - 1).toDouble());

    // 超出边界时产生"橡皮筋"阻力（越拉越难拉）
    if (v < 0 || v > (_n - 1)) {
      final overshoot = (v - clamped).abs();
      final resisted = clamped + (v - clamped) * (1.0 / (1.0 + overshoot * 3));
      v = resisted;
      // 边缘拉伸
      stretchValue = overshoot.clamp(0.0, 0.35);
    } else {
      // 中间拖动也有一点形变，模拟液态黏滞
      stretchValue = (delta.abs() * 3).clamp(0.0, 0.18);
    }

    if (delta != 0) _dragDir.value = delta > 0 ? 1.0 : -1.0;
    // ⚠️ 只改 notifier —— repaint 会自动触发，**不需要 setState**
    //    （旧代码这里有 setState(() {})，导致每帧重建整棵树 = 卡顿）
    _stretch.value = stretchValue;
    _controller.value = v;
  }

  /// 松手：吸附到最近槽位，形变回弹。
  void _onDragEnd(double velocity, double slotWidth) {
    final target = _controller.value.roundToDouble().clamp(0.0, (_n - 1).toDouble());

    // 甩动：按速度惯性多滑一格（"液态被甩出去"）
    var finalTarget = target;
    if (velocity.abs() > 500) {
      final extra = velocity > 0 ? 1.0 : -1.0;
      finalTarget = (target + extra).clamp(0.0, (_n - 1).toDouble());
    }

    _stretch.value = 0.0;
    _springTo(finalTarget);

    final idx = finalTarget.round();
    if (idx != widget.selectedIndex) widget.onSelected(idx);
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isDark = theme.brightness == Brightness.dark;

    return Positioned(
      left: s.marginHorizontal,
      right: s.marginHorizontal,
      bottom: s.marginBottom,
      child: LayoutBuilder(
        builder: (context, constraints) {
          final totalWidth = constraints.maxWidth;
          final slotWidth = totalWidth / _n;

          return GestureDetector(
            behavior: HitTestBehavior.opaque,
            onHorizontalDragUpdate: (d) =>
                _onDragUpdate(d.delta.dx, slotWidth, totalWidth),
            onHorizontalDragEnd: (d) =>
                _onDragEnd(d.velocity.pixelsPerSecond.dx, slotWidth),
            onTapDown: (d) {
              final idx =
                  (d.localPosition.dx / slotWidth).floor().clamp(0, _n - 1);
              _springTo(idx.toDouble());
              if (idx != widget.selectedIndex) widget.onSelected(idx);
            },
            child: RepaintBoundary(
              // ⚠️ 2026-09 性能优化：自绘层（滑块/文字/形变）在拖动和
              //    吸附动画期间**每帧重画**。没有这层边界时，重画会向上
              //    传播到整个 Dock 子树 —— 连带把库的 premium 玻璃也卷进
              //    每帧重渲染。加了边界后：滑块归滑块重画，玻璃归玻璃
              //    （只有它背后的内容真的变化时才更新）。
              child: CustomPaint(
                painter: _LiquidGlassDockPainter(
                  controller: _controller,
                  itemCount: _n,
                  selectedIndex: widget.selectedIndex,
                  items: widget.items,
                  style: s,
                  stretchN: _stretch,
                  dragDirN: _dragDir,
                  isDark: isDark,
                  colorScheme: theme.colorScheme,
                ),
                child: SizedBox(
                  height: s.height,
                  width: totalWidth,
                ),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// ============================================================
/// 自绘：液态玻璃 Dock
/// ============================================================
///
/// 绘制层次（从下到上）—— 这是"液态玻璃"观感的关键：
///   1. 背景模糊层（BackdropFilter 由外部提供，这里画半透明底）
///   2. 玻璃主体：圆角矩形 + 半透明填充
///   3. 顶部高光：线性渐变白，模拟玻璃受光
///   4. 边缘折射：内描边，亮色在上、暗色在下
///   5. 内发光：向内扩散的柔和光晕
///   6. 滑块：药丸形，带弹性形变（拉伸/挤压）
///   7. 图层图标与文字
class _LiquidGlassDockPainter extends CustomPainter {
  final Animation<double> controller;
  final int itemCount;
  final int selectedIndex;
  final List<DockItem> items;
  final LiquidGlassDockStyle style;
  final ValueNotifier<double> stretchN;
  final ValueNotifier<double> dragDirN;
  final bool isDark;
  final ColorScheme colorScheme;

  _LiquidGlassDockPainter({
    required this.controller,
    required this.itemCount,
    required this.selectedIndex,
    required this.items,
    required this.style,
    required this.stretchN,
    required this.dragDirN,
    required this.isDark,
    required this.colorScheme,
  }) : super(
          // ⚠️ 同时监听三个源，任一变化都只触发 paint()（不触发 build）
          repaint: Listenable.merge([controller, stretchN, dragDirN]),
        );

  @override
  void paint(Canvas canvas, Size size) {
    final s = style;
    final radius = Radius.circular(s.radius);
    final dockRect = RRect.fromRectAndRadius(
      Rect.fromLTWH(0, 0, size.width, size.height),
      radius,
    );

    // ---------- 2. 玻璃主体（简洁版，无渐变）----------
    final baseColor = isDark
        ? const Color(0xFF1C1B1F).withValues(alpha: s.glassOpacity)
        : Colors.white.withValues(alpha: s.glassOpacity);
    final bodyPaint = Paint()
      ..color = baseColor
      ..style = PaintingStyle.fill;
    canvas.drawRRect(dockRect, bodyPaint);

    // ---------- 3. 滑块 ----------
    //
    // 定位严格照原版 DockBarView.targetX：
    //   slotW      = width / n
    //   滑块宽      = slotW + sliderWidthPadding - sliderGap
    //   槽位中心    = (value + 0.5) * slotW
    //   左边缘      = 槽位中心 - 滑块宽 / 2   （与文字同一坐标系）
    // ⚠️ 不引入额外 inset —— 那样会让滑块与文字错位（用户报过"两端不对称"）。
    final slotWidth = size.width / itemCount;
    final slotCenter = (controller.value + 0.5) * slotWidth;

    // 形变：拖动时轻微拉长（最多 +9%），松手归零
    final stretch = stretchN.value;
    final stretchFactor = 1.0 + stretch * 0.5;
    final squeezeFactor = 1.0 - stretch * 0.5;

    // 滑块宽度 = 槽宽 - 间隙 + 宽度增量（不允许超过槽宽，否则会溢出到相邻格）
    final baseW =
        (slotWidth - s.sliderGap + s.sliderWidthPadding).clamp(8.0, slotWidth);
    final sliderW = baseW * stretchFactor;
    final sliderH = s.sliderHeight * squeezeFactor;
    final halfW = sliderW / 2;

    final left = (slotCenter - halfW).clamp(
      // 只有滑块比槽位宽（padding > 0）时才可能触到这两个边界；
      // 此时宁可贴边，也不与文字错位。
      0.0,
      (size.width - sliderW).clamp(0.0, double.infinity),
    );

    final sliderRect = RRect.fromRectAndRadius(
      Rect.fromLTWH(left, (size.height - sliderH) / 2, sliderW, sliderH),
      Radius.circular(s.sliderRadius),
    );

    // 滑块本体：主色渐变（液态玻璃的"染色玻璃"效果）
    final sliderGradient = LinearGradient(
      begin: Alignment.topLeft,
      end: Alignment.bottomRight,
      colors: [
        colorScheme.primary.withValues(alpha: 0.95),
        Color.lerp(colorScheme.primary, colorScheme.tertiary, 0.35)!
            .withValues(alpha: 0.90),
      ],
    );
    canvas.drawRRect(
      sliderRect,
      Paint()..shader = sliderGradient.createShader(sliderRect.outerRect),
    );

    // 滑块高光（顶部亮边，玻璃质感）
    canvas.drawRRect(
      sliderRect,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.0
        ..color = Colors.white.withValues(alpha: 0.35),
    );

    // ---------- 7. 图标与文字 ----------
    //
    // ⚠️ 这一层是 1.0.3 原版 Dock 的关键：每项=图标+文字，
    //    选中项的图标/文字用高对比色（压在药丸滑块上），
    //    未选中项用弱化色。之前只画滑块、不画这一层，
    //    所以用户看到的是「没有文字的空 Dock」。
    if (items.isNotEmpty) {
      for (var i = 0; i < items.length && i < itemCount; i++) {
        final cx = (i + 0.5) * slotWidth;

        // 「选中程度」：滑块中心离该项多近（1=完全重合）
        final dist = (controller.value - i).abs();
        final sel = (1.0 - dist).clamp(0.0, 1.0);

        // 选中时文字/图标压在彩色药丸上 -> 用 onPrimary；
        // 未选中时压在玻璃底上 -> 用 onSurfaceVariant。
        final iconColor = Color.lerp(
          colorScheme.onSurfaceVariant,
          colorScheme.onPrimary,
          sel,
        )!;

        // ⚠️ 图标是**可选**的（用户要求「Dock 不要图标只留文字」）。
        //    有图标 -> 图标在上、文字在下（两行布局）；
        //    无图标 -> 文字**垂直居中**（纯文字 Dock，观感更干净）。
        final hasIcon = items[i].icon != null;
        final icon = items[i].icon;

        if (hasIcon) {
          final iconSize = s.fontSize + 10;
          final iconY = size.height / 2 - s.fontSize * 0.62;
          _drawIcon(canvas, icon!, Offset(cx, iconY), iconSize, iconColor);
        }

        // 文字
        final tp = TextPainter(
          text: TextSpan(
            text: items[i].label,
            style: TextStyle(
              fontSize: s.fontSize,
              height: 1.0,
              fontWeight: sel > 0.5 ? FontWeight.w600 : FontWeight.w500,
              color: iconColor,
              // 加阴影让文字在玻璃折射背景下更清晰
              shadows: [
                Shadow(
                  color: Colors.black.withValues(alpha: 0.15),
                  blurRadius: 3,
                  offset: const Offset(0, 1),
                ),
              ],
            ),
          ),
          textDirection: TextDirection.ltr,
        )..layout(maxWidth: slotWidth);

        // 无图标时垂直居中，有图标时下移给图标让位
        final textY = hasIcon
            ? size.height / 2 + s.fontSize * 0.18
            : (size.height - tp.height) / 2;
        tp.paint(canvas, Offset(cx - tp.width / 2, textY));
      }
    }
  }

  /// 用 canvas 直接画 Material 图标（无需 Widget 层）。
  ///
  /// 原理：把 IconData 的 codePoint 用 MaterialIcons 字体渲染成一段「文字」。
  void _drawIcon(
    Canvas canvas,
    IconData icon,
    Offset center,
    double size,
    Color color,
  ) {
    final tp = TextPainter(
      text: TextSpan(
        text: String.fromCharCode(icon.codePoint),
        style: TextStyle(
          fontSize: size,
          fontFamily: icon.fontFamily,
          package: icon.fontPackage,
          color: color,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    tp.paint(
      canvas,
      Offset(center.dx - tp.width / 2, center.dy - tp.height / 2),
    );
  }

  @override
  bool shouldRepaint(covariant _LiquidGlassDockPainter old) =>
      // ⚠️ 只比较「结构性」字段 —— 动画/形变由 repaint: 驱动，不靠 shouldRepaint。
      //    之前这里比 `old.stretch != stretch`，但 stretch 已移入 ValueNotifier，
      //    逐帧变化本就由 Listenable 驱动，不该再参与 shouldRepaint。
      old.selectedIndex != selectedIndex ||
      old.items != items ||
      old.style != style ||
      old.isDark != isDark;
}

/// ============================================================
/// Dock 玻璃面 —— 主界面与「Dock 外观调节」页预览**共用同一实现**
/// ============================================================
///
/// ⚠️ 共用的意义：预览页曾经自己用普通的 BackdropFilter 模糊层，
///    与主界面的库玻璃观感不一致，用户反馈「预览没有同步」。
///    现在两边都渲染这一个组件 → 永远一致，不会再漂移。
///
/// 参数定稿（2026-09，用户逐项裁定 + 排查修复）：
///   · blur / glassColor(白度) / thickness(折射厚度) / chromaticAberration(色散)
///     —— 这四项**用户可调**（Dock 调节页的「液态玻璃」分区），
///        经 [dockGlassSettings] 由 DockTuning 值生成；
///   · lightIntensity / lightAngle / refractiveIndex / ambientStrength / saturation
///     —— 定稿值，不开放。
final LiquidGlassSettings kDockGlassSettings = LiquidGlassSettings(
  blur: 4.0,
  lightIntensity: 2.0,
  lightAngle: 60.0,
  thickness: 35,
  chromaticAberration: 0.3,
  refractiveIndex: 1.25,
  ambientStrength: 0.5,
  saturation: 1.4,
  // ⚠️ 用户反馈「和背景颜色有点接近」→ 加白（苹果奶白感）。
  //    0.06 几乎看不出色调；0.15 是"一点点白"的第一档。
  glassColor: Colors.white.withValues(alpha: 0.15),
);

/// 由可调参数生成 Dock 玻璃设置（主界面与预览共用同一份生成逻辑）。
///
/// ⚠️ 只覆盖四个可调项，其余参数继承 [kDockGlassSettings] 的定稿值 ——
///    这样以后加调参滑块时也只需要改这里一处 + 调节页 UI。
LiquidGlassSettings dockGlassSettings({
  required double blur,
  required double white,
  required double thickness,
  required double chroma,
}) {
  return kDockGlassSettings.copyWith(
    blur: blur,
    glassColor: Colors.white.withValues(alpha: white),
    thickness: thickness,
    chromaticAberration: chroma,
  );
}

/// Dock 的玻璃背景层（库 GlassCard，premium 3D shader 管线）。
///
/// ⚠️ 放置规则（都是排查换来的，别改）：
///   · `useOwnLayer: true` + `quality: premium` → 完整 3D shader；
///   · **不要在外面套 ClipRRect** —— LiquidShape 自带裁剪，
///     外套会多一层 saveLayer 破坏 Impeller backdrop 捕获；
///   · 上层**不要再压半透明膜**（自绘层 glassOpacity 必须为 0），
///     否则折射/高光会被洗淡，深色模式还会「像被挡住」。
class GlassDockSurface extends StatelessWidget {
  const GlassDockSurface({super.key, required this.style, this.settings});

  final LiquidGlassDockStyle style;

  /// 玻璃参数；null = 用定稿的 [kDockGlassSettings]。
  final LiquidGlassSettings? settings;

  @override
  Widget build(BuildContext context) {
    return GlassCard(
      shape: LiquidRoundedSuperellipse(borderRadius: style.radius),
      useOwnLayer: true,
      quality: GlassQuality.premium,
      settings: settings ?? kDockGlassSettings,
      child: const SizedBox.expand(),
    );
  }
}
