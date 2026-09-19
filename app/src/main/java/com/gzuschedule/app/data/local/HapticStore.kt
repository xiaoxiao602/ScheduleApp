package com.gzuschedule.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.view.HapticFeedbackConstants

/**
 * 触感反馈设置（ADR-092）。
 *
 * ⚠️ 为什么需要这个（用户反馈）：
 *    「小米13 是清脆的反馈，小米15 是咚咚」「按钮没有震动反馈」
 *
 *  根因有两个：
 *   ① `performHapticFeedback` 只用在 Dock 上，其他按钮（周次箭头、tab、
 *      设置项、弹窗…）全都没调用 → 所以「按钮没有震动」是**真的缺失**。
 *   ② 震动**波形由厂商决定** —— Android 把「触感常量 → 实际马达波形」的
 *      映射交给 OEM。`CLOCK_TICK` 在 MIUI 上是短促「嗒」，在 HyperOS 上
 *      被重新调校成「咚」。**同一份代码在不同手机上必然不同**，这不是 bug。
 *
 * ⚠️ 所以这里做成**可选 + 可调强度**，让用户在自己的设备上对比着选：
 *    · 先选「风格」（对应不同的 HapticFeedbackConstants）
 *    · 再调「强度」（用 VibratorManager 的振幅缩放，API 31+ 生效）
 *
 * ⚠️ 迁移到 Compose/Flutter 时：
 *    风格常量是 Android 原生 API，跨框架语义相同；
 *    强度缩放是 Android 12+ 的特性，低版本只能回落到风格本身。
 */
class HapticStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 是否启用触感反馈。 */
    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, DEF_ENABLED)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /**
     * 触感风格 —— 存的是 [Style] 的 name（字符串），
     * 这样以后增删枚举值不会让旧数据错位。
     *
     * ⚠️ 用 entries 查找而不是 when(code)：
     *    之前 CardStyleStore 就因为在 when 里漏了分支导致「选了不生效」，
     *    这里直接按名字查表，缺失就落默认值。
     */
    var style: Style
        get() = Style.entries.firstOrNull { it.name == sp.getString(KEY_STYLE, null) }
            ?: DEF_STYLE
        set(v) = sp.edit().putString(KEY_STYLE, v.name).apply()

    /**
     * 强度（百分比，30~100）。
     *
     * ⚠️ 100 = 系统默认强度（不做任何缩放）。
     *    低于 100 时会用 [android.os.VibrationAttributes] 的振幅缩放，
     *    **仅 Android 12（API 31）及以上生效**；低版本忽略此值。
     */
    var strength: Int
        get() = sp.getInt(KEY_STRENGTH, DEF_STRENGTH).coerceIn(MIN_STRENGTH, MAX_STRENGTH)
        set(v) = sp.edit().putInt(KEY_STRENGTH, v.coerceIn(MIN_STRENGTH, MAX_STRENGTH)).apply()

    // ---------- 各场景的开关（默认全开）----------

    /** Dock 拖动/点击时的反馈。 */
    var onDock: Boolean
        get() = sp.getBoolean(KEY_ON_DOCK, true)
        set(v) = sp.edit().putBoolean(KEY_ON_DOCK, v).apply()

    /** 底部 tab 切换时的反馈。 */
    var onTab: Boolean
        get() = sp.getBoolean(KEY_ON_TAB, true)
        set(v) = sp.edit().putBoolean(KEY_ON_TAB, v).apply()

    /** 按钮点击（周次箭头、回到本周、设置项等）的反馈。 */
    var onButton: Boolean
        get() = sp.getBoolean(KEY_ON_BUTTON, true)
        set(v) = sp.edit().putBoolean(KEY_ON_BUTTON, v).apply()

    companion object {
        private const val PREFS = "haptic_tuning"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_STYLE = "style"
        private const val KEY_STRENGTH = "strength"
        private const val KEY_ON_DOCK = "on_dock"
        private const val KEY_ON_TAB = "on_tab"
        private const val KEY_ON_BUTTON = "on_button"

        const val DEF_ENABLED = true
        const val DEF_STRENGTH = 100
        const val MIN_STRENGTH = 30
        const val MAX_STRENGTH = 100

        val DEF_STYLE = Style.CLOCK_TICK

    }
}

/**
 * 触感风格（ADR-092）。
 *
 * ⚠️ 为什么是**顶层声明**而不是 HapticStore 的嵌套类型：
 *    实测 `HapticStore.Style` 在跨文件引用时会报 `Unresolved reference`，
 *    挪到顶层后正常。Kotlin 对 companion 内嵌类型的解析有歧义。
 *
 * ⚠️ 每个常量的实际手感**由厂商决定** —— Android 把「触感常量 → 马达波形」
 *    的映射交给 OEM，所以同一份代码在小米13 和 小米15 上手感不同。
 *    这正是把它做成用户可选设置的原因。
 */
enum class Style(
    val label: String,
    val desc: String,
    val constant: Int,
) {
    /** 时钟滴答 —— 短促清脆（Dock 原本的效果）。 */
    CLOCK_TICK(
        "滴答",
        "短促清脆，像秒针走动（Dock 原本的效果）",
        HapticFeedbackConstants.CLOCK_TICK,
    ),

    /** 虚拟按键 —— 标准点击反馈，最通用、机型差异最小。 */
    VIRTUAL_KEY(
        "按键",
        "标准按键反馈，各机型差异最小",
        HapticFeedbackConstants.VIRTUAL_KEY,
    ),

    /** 键盘敲击 —— 比按键更轻。 */
    KEYBOARD_TAP(
        "轻敲",
        "比按键更轻，接近打字手感",
        HapticFeedbackConstants.KEYBOARD_TAP,
    ),

    /** 文本手柄移动 —— iOS 那种「划过」的细腻感（API 27+）。 */
    TEXT_HANDLE_MOVE(
        "划动",
        "细腻的滑动感，类似 iOS 滚轮",
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            HapticFeedbackConstants.TEXT_HANDLE_MOVE
        else HapticFeedbackConstants.CLOCK_TICK,
    ),

    /** 上下文点击 —— 稍重，适合「确认」类操作。 */
    CONTEXT_CLICK(
        "确认",
        "稍重、更有存在感，适合确认操作",
        HapticFeedbackConstants.CONTEXT_CLICK,
    ),

    /** 长按 —— 最重。 */
    LONG_PRESS(
        "长按",
        "最重的一种，存在感最强",
        HapticFeedbackConstants.LONG_PRESS,
    );

    companion object {
        val ALL = entries.toList()
    }
}
