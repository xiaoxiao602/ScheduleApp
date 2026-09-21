import 'package:flutter/animation.dart';

/// ============================================================
/// 统一动效规范（Motion Spec）
/// ============================================================
///
/// ⚠️ 用户要求「所有动画加非线性变化，看起来优雅丝滑」。
///
/// ## 为什么线性动画显得廉价
///
/// 线性（`Curves.linear`）意味着**匀速起动、匀速停止** —— 现实中没有物体这样运动，
/// 观感是"机械、生硬、廉价"。真实世界的运动有加减速（惯性）。
///
/// ## 本项目的三条曲线
///
/// * [standard]  —— **easeInOutCubic**：通用。起步慢、中段快、收尾慢。
///                  用于"从 A 到 B"的位移（切页、滑块、滚动定位）。
///
/// * [enter]    —— **easeOutCubic**：进入。**起步快、收尾慢**（像被"吸"到位）。
///                  用于"新内容出现"（页面滑入、元素展开、Toast 浮出）。
///                  收尾慢 = 用户能看清最终状态，是"优雅"的关键。
///
/// * [exit]      —— **easeInCubic**：退出。**起步慢、收尾快**（像被"甩"出去）。
///                  用于"内容消失"（页面滑出、元素收起）。
///
/// * [emphasized] —— **easeOutQuint**：强调。比 enter 收得更快、更"粘"。
///                  用于需要明确"到达感"的动作（开关、勾选）。
///
/// * [spring]    —— 带轻微回弹的曲线，用于需要"生命感"的交互（Dock 滑块）。
///
/// ## 时长
///
/// 短动作 150~200ms，常规 250~300ms，大范围位移 350~400ms。
/// **超过 400ms 会显得拖沓**（除非是刻意的戏剧性效果）。

abstract final class Motion {
  // ---------------------------------------------------------- 曲线
  /// 通用位移：起步慢 → 中段快 → 收尾慢
  static const Curve standard = Curves.easeInOutCubic;

  /// 进入：起步快 → 收尾慢（"被吸到位"）
  static const Curve enter = Curves.easeOutCubic;

  /// 退出：起步慢 → 收尾快（"被甩出去"）
  static const Curve exit = Curves.easeInCubic;

  /// 强调：更"粘"的收尾
  static const Curve emphasized = Curves.easeOutQuint;

  /// 轻微回弹（用于需要生命感的交互）
  static const Curve spring = Curves.easeOutBack;

  /// 线性 —— 仅用于**进度类**动画（进度条填充、无限循环指示器）。
  /// ⚠️ 不要用它做位移/淡入淡出。
  static const Curve progress = Curves.linear;

  // ---------------------------------------------------------- 时长
  /// 微交互（按钮按下、勾选）
  static const Duration micro = Duration(milliseconds: 120);

  /// 短动作（下拉刷新收回、Toast）
  static const Duration short = Duration(milliseconds: 180);

  /// 常规（二级页切换、元素展开）
  static const Duration medium = Duration(milliseconds: 260);

  /// 大范围位移（Dock 切页、跨页导航）
  static const Duration long = Duration(milliseconds: 340);
}
