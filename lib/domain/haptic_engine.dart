import 'package:flutter/services.dart';

import '../data/local/settings_stores.dart' show HapticStyle;

/// ============================================================
/// 触感引擎（1.2.1+13）—— 统一入口，对齐原版 Haptics.kt（ADR-092）
/// ============================================================
///
/// ⚠️ 为什么要有它（用户反馈「切换风格回主页还是原来的效果」「好几个效果重复」）：
///   1. 之前 main.dart 写死 HapticFeedback.selectionClick() —— 风格设置形同虚设；
///   2. Flutter 内置 HapticFeedback 只有 5 种常量，6 个风格里 3 个撞同一效果。
///
/// 现在：风格 → Android 原生 HapticFeedbackConstants（6 种 OEM 调校真效果），
/// 强度 <100 由原生 Vibrator 振幅缩放（MainActivity.kt 与原版逐行对齐）。
/// 桥不可用时退回内置映射（保底，不崩）。
abstract final class HapticEngine {
  static const _channel = MethodChannel('gzuschedule/haptic');

  /// 按风格 + 强度触发一次。
  ///
  /// ⚠️ 不在这里做 enabled/场景开关 —— 由调用方按场景（onDock/onTab/onButton）
  ///    判断（与原版 Haptics.perform 的 Scene 语义一致）；
  ///    设置页「试一下」**无视 enabled 总开关**（对齐原版 preview：
  ///    否则用户关掉总开关后永远试不出效果）。
  static Future<void> play({
    required HapticStyle style,
    required int strength,
  }) async {
    try {
      await _channel.invokeMethod('perform', {
        'style': style.name,
        'strength': strength,
      });
    } catch (_) {
      await _fallback(style);
    }
  }

  /// 桥不可用（理论上不会发生）时的保底映射。
  static Future<void> _fallback(HapticStyle s) async {
    switch (s) {
      case HapticStyle.clockTick:
      case HapticStyle.keyboardTap:
      case HapticStyle.textHandleMove:
        await HapticFeedback.selectionClick();
      case HapticStyle.virtualKey:
        await HapticFeedback.lightImpact();
      case HapticStyle.contextClick:
        await HapticFeedback.mediumImpact();
      case HapticStyle.longPress:
        await HapticFeedback.heavyImpact();
    }
  }
}
