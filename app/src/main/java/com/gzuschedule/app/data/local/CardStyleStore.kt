package com.gzuschedule.app.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * 课程卡片配色模式（ADR-064）。
 *
 * ⚠️⚠️ 为什么做成"用户自选"而不是再定一版配色：
 *   用户对周课表卡片的视觉，已经反馈过**至少 4 轮**：
 *     ① 浅底 + 深字            → 「配色不高级」
 *     ② 浅底 + 两级文字层次     → 仍不满意（同一架构，调数值无用）
 *     ③ 饱和彩底 + 白字        → 「好丑」（改成下面这个）
 *     ④ 近白底 + 彩色左色条     → 「效果不好，要第二张图那个效果」
 *   每次"改数值 → 等反馈 → 再改"循环一轮就要一次构建 + 用户安装验证，
 *   而且**只有用户本人能判定好不好看** —— 助手无法替他决定。
 *
 *   既有教训（用户明确表态过）：同一处视觉问题第 2 次反馈 = 方案错了，
 *   **必须换架构或做成可调参数，不能给第 3 个数值**。
 *
 *   所以这里不再赌某一版配色，而是把两种方案**都实现**，做成设置项：
 *   用户自己切，切到满意为止。此后助手无需再介入配色。
 *
 * ⚠️ 为什么放 SharedPreferences 而不是 Room：
 *   纯 UI 偏好，非业务数据；不需查询/事务。与 [DockTuningStore] 保持一致。
 */
class CardStyleStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 卡片配色模式。 */
    /**
     * 卡片样式。
     *
     * ⚠️⚠️ ADR-072 修「点了没反应」—— 这是个**极易复发的陷阱**：
     *
     *   之前写的是显式分支匹配：
     *     ```
     *     get() = when (sp.getString(KEY_STYLE, null)) {
     *         Style.ACCENT_BAR.name -> Style.ACCENT_BAR   // 只认这一个
     *         else -> DEF_STYLE                           // 其余全落到默认值
     *     }
     *     ```
     *   从 2 种样式扩到 3 种时，**只改了这一个分支**，
     *   于是 SOLID / PLAIN 存进去后读出来都是 DEF_STYLE(=ACCENT_BAR)：
     *   用户点「彩色填充」→ 存 SOLID → 读回 ACCENT_BAR → 勾还在原处 → 「点不了」。
     *
     *   ✅ 现在改为**通用枚举查找**：把存的字符串按名字匹配所有枚举值，
     *      无论以后加多少种样式都自动生效，不会再有"忘了加分支"的问题。
     */
    var style: Style
        get() = Style.entries.firstOrNull { it.name == sp.getString(KEY_STYLE, null) }
            ?: DEF_STYLE
        set(v) = sp.edit().putString(KEY_STYLE, v.name).apply()

    // ==================== 课程颜色覆盖（ADR-065）====================

    /**
     * 用户为**某门课**手动指定的颜色（ARGB）。
     *
     * ⚠️ 键是课程名（不是 id）：
     *   课表每学期同步会重建记录（id 会变），但课程名稳定。
     *   用 id 做键的话，重新同步一次用户调好的颜色就全丢了。
     *
     * ⚠️ 值存为 Int（ARGB）而不是资源 id：
     *   用户可用色轮取**任意**颜色，不是有限资源集，无法用 R.color 表达。
     */
    fun customColorOf(courseName: String): Int? =
        sp.getInt(prefixKey(courseName), NO_COLOR).takeIf { it != NO_COLOR }

    /** 设置某门课的自定义颜色；传 null 表示恢复自动分配。 */
    fun setCustomColor(courseName: String, argb: Int?) {
        sp.edit().apply {
            if (argb == null) remove(prefixKey(courseName))
            else putInt(prefixKey(courseName), argb)
        }.apply()
    }

    /** 清空所有自定义颜色（「恢复默认配色」用）。 */
    fun clearAllCustomColors() {
        sp.edit().apply {
            sp.all.keys
                .filter { it.startsWith(KEY_COLOR_PREFIX) }
                .forEach { remove(it) }
        }.apply()
    }

    /** 是否已有任意自定义颜色（设置页显示「恢复默认」按钮用）。 */
    fun hasAnyCustomColor(): Boolean =
        sp.all.keys.any { it.startsWith(KEY_COLOR_PREFIX) }

    /**
     * ⚠️ 用前缀而非课程名直接做键：
     *   SharedPreferences 的键是平铺的，课程名可能与其他键撞名
     *   （例如某门课恰好叫 "card_style_mode"）。加前缀彻底避免。
     */
    private fun prefixKey(courseName: String) = "$KEY_COLOR_PREFIX$courseName"

    /**
     * 两种卡片外观。
     *
     * ⚠️ 命名用外观特征（实色/浅色），不用"图2/图3"这种指代 ——
     *    截图会丢，名字要能自解释。
     */
    enum class Style(val displayName: String, val description: String) {
        /**
         * 整块课程色填充 + 白字。
         * 颜色最有冲击力，识别最快，整页偏活泼。
         */
        SOLID("彩色填充", "整块课程色 + 白字，颜色醒目"),

        /**
         * 白底 + **加粗**左侧色条。
         * 页面干净，用一条醒目的色条标记课程 —— 兼顾克制与辨识度。
         */
        ACCENT_BAR("白底 + 色条", "白底 + 左侧粗色条，干净且有辨识度"),

        /**
         * 纯白底，无任何装饰。
         * 最极简，颜色完全不参与，靠文字区分。
         */
        PLAIN("纯白底", "白底无装饰，最简洁"),
    }

    companion object {
        private const val PREFS = "card_style"
        private const val KEY_STYLE = "card_style_mode"

        /** 课程颜色覆盖的键前缀（见 [prefixKey]）。 */
        private const val KEY_COLOR_PREFIX = "course_color__"

        /** 「没有自定义颜色」的哨兵值 —— SharedPreferences 不能存 null。 */
        private const val NO_COLOR = 0

        /**
         * 默认值。
         *
         * ⚠️ 默认选 SOLID：用户本次明确要求「我要你改的其实是第二张图这个效果」，
         *    故默认给实色。若他之后切到 OUTLINE 并反馈，再改这里。
         */
        val DEF_STYLE: Style = Style.ACCENT_BAR

        /** 供设置页渲染选项列表。 */
        val ALL: List<Style> = listOf(Style.SOLID, Style.ACCENT_BAR, Style.PLAIN)
    }
}