package com.gzuschedule.app.ui.widget

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import com.gzuschedule.app.data.local.HapticStore
import com.gzuschedule.app.data.local.Style

/**
 * 触感反馈统一入口（ADR-092）。
 *
 * ⚠️ 为什么需要统一入口：
 *    原本震动代码散落各处（只有 DockBarView 里两处直接调
 *    `performHapticFeedback`），导致：
 *      · 其他按钮全都没有反馈（用户反馈「按钮没有震动反馈」）
 *      · 想调整风格时要改很多地方
 *      · 强度设置无法统一应用
 *
 * 用法：
 * ```
 * Haptics.click(view)                 // 普通按钮点击
 * Haptics.tab(view)                   // tab 切换
 * Haptics.dragTick(view)              // 拖动经过一格
 * Haptics.preview(view)               // 设置页试手感
 * ```
 *
 * ⚠️ 实现要点：
 *   1. 优先用 View.performHapticFeedback —— 不需要 VIBRATE 权限，
 *      系统会尊重用户的「触感反馈」总开关。**这是主要路径**。
 *   2. 强度 <100 且 API 31+ 时，额外用 Vibrator 施加振幅缩放
 *      （performHapticFeedback 本身不支持调强度）。
 *   3. 任何异常都吞掉 —— 触感失败绝不能影响主功能。
 */
object Haptics {

    /** 场景：普通按钮点击。 */
    fun click(view: View?) = perform(view, Style.VIRTUAL_KEY, Scene.BUTTON)

    /** 场景：tab 切换。 */
    fun tab(view: View?) = perform(view, Style.VIRTUAL_KEY, Scene.TAB)

    /** 场景：Dock 拖动经过一格（短促，符合原本手感）。 */
    fun dragTick(view: View?) = perform(view, Style.CLOCK_TICK, Scene.DOCK)

    /** 场景：Dock 点击选中。 */
    fun dockSelect(view: View?) = perform(view, Style.CLOCK_TICK, Scene.DOCK)

    /** 场景：确认类操作（稍重）。 */
    fun confirm(view: View?) = perform(view, Style.CONTEXT_CLICK, Scene.BUTTON)

    /**
     * 设置页「试手感」—— 强制无视 enabled 开关，直接按当前 style 触发。
     * 否则用户关掉总开关后就永远试不出效果了。
     */
    fun preview(view: View?, style: Style, strength: Int) {
        val ctx = view?.context ?: return
        try {
            view.performHapticFeedback(style.constant, FLAGS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && strength < 100) {
                amplify(ctx, strength)
            }
        } catch (_: Throwable) {
        }
    }

    // ---------- 内部 ----------

    private enum class Scene { DOCK, TAB, BUTTON }

    /**
     * ⚠️ 这两个 flag 必须带：
     *   · FLAG_IGNORE_GLOBAL_SETTING 会让反馈**无视系统的触感开关** ——
     *     我们**不加**它，尊重用户系统设置。
     *   · FLAG_IGNORE_VIEW_SETTING 允许在 view 自身关闭触感时仍然触发
     *     （我们要自己控制开关，所以加上）。
     */
    private const val FLAGS = HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING

    private fun perform(view: View?, defaultStyle: Style, scene: Scene) {
        val v = view ?: return
        val store = runCatching { HapticStore(v.context) }.getOrNull() ?: return

        if (!store.enabled) return

        // 场景级开关
        val sceneOn = when (scene) {
            Scene.DOCK -> store.onDock
            Scene.TAB -> store.onTab
            Scene.BUTTON -> store.onButton
        }
        if (!sceneOn) return

        val style = store.style
        val strength = store.strength

        try {
            v.performHapticFeedback(style.constant, FLAGS)
            // 强度缩放：performHapticFeedback 不支持，API 31+ 用 Vibrator 补一次
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && strength < 100) {
                amplify(v.context, strength)
            }
        } catch (_: Throwable) {
            // 触感永远不能影响主流程
        }
    }

    /**
     * 用极短的振动模拟「强度缩放」。
     *
     * ⚠️ 说明：`performHapticFeedback` 的波形完全由系统决定，**无法调强度**。
     *    所以强度 <100 时，我们用 Vibrator 施加一个额外的短振，
     *    用振幅（amplitude）实现「更轻/更重」的感知。
     *
     * ⚠️ 这需要 VIBRATE 权限吗？
     *    需要的。所以**只有用户主动把强度调到 <100 才会触发**，
     *    且我们已在 Manifest 声明该权限（无害权限）。
     *    如果用户不想给权限，保持 100 即可完全走 performHapticFeedback 路径。
     */
    private fun amplify(context: Context, strength: Int) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                mgr?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            // ⚠️ 必须显式判空 —— 上面是可空类型，直接调 .vibrate 编译不过
            //    （实测报 "only safe (?.) or non-null asserted (!!.) calls are allowed"）
            if (vibrator == null) return

            if (!vibrator.hasVibrator()) return

            // 时长 8ms（很短，接近 CLOCK_TICK 的体感），振幅按比例
            val amp = (strength.coerceIn(HapticStore.MIN_STRENGTH, HapticStore.MAX_STRENGTH) * 255 / 100)
                .coerceIn(1, 255)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(8L, amp),
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH),
                )
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(8L, amp))
            }
        } catch (_: Throwable) {
        }
    }
}
