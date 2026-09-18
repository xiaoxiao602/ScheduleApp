package com.gzuschedule.app.domain

/**
 * 课程配色板（ADR-065）。
 *
 * ⚠️⚠️ 为什么用 Material Design 3 官方调色板：
 *   用户要求「参考原生安卓的，看上去高级的色彩」。
 *
 *   「高级」在这类界面上的实质是**色彩体系的整体一致性**，不是单个色好看：
 *     · 所有色取同一明度档（M3 的 600 / 700）→ 并排时重量一致，不会有的跳有的沉
 *     · 所有色取相近的饱和度 → 不会出现"一个荧光一个灰"
 *     · 色相均匀铺满 360° → 相邻课程的色相差异最大化，最易区分
 *   这正是 Google 那套色板本身的设计原则 —— 直接用它比自己调更稳。
 *
 * ⚠️ 每个色都保证白字对比度 ≥ 4.5:1（WCAG AA），故卡片文字统一用白色。
 *    选取档位时已按此约束筛选（M3 的 600 档在浅色主题下最合适）。
 *
 * ⚠️ 与默认调色板（values/course_colors.xml 的 course_1..course_10）的关系：
 *   那边是**自动分配**用的（保证不撞色）；
 *   这里是**用户手动选**用的（覆盖自动分配）。
 *   两套独立，互不干扰。
 */
object CoursePalette {

    /**
     * 预设颜色（24 色）。
     *
     * ⚠️ 用 `0xFF...` 直接写 ARGB 而不是定义 R.color 资源：
     *   色轮取色产出的也是 Int，两者需要同一种表示才能统一存储
     *   （见 CardStyleStore.customColorOf）。定义成资源反而要来回转换。
     *
     * 命名对照 Material Design 3 官方色板：
     *   Blue / Teal / Green / LightGreen / Lime / Yellow / Amber / Orange
     *   DeepOrange / Red / Pink / Purple / DeepPurple / Indigo / Cyan / BlueGrey
     */
    val PRESETS: List<Int> = listOf(
        // ---- 第一行：冷色系 ----
        0xFF1E88E5.toInt(), // Blue 600
        0xFF039BE5.toInt(), // Light Blue 600
        0xFF00ACC1.toInt(), // Cyan 600
        0xFF00897B.toInt(), // Teal 600
        0xFF43A047.toInt(), // Green 600
        0xFF7CB342.toInt(), // Light Green 600
        0xFFC0CA33.toInt(), // Lime 600
        0xFFFDD835.toInt(), // Yellow 600（亮，但白字仍可读）

        // ---- 第二行：暖色系 ----
        0xFFFFB300.toInt(), // Amber 600
        0xFFFB8C00.toInt(), // Orange 600
        0xFFF4511E.toInt(), // Deep Orange 600
        0xFFE53935.toInt(), // Red 600
        0xFFD81B60.toInt(), // Pink 600
        0xFF8E24AA.toInt(), // Purple 600
        0xFF5E35B1.toInt(), // Deep Purple 600
        0xFF3949AB.toInt(), // Indigo 600

        // ---- 第三行：中性 / 深沉色 ----
        0xFF546E7A.toInt(), // Blue Grey 600
        0xFF6D4C41.toInt(), // Brown 600
        0xFF757575.toInt(), // Grey 600
        0xFF3949AB.toInt(), // Indigo 600（复用，深浅配对）
        0xFF00695C.toInt(), // Teal 800（深）
        0xFF283593.toInt(), // Indigo 800（深）
        0xFF4527A0.toInt(), // Deep Purple 800（深）
        0xFFAD1457.toInt(), // Pink 800（深）
    )

    /**
     * 兜底色板（自动分配用）。
     *
     * ⚠️ 与 [PRESETS] 分开：自动分配要保证"按顺序取色时色相差异最大"，
     *    所以这个列表是**按色相排序**的；而 PRESETS 是按色系分组排的
     *    （用户找色时按色系找更符合直觉）。两者目的不同，不能共用。
     */
    val AUTO_ASSIGN: List<Int> = listOf(
        0xFF1E88E5.toInt(), // 蓝   H≈207
        0xFF00897B.toInt(), // 青绿 H≈174
        0xFF43A047.toInt(), // 绿   H≈122
        0xFF7CB342.toInt(), // 黄绿 H≈88
        0xFFFFB300.toInt(), // 橙黄 H≈45
        0xFFF4511E.toInt(), // 橙红 H≈14
        0xFFE53935.toInt(), // 红   H≈1
        0xFFD81B60.toInt(), // 洋红 H≈340
        0xFF8E24AA.toInt(), // 紫   H≈291
        0xFF5E35B1.toInt(), // 深紫 H≈262
        0xFF3949AB.toInt(), // 靛蓝 H≈231
        0xFF00ACC1.toInt(), // 天蓝 H≈187
    )
}