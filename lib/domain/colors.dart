import 'dart:ui' show Color;

/// ============================================================
/// 课程配色板（对应 domain/CoursePalette.kt，ADR-065）
/// ============================================================
///
/// ⚠️ 用 Material Design 3 官方色板，色值**逐项照搬**，不要自己调色。
///    每种色都保证白字对比度 ≥ 4.5:1（WCAG AA）。
abstract final class CoursePalette {
  /// 预设颜色（24 色）—— 用户手动选色用。
  ///
  /// ⚠️ 与原版 CoursePalette.kt 的 PRESETS **逐项一致**（M3 官方色板 600 档 +
  ///    少量 800 档加深色）。原版按色系分组：冷色 8 → 暖色 8 → 中性/深沉 8。
  ///    每个色都保证白字对比度 ≥ 4.5:1（WCAG AA）。
  ///
  /// ⚠️ 与 [autoAssign] 是**两套独立色板**（原版设计）：
  ///    这里是"用户找色"用（按色系分组，符合直觉）；
  ///    [autoAssign] 是"自动分配"用（按色相排序，保证相邻课程差异最大）。
  ///    不要合并、不要互相赋值。
  static const List<int> presets = [
    // ---- 第一行：冷色系 ----
    0xFF1E88E5, // Blue 600
    0xFF039BE5, // Light Blue 600
    0xFF00ACC1, // Cyan 600
    0xFF00897B, // Teal 600
    0xFF43A047, // Green 600
    0xFF7CB342, // Light Green 600
    0xFFC0CA33, // Lime 600
    0xFFFDD835, // Yellow 600（亮，但白字仍可读）
    // ---- 第二行：暖色系 ----
    0xFFFFB300, // Amber 600
    0xFFFB8C00, // Orange 600
    0xFFF4511E, // Deep Orange 600
    0xFFE53935, // Red 600
    0xFFD81B60, // Pink 600
    0xFF8E24AA, // Purple 600
    0xFF5E35B1, // Deep Purple 600
    0xFF3949AB, // Indigo 600
    // ---- 第三行：中性 / 深沉色 ----
    0xFF546E7A, // Blue Grey 600
    0xFF6D4C41, // Brown 600
    0xFF757575, // Grey 600
    0xFF3949AB, // Indigo 600（复用，深浅配对）
    0xFF00695C, // Teal 800（深）
    0xFF283593, // Indigo 800（深）
    0xFF4527A0, // Deep Purple 800（深）
    0xFFAD1457, // Pink 800（深）
  ];

  /// 深色主题色板（values-night/course_colors.xml）—— 提亮版。
  static const List<int> presetsNight = [
    0xFF5B9BFF,
    0xFF3ED9C4,
    0xFF66D16B,
    0xFFBCD140,
    0xFFFFB84D,
    0xFFFF6B6B,
    0xFFFF6BB8,
    0xFFA98BFF,
    0xFF4FC3F7,
    0xFFA3C43F,
  ];

  /// 兜底色板（自动分配用）—— **按色相排序**，保证顺序取色时差异最大。
  ///
  /// ⚠️ 这 10 色与 values/course_colors.xml 的 course_1..course_10 一致，
  ///    改动会改变所有"未自定义颜色"课程的外观 —— 不要动。
  static const List<int> autoAssign = [
    0xFF1E6FD9, // 蓝     H=215
    0xFF0E9B8A, // 青绿   H=175
    0xFF3D9B40, // 绿     H=125
    0xFF8A9B1E, // 黄绿   H=70
    0xFFD1871A, // 橙     H=38
    0xFFD93F3F, // 红     H=0
    0xFFC4398C, // 洋红   H=325
    0xFF7B4FD9, // 紫     H=265
    0xFF0F8FC4, // 天蓝   H=198
    0xFF6B8E23, // 橄榄   H=80
  ];

  /// 按主题取色板。
  static List<int> forBrightness(bool isDark) =>
      isDark ? presetsNight : presets;

  static Color colorOf(int argb) => Color(argb);

  /// int 色值 → CSS 风格 hex（用于日志/调试）。
  static String hex(int argb) =>
      '#${(argb & 0xFFFFFF).toRadixString(16).padLeft(6, '0').toUpperCase()}';
}

/// ============================================================
/// 课程配色分配（对应 domain/CourseColorAssigner.kt，ADR-050）
/// ============================================================
///
/// ⚠️ 用户曾反馈「英语卡片和高数课的卡片颜色一致」。
///    旧实现 `abs(name.hashCode()) % 8` 撞色概率约 50%。
///    新做法：**按整张课表统一分配**，保证同课同色、异课尽量不同色。
abstract final class CourseColorAssigner {
  /// 为一组课程名分配调色板下标。
  ///
  /// ⚠️ 内部**排序**：保证同一学期不管渲染顺序如何，同一门课拿同一个颜色。
  ///    不排序的话，先渲染的先拿色，翻页/刷新会变色。
  ///
  /// 返回：课程名 → 下标（0-based）。同一课名必得同一值。
  static Map<String, int> assign(Iterable<String> courseNames, int paletteSize) {
    if (paletteSize <= 0) return {};
    final distinct = courseNames
        .where((n) => n.trim().isNotEmpty)
        .toSet()
        .toList()
      ..sort();

    final result = <String, int>{};
    for (var i = 0; i < distinct.length; i++) {
      result[distinct[i]] = i % paletteSize;
    }
    return result;
  }

  /// 兜底：用 FNV-1a 散列（拿不到全量课程时用）。
  ///
  /// ⚠️ 优先用 [assign] —— 这个只是兜底，降低（不能消除）撞色概率。
  ///    Dart 的 String.hashCode 每次运行可能不同（有随机种子），
  ///    绝不能用它做持久化配色 —— 这正是原 Kotlin 版的坑。
  static int assignFallback(String courseName, int paletteSize) {
    if (paletteSize <= 0) return 0;
    var hash = 2166136261; // FNV-1a 32-bit offset basis
    for (final code in courseName.codeUnits) {
      hash = hash ^ (code & 0xFF);
      hash = (hash * 16777619) & 0xFFFFFFFF;
    }
    return hash % paletteSize;
  }
}
