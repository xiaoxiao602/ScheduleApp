import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/local/settings_stores.dart'
    show HapticConfig, HapticStyle;
import '../providers.dart';
import '../theme.dart';

/// ============================================================
/// 触感反馈二级页（一比一对应 fragment_haptic.xml，ADR-094）
/// ============================================================
///
/// 为什么这些设置存在（原版记录的用户反馈）：
///   「小米13 是清脆的反馈，小米15 是咚咚」「按钮没有震动反馈」
///   · 震动波形由厂商决定 —— Android 把「常量 → 马达波形」的映射交给 OEM，
///     同一份代码在不同手机上必然不同，这不是 bug。
///   · 所以让用户在自己设备上对比着选风格 + 调强度。
///
/// ⚠️ ADR-095：原版不用 Spinner（下拉列表由系统绘制，圆角改不了，
///    与 App 的 28dp 圆角风格不统一）→ 改成点击行 + 自定义圆角单选弹窗。
///    Flutter 侧用 AlertDialog + RadioListTile 达成同样效果。
class HapticPage extends ConsumerWidget {
  const HapticPage({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    final async = ref.watch(hapticConfigProvider);

    return Scaffold(
      backgroundColor: colors.surface,
      appBar: AppBar(title: const Text('触感反馈')),
      body: async.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(child: Text('读取失败：$e')),
        data: (cfg) => _Body(cfg: cfg),
      ),
    );
  }
}

class _Body extends ConsumerWidget {
  const _Body({required this.cfg});

  final HapticConfig cfg;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final colors = AppColors.of(context);
    void save(HapticConfig c) => ref.read(hapticConfigProvider.notifier).apply(c);

    return ListView(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 40),
      children: [
        // ============ 一、总开关 ============
        SwitchListTile(
          value: cfg.enabled,
          onChanged: (v) => save(cfg.copyWith(enabled: v)),
          title: const Text('启用震动反馈', style: TextStyle(fontSize: 15)),
          contentPadding: EdgeInsets.zero,
        ),

        // ============ 二、风格 ============
        _Label('震动风格'),
        Text(
          '当前：${cfg.style.label}',
          style: TextStyle(fontSize: 14, color: colors.onSurface),
        ),
        const SizedBox(height: 2),
        Text(
          _styleDesc(cfg.style),
          style: TextStyle(
            fontSize: 11,
            color: colors.onSurface.withValues(alpha: 0.6),
          ),
        ),
        const SizedBox(height: 8),

        // 选择行（点击弹单选）
        InkWell(
          onTap: () => _pickStyle(context, ref, cfg),
          borderRadius: BorderRadius.circular(AppTheme.settingsCardRadius),
          child: Container(
            constraints: const BoxConstraints(minHeight: 52),
            padding: const EdgeInsets.symmetric(horizontal: 16),
            decoration: BoxDecoration(
              color: colors.surfaceContainerHigh,
              borderRadius: BorderRadius.circular(AppTheme.settingsCardRadius),
            ),
            child: Row(
              children: [
                Expanded(
                  child: Text(
                    cfg.style.label,
                    style: TextStyle(fontSize: 15, color: colors.onSurface),
                  ),
                ),
                Icon(Icons.keyboard_arrow_down_rounded,
                    size: 20,
                    color: colors.onSurface.withValues(alpha: 0.45)),
              ],
            ),
          ),
        ),
        const SizedBox(height: 8),

        // 试一下
        FilledButton.tonal(
          onPressed: () => _preview(cfg),
          child: const Text('试一下'),
        ),

        // ============ 三、强度 ============
        _Label('强度'),
        Text(
          '强度 · ${cfg.strength}%',
          style: TextStyle(fontSize: 12, color: colors.onSurface),
        ),
        Slider(
          value: cfg.strength.toDouble(),
          min: HapticConfig.MIN_STRENGTH.toDouble(),
          max: HapticConfig.MAX_STRENGTH.toDouble(),
          divisions: (HapticConfig.MAX_STRENGTH - HapticConfig.MIN_STRENGTH) ~/ 5,
          onChanged: (v) => save(cfg.copyWith(strength: v.round())),
        ),
        Text(
          '100% = 系统默认强度。低于 100% 需要 Android 12 及以上才生效。',
          style: TextStyle(
            fontSize: 11,
            color: colors.onSurface.withValues(alpha: 0.55),
          ),
        ),

        // ============ 四、生效范围 ============
        _Label('在哪些地方震动'),
        SwitchListTile(
          value: cfg.onDock,
          onChanged: (v) => save(cfg.copyWith(onDock: v)),
          title: const Text('Dock 拖动 / 点击', style: TextStyle(fontSize: 14)),
          contentPadding: EdgeInsets.zero,
          dense: true,
        ),
        SwitchListTile(
          value: cfg.onTab,
          onChanged: (v) => save(cfg.copyWith(onTab: v)),
          title: const Text('底部导航切换', style: TextStyle(fontSize: 14)),
          contentPadding: EdgeInsets.zero,
          dense: true,
        ),
        SwitchListTile(
          value: cfg.onButton,
          onChanged: (v) => save(cfg.copyWith(onButton: v)),
          title: const Text('按钮点击', style: TextStyle(fontSize: 14)),
          contentPadding: EdgeInsets.zero,
          dense: true,
        ),

        // ============ 设备差异说明 ============
        const SizedBox(height: 20),
        Text(
          '💡 不同手机的震动马达和系统调校不同，同一设置在两台设备上手感可能不一样'
          ' —— 这是系统决定的，按自己在意的设备调即可。',
          style: TextStyle(
            fontSize: 11,
            height: 1.35,
            color: colors.onSurface.withValues(alpha: 0.55),
          ),
        ),
      ],
    );
  }

  /// 风格说明（对应原版 tvHapticStyleDesc）。
  static String _styleDesc(HapticStyle s) {
    switch (s) {
      case HapticStyle.clockTick:
        return '短促清脆，像秒针走动（Dock 原本的效果）';
      case HapticStyle.virtualKey:
        return '标准按键反馈，机型差异最小';
      case HapticStyle.keyboardTap:
        return '比按键更轻，接近打字';
      case HapticStyle.textHandleMove:
        return '细腻滑动感，类似 iOS（需 Android 8.1+）';
      case HapticStyle.contextClick:
        return '稍重，适合确认类操作';
      case HapticStyle.longPress:
        return '最重的一档';
    }
  }

  /// 风格单选弹窗（替代原版 Spinner，ADR-095）。
  Future<void> _pickStyle(
      BuildContext context, WidgetRef ref, HapticConfig cfg) async {
    final picked = await showDialog<HapticStyle>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('震动风格'),
        children: [
          for (final s in HapticStyle.values)
            RadioListTile<HapticStyle>(
              value: s,
              groupValue: cfg.style,
              onChanged: (v) {
                Navigator.of(ctx).pop(v);
                // 选中即试一下，方便对比
                _preview(cfg.copyWith(style: v ?? cfg.style));
              },
              title: Text(s.label),
              subtitle: Text(
                _styleDesc(s),
                style: const TextStyle(fontSize: 11),
              ),
            ),
          Padding(
            padding: const EdgeInsets.only(top: 4, bottom: 4),
            child: Center(
              child: TextButton(
                onPressed: () => Navigator.of(ctx).pop(),
                child: const Text('取消'),
              ),
            ),
          ),
        ],
      ),
    );
    if (picked == null) return;
    await ref
        .read(hapticConfigProvider.notifier)
        .apply(cfg.copyWith(style: picked));
  }

  /// 试一下 —— 按当前配置触发一次震动。
  ///
  /// ⚠️ Flutter 内置 HapticFeedback 只有 5 种常量
  ///    （lightImpact/mediumImpact/heavyImpact/selectionClick/vibrate），
  ///    缺 CLOCK_TICK 等 —— 完整复刻需 PlatformChannel。
  ///    这里先按风格映射到最接近的内置效果。
  static Future<void> _preview(HapticConfig cfg) async {
    if (!cfg.enabled) return;
    switch (cfg.style) {
      case HapticStyle.clockTick:
        await HapticFeedback.selectionClick();
      case HapticStyle.virtualKey:
        await HapticFeedback.lightImpact();
      case HapticStyle.keyboardTap:
        await HapticFeedback.selectionClick();
      case HapticStyle.textHandleMove:
        await HapticFeedback.selectionClick();
      case HapticStyle.contextClick:
        await HapticFeedback.mediumImpact();
      case HapticStyle.longPress:
        await HapticFeedback.heavyImpact();
    }
  }
}

class _Label extends StatelessWidget {
  const _Label(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    final colors = AppColors.of(context);
    return Padding(
      padding: const EdgeInsets.only(top: 18, bottom: 6),
      child: Text(
        text,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.bold,
          color: colors.onSurface.withValues(alpha: 0.6),
        ),
      ),
    );
  }
}