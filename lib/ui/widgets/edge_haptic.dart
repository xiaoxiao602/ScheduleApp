import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../domain/haptic_engine.dart';
import '../providers.dart';

/// ============================================================
/// 滑到顶/底触感（1.2.1+14）
/// ============================================================
///
/// 用户要求：「每个界面上下滑到底加入震动反馈，震动效果同步震动设置」。
///
/// ⚠️ 全局唯一入口：包在 MaterialApp 外层 —— 所有页面（含 push 的二级页）
///    的滚动通知都冒泡到这里，不必逐页包、不动任何页面布局。
///
/// ⚠️ 只在「触到边缘」的**瞬间**触发一次（上升沿判定）：
///    停在边缘不连震；250ms 冷却防止嵌套滚动/回弹抖动重复触发。
///
/// ⚠️ 效果同步「触感反馈」设置（风格 + 强度 + 总开关），且每次**现读**配置
///    —— 不缓存（+13 教训：缓存旧值 = 设置切换无效）。
class EdgeHapticScope extends ConsumerStatefulWidget {
  const EdgeHapticScope({super.key, required this.child});

  final Widget child;

  @override
  ConsumerState<EdgeHapticScope> createState() => _EdgeHapticScopeState();
}

class _EdgeHapticScopeState extends ConsumerState<EdgeHapticScope> {
  bool _atTop = false;
  bool _atBottom = false;
  DateTime _lastFire = DateTime.fromMillisecondsSinceEpoch(0);

  void _fire() {
    final cfg = ref.read(hapticConfigProvider).valueOrNull;
    if (cfg == null || !cfg.enabled) return;
    final now = DateTime.now();
    if (now.difference(_lastFire).inMilliseconds < 250) return;
    _lastFire = now;
    HapticEngine.play(style: cfg.style, strength: cfg.strength);
  }

  bool _onScroll(ScrollNotification n) {
    final m = n.metrics;
    if (m.axis != Axis.vertical) return false; // 只管纵向（横向翻页不算）
    final atTop = m.pixels <= m.minScrollExtent + 0.5;
    final atBottom =
        m.maxScrollExtent > 0 && m.pixels >= m.maxScrollExtent - 0.5;
    if (atTop && !_atTop) _fire();
    if (atBottom && !_atBottom) _fire();
    _atTop = atTop;
    _atBottom = atBottom;
    return false; // 不吞通知，各页照常监听自己的滚动
  }

  @override
  Widget build(BuildContext context) {
    return NotificationListener<ScrollNotification>(
      onNotification: _onScroll,
      child: widget.child,
    );
  }
}
