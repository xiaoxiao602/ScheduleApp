import 'package:flutter/material.dart';
import 'motion.dart';

/// ============================================================
/// 统一的二级页路由（对应原版 activity_subpage 的右滑进入）
/// ============================================================
///
/// ⚠️ 为什么需要它：
///   Flutter 默认的 `MaterialPageRoute` 在 Android 上是从**底部淡入**
///   （Material 3 的 shared-axis 过渡），而原版 1.0.3 的二级页是
///   **从右侧滑入、返回时滑出** —— 这是 Android 原生的 push 语义，
///   用户对它有肌肉记忆（右滑返回）。
///
///   所以所有二级页统一走本路由，保证：
///     · 进入 = 从右滑入（300ms，easeOutCubic）
///     · 返回 = 向右滑出
///     · 手势返回（从左边缘右滑）由 Flutter 在 iOS/部分 Android 上自动支持
///
/// ⚠️ 不要各处直接写 MaterialPageRoute —— 会退回底部弹入的观感。
Route<T> slideRoute<T>(Widget page) {
  return PageRouteBuilder<T>(
    pageBuilder: (_, _, _) => page,
    transitionDuration: Motion.medium,
    reverseTransitionDuration: Motion.short,
    transitionsBuilder: (context, animation, secondary, child) {
      // 右滑入 + 轻微淡化（纯位移略显生硬）
      // ⚠️ 用户反馈「二级界面阻尼感有点轻」——
      //    之前用 easeOutCubic（收尾偏"飘"），缺少"到位"的顿挫感。
      //    换成 easeOutQuint：起步很快、末段强烈减速 —— 像被"吸"到位，
      //    这是 Material 3 里 "emphasized decelerate" 的手感。
      //    退出仍用 easeInCubic（干脆甩出，不拖泥带水）。
      final curved = CurvedAnimation(
        parent: animation,
        curve: Motion.emphasized,
        reverseCurve: Motion.exit,
      );
      return SlideTransition(
        position: Tween<Offset>(
          begin: const Offset(1, 0),
          end: Offset.zero,
        ).animate(curved),
        child: FadeTransition(opacity: curved, child: child),
      );
    },
  );
}
