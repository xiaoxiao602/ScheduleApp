import 'package:flutter/material.dart';
import 'motion.dart';

/// ============================================================
/// 主题（严格照搬原 Android 版 res/values + values-night）
/// ============================================================
///
/// 来源：
///   values/brand_colors.xml          -> AppColors.light
///   values-night/brand_colors_night.xml -> AppColors.dark
///   values/schedule_colors.xml       -> 课表网格/时间列/表头色
///   values-night/schedule_colors.xml -> 深色版
///
/// ⚠️ 色值不要"顺手调一调"——原版的每个值都有 ADR 记录：
///   ADR-009 校徽取色（主色 #06459B，色相 214°）
///   ADR-066 背景压深（#E8EBF2），使 Dock 与背景拉开层次
///            否则 Dock 会"融"进背景，失去悬浮感 —— 勿调回近白
///   ADR-029 on_surface 显式声明，否则深色模式灰字看不清
@immutable
class AppColors {
  const AppColors({
    required this.primary,
    required this.onPrimary,
    required this.primaryContainer,
    required this.onPrimaryContainer,
    required this.secondary,
    required this.onSecondary,
    required this.secondaryContainer,
    required this.onSecondaryContainer,
    required this.tertiary,
    required this.onTertiary,
    required this.tertiaryContainer,
    required this.onTertiaryContainer,
    required this.surface,
    required this.surfaceContainer,
    required this.surfaceContainerHigh,
    required this.onSurface,
    required this.onSurfaceVariant,
    required this.scheduleSurface,
    required this.scheduleGridLine,
    required this.scheduleTimeText,
    required this.scheduleHeaderText,
    required this.outlineVariant,
    required this.courseCellBg,
    required this.courseCellTitle,
    required this.courseCellMeta,
  });

  final Color primary;
  final Color onPrimary;
  final Color primaryContainer;
  final Color onPrimaryContainer;
  final Color secondary;
  final Color onSecondary;
  final Color secondaryContainer;
  final Color onSecondaryContainer;
  final Color tertiary;
  final Color onTertiary;
  final Color tertiaryContainer;
  final Color onTertiaryContainer;
  final Color surface;
  final Color surfaceContainer;
  final Color surfaceContainerHigh;
  final Color onSurface;
  final Color onSurfaceVariant;

  /// 课表区域底色（与 surface 一致）
  final Color scheduleSurface;

  /// 网格线（只画线不填格）
  final Color scheduleGridLine;

  /// 左侧时间列文字
  final Color scheduleTimeText;

  /// 表头文字（非今天）
  final Color scheduleHeaderText;

  /// 分隔线/描边（对应 ?attr/colorOutlineVariant）。
  final Color outlineVariant;

  /// 课程卡片底色（@color/course_cell_bg）。
  ///
  /// ⚠️ ADR-063：统一近白/近黑，**不按课程变色**。
  ///    饱和底曾被评为「好丑」—— 整列高彩度色块会显得花、吵。
  ///    颜色收缩成左侧窄色条，底色保持统一。
  final Color courseCellBg;

  /// 卡片标题色（@color/course_cell_title）。
  final Color courseCellTitle;

  /// 卡片元信息色（@color/course_cell_meta）。
  final Color courseCellMeta;

  /// 浅色（对应 values/brand_colors.xml + values/schedule_colors.xml）
  static const light = AppColors(
    primary: Color(0xFF06459B), // ADR-009，色相 214°（校徽蓝）
    onPrimary: Color(0xFFFFFFFF),
    primaryContainer: Color(0xFFAECFFB),
    onPrimaryContainer: Color(0xFF021D41),
    secondary: Color(0xFF4A6088),
    onSecondary: Color(0xFFFFFFFF),
    secondaryContainer: Color(0xFFD9E2FF),
    onSecondaryContainer: Color(0xFF001B3F),
    tertiary: Color(0xFF77536D),
    onTertiary: Color(0xFFFFFFFF),
    tertiaryContainer: Color(0xFFFFD8F0),
    onTertiaryContainer: Color(0xFF2C1229),
    surface: Color(0xFFE5E9F1), // 再加亮
    surfaceContainer: Color(0xFFE8ECF2),
    surfaceContainerHigh: Color(0xFFFFFFFF), // 卡片统一亮白
    onSurface: Color(0xFF1A1C1E), // ADR-029：必须显式声明
    onSurfaceVariant: Color(0xFF44474E),
    scheduleSurface: Color(0xFFE8EBF2),
    scheduleGridLine: Color(0xFFE0E3E8),
    outlineVariant: Color(0xFFCAC4D0),
    scheduleTimeText: Color(0xFF9AA0A6),
    scheduleHeaderText: Color(0xFF5F6368),
    // ---- 课程卡片（values/course_colors.xml）----
    courseCellBg: Color(0xFFFFFFFF),
    courseCellTitle: Color(0xFF1A1C1E),
    courseCellMeta: Color(0xFF6B7280),
  );

  /// 深色（对应 values-night/*）
  static const dark = AppColors(
    primary: Color(0xFF79B0F9), // 深色下用更亮的 tone 保证对比度
    onPrimary: Color(0xFF04316D),
    primaryContainer: Color(0xFF064190),
    onPrimaryContainer: Color(0xFFAECFFB),
    secondary: Color(0xFFB2C7EF),
    onSecondary: Color(0xFF16304F),
    secondaryContainer: Color(0xFF324867),
    onSecondaryContainer: Color(0xFFD9E2FF),
    tertiary: Color(0xFFE6B9D8),
    onTertiary: Color(0xFF45263D),
    tertiaryContainer: Color(0xFF5E3C55),
    onTertiaryContainer: Color(0xFFFFD8F0),
    surface: Color(0xFF0B0D10), // ADR-066：夜间同理压深
    surfaceContainer: Color(0xFF1D2024),
    surfaceContainerHigh: Color(0xFF282A2F),
    onSurface: Color(0xFFE3E2E6), // 深底配浅字
    onSurfaceVariant: Color(0xFFC4C6CF),
    scheduleSurface: Color(0xFF0B0D10),
    scheduleGridLine: Color(0xFF3A3E45),
    outlineVariant: Color(0xFF49454F),
    scheduleTimeText: Color(0xFF9AA0A6),
    scheduleHeaderText: Color(0xFFC4C6CF),
    // ---- 课程卡片（values-night/course_colors.xml）----
    courseCellBg: Color(0xFF1E2126),
    courseCellTitle: Color(0xFFE8EAED),
    courseCellMeta: Color(0xFF9AA2AE),
  );

  /// 按亮度取对应配色。
  static AppColors of(BuildContext context) =>
      Theme.of(context).brightness == Brightness.dark ? dark : light;
}

/// 主题构建。
abstract final class AppTheme {
  /// 浅色 ThemeData。
  static ThemeData light() => _build(AppColors.light, Brightness.light);

  /// 深色 ThemeData。
  static ThemeData dark() => _build(AppColors.dark, Brightness.dark);

  /// 圆角规范（原版统一用大圆角）。
  /// 卡片 24dp / 弹窗 28dp（DialogCorner）/ 设置卡片 20dp
  static const double cardRadius = 24;
  static const double dialogRadius = 28;
  static const double settingsCardRadius = 20;

  /// 切页动画时长（原版 150ms 缩放+淡入）。
  /// ⚠️ 统一到 Motion.short —— 微交互级，不用长时长。
  static const Duration pageTransition = Motion.short;

  static ThemeData _build(AppColors c, Brightness b) {
    final scheme = ColorScheme(
      brightness: b,
      primary: c.primary,
      onPrimary: c.onPrimary,
      primaryContainer: c.primaryContainer,
      onPrimaryContainer: c.onPrimaryContainer,
      secondary: c.secondary,
      onSecondary: c.onSecondary,
      secondaryContainer: c.secondaryContainer,
      onSecondaryContainer: c.onSecondaryContainer,
      tertiary: c.tertiary,
      onTertiary: c.onTertiary,
      tertiaryContainer: c.tertiaryContainer,
      onTertiaryContainer: c.onTertiaryContainer,
      error: const Color(0xFFBA1A1A),
      onError: Colors.white,
      errorContainer: const Color(0xFFFFDAD6),
      onErrorContainer: const Color(0xFF410002),
      surface: c.surface,
      onSurface: c.onSurface,
      onSurfaceVariant: c.onSurfaceVariant,
      surfaceContainer: c.surfaceContainer,
      surfaceContainerHigh: c.surfaceContainerHigh,
      surfaceContainerHighest: c.surfaceContainerHigh,
      outline: c.onSurfaceVariant,
      outlineVariant: c.scheduleGridLine,
      shadow: Colors.black,
      scrim: Colors.black,
      inverseSurface: c.onSurface,
      onInverseSurface: c.surface,
      inversePrimary: c.primaryContainer,
    );

    return ThemeData(
      useMaterial3: true,
      brightness: b,
      colorScheme: scheme,
      scaffoldBackgroundColor: c.surface,
      // 与原版一致：设置页列表行用统一的列表样式
      listTileTheme: ListTileThemeData(
        iconColor: c.onSurfaceVariant, // ⚠️ 设置行图标 tint = onSurfaceVariant（灰）
        textColor: c.onSurface,
      ),
      appBarTheme: AppBarTheme(
        backgroundColor: c.surface,
        foregroundColor: c.onSurface,
        elevation: 0,
        centerTitle: false,
      ),
      // ⚠️⚠️ SnackBar 全局样式 —— 用户反馈「提示是黑黑的一坨」。
      //
      //    Flutter 默认 SnackBar 用 `inverseSurface` 当背景（浅色主题下 = 深灰/黑），
      //    在浅色 UI 里非常突兀，且和项目其它弹窗风格完全不搭。
      //    这里改成「浅色卡片 + 圆角 + 细描边」，与 Dialog / Card 同一语言，
      //    并设为浮动（floating）而不是贴底通栏，视觉上更像"提示"而非"横幅"。
      snackBarTheme: SnackBarThemeData(
        backgroundColor: c.surfaceContainerHigh,
        contentTextStyle: TextStyle(fontSize: 13, color: c.onSurface),
        actionTextColor: c.primary,
        behavior: SnackBarBehavior.floating,
        elevation: 2,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
        ),
        insetPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      ),
      // ⚠️ 墨迹（InkWell 波纹）裁剪规则提醒：
      //    波纹画在**最近的 Material 图层**上，外层的 clipBehavior **管不到它**。
      //    所以「圆角卡片 + 内部 InkWell」必须**同时**给 Material 设 borderRadius
      //    （或者用 Material(shape: RoundedRectangleBorder(...))）。
      //    漏了的表现：点击时出现**灰色直角矩形**，与卡片圆角不匹配。
      dialogTheme: DialogThemeData(
        backgroundColor: c.surfaceContainerHigh,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(dialogRadius),
        ),
        // ⚠️ 剪裁内容到圆角内 —— 否则自定义 content（如"检查更新"的
        //    可滚动说明）会**溢出到圆角外**，看起来像"多了个方角灰块"。
        clipBehavior: Clip.antiAlias,
      ),
      cardTheme: CardThemeData(
        color: c.surfaceContainerHigh,
        elevation: 0,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(cardRadius),
        ),
      ),
      // Dock（NavigationBar）—— 标准控件，不手搓
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: c.surfaceContainerHigh,
        indicatorColor: c.primaryContainer,
        height: 64,
        labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
        iconTheme: WidgetStateProperty.resolveWith((states) {
          final selected = states.contains(WidgetState.selected);
          return IconThemeData(
            size: 24,
            color: selected ? c.onPrimaryContainer : c.onSurfaceVariant,
          );
        }),
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          final selected = states.contains(WidgetState.selected);
          return TextStyle(
            fontSize: 12,
            fontWeight: selected ? FontWeight.w600 : FontWeight.w400,
            color: selected ? c.onPrimaryContainer : c.onSurfaceVariant,
          );
        }),
      ),
      dividerTheme: DividerThemeData(color: c.scheduleGridLine, space: 1),
    );
  }
}
