import 'package:flutter/material.dart';

import '../motion.dart';

/// ============================================================
/// 状态栏渐变遮罩（小米 / HyperOS 风格）· v13 定稿
/// ============================================================
///
/// ⚠️ 用户反馈演进（这个组件改了 12 版，每版的坑都记着）：
///
/// | 版本 | 现象           | 根因 |
/// |------|----------------|------|
/// | v1   | 没有模糊       | `ShaderMask(dstIn)` 把 BackdropFilter 擦掉了 |
/// | v2   | 很突兀         | 「清晰模糊 + 独立渐隐带」= 两段分明 |
/// | v3   | 好卡           | 8 段 BackdropFilter 常驻，每帧卷积背景 |
/// | v4-6 | 太浅           | 用 surface 色但整条都在渐变，顶部没真正遮住 |
/// | v7-9 | 有断层         | 采样点太少 / 曲线中段斜率过大 |
/// | v10  | 好一些         | 64 点混合曲线 |
/// | v11  | 拖尾           | 线性末段 12% 跨 10% 高度，到顶还看得见 |
/// | v12  | 好一些         | 末段 1.8× 收尾 |
/// | v13  | **定稿**       | **按用户指定的 58% / 90% / 100% 三点定曲线** |
///
/// ## 最终曲线
///
/// ```
/// 位置    alpha
/// ──────────────────────────────────────
/// 0%      100%   ████████████████████████  实心
/// 15%     100%   ████████████████████████  实心（内容在此"消失"）
/// 35%      85%   ████████████████████
/// 58%      60%   ██████████████            ← 用户指定点
/// 78%      28%   ███████
/// 90%       6%   ██                        ← 用户指定点
/// 100%      0%                             ← 用户指定点（彻底消失）
/// ```
///
/// ⚠️ 关键：90% 处只有 6% 不透明度 —— 这样"接近顶部时"内容已经
///    基本不可见，不会出现 v11 那种"到顶了还能看见"的拖尾。
///
/// ## 用法
///
/// ```dart
/// Stack(
///   children: [
///     ListView(...),
///     const StatusBarBlur(),   // 必须放最后一个 child
///   ],
/// )
/// ```
class StatusBarBlur extends StatelessWidget {
  const StatusBarBlur({
    super.key,
    this.visible = true,
    this.height = 65,
  });

  /// 是否显示。
  final bool visible;

  /// 遮罩高度。
  /// ⚠️ 演进：40 → 50 → 60 → 65 →（用户回退）**65dp**。
  final double height;

  @override
  Widget build(BuildContext context) {
    final surface = Theme.of(context).colorScheme.surface;

    return Positioned(
      top: 0,
      left: 0,
      right: 0,
      height: height,
      child: IgnorePointer(
        // ⚠️ 不拦截触摸 —— 否则状态栏那一条区域点不动、列表滚不上去
        child: AnimatedOpacity(
          opacity: visible ? 1.0 : 0.0,
          duration: Motion.short,
          curve: Motion.standard,
          child: DecoratedBox(
            decoration: BoxDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                colors: [
                  surface,                           //   0%  实心
                  surface,                           //  15%  实心
                  surface.withValues(alpha: 0.85),   //  35%
                  surface.withValues(alpha: 0.60),   //  58%  ← 用户指定
                  surface.withValues(alpha: 0.28),   //  78%
                  surface.withValues(alpha: 0.06),   //  90%  ← 用户指定
                  surface.withValues(alpha: 0.0),    // 100%  ← 用户指定
                ],
                stops: const [0.0, 0.15, 0.35, 0.58, 0.78, 0.90, 1.0],
              ),
            ),
            child: const SizedBox.expand(),
          ),
        ),
      ),
    );
  }
}
