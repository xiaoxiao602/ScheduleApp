package com.gzuschedule.app.data.local

import android.content.Context
import android.content.SharedPreferences

/**
 * Dock 外观参数（ADR-033）。
 *
 * 用户可以自己调 Dock / 滑块的尺寸，调完持久化，重启仍生效。
 *
 * ⚠️ 为什么放 SharedPreferences 而不是 Room：
 *  这些是**纯 UI 偏好**，不是业务数据；不需要查询/事务，
 *  SharedPreferences 更轻，且改动即时生效（Room 要走协程 + Flow）。
 *
 * ⚠️ 取值范围有硬性上下限（见 companion），避免用户拖出不可用的界面
 *  （例如高度 0 导致 Dock 消失、或圆角大于高度的一半导致渲染异常）。
 */
class DockTuningStore(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---------- Dock 本体 ----------

    /** Dock 高度（dp）。 */
    var dockHeight: Int
        get() = sp.getInt(KEY_HEIGHT, DEF_HEIGHT).coerceIn(MIN_HEIGHT, MAX_HEIGHT)
        set(v) = sp.edit().putInt(KEY_HEIGHT, v.coerceIn(MIN_HEIGHT, MAX_HEIGHT)).apply()

    /** Dock 圆角（dp）。 */
    var dockCorner: Int
        get() = sp.getInt(KEY_CORNER, DEF_CORNER).coerceIn(MIN_CORNER, MAX_CORNER)
        set(v) = sp.edit().putInt(KEY_CORNER, v.coerceIn(MIN_CORNER, MAX_CORNER)).apply()

    /** 投影高度（dp），影响悬浮感。 */
    var dockElevation: Int
        get() = sp.getInt(KEY_ELEVATION, DEF_ELEVATION).coerceIn(MIN_ELEVATION, MAX_ELEVATION)
        set(v) = sp.edit().putInt(KEY_ELEVATION, v.coerceIn(MIN_ELEVATION, MAX_ELEVATION)).apply()

    /** 左右外边距（dp）——Dock 离屏幕边缘的距离。
     *
     * ⚠️ ADR-042：已废弃 —— 职责被 [dockWidth] 取代（宽度独立调节），
     *    全项目零引用，故删除 getter/setter，仅保留常量以免旧 SharedPreferences 键报错。
     */

    // ---------- 滑块 ----------

    /** 滑块高度（dp）。 */
    var sliderHeight: Int
        get() = sp.getInt(KEY_SLIDER_H, DEF_SLIDER_H).coerceIn(MIN_SLIDER_H, MAX_SLIDER_H)
        set(v) = sp.edit().putInt(KEY_SLIDER_H, v.coerceIn(MIN_SLIDER_H, MAX_SLIDER_H)).apply()

    /** 滑块圆角（dp）。 */
    var sliderCorner: Int
        get() = sp.getInt(KEY_SLIDER_CORNER, DEF_SLIDER_CORNER)
            .coerceIn(MIN_SLIDER_CORNER, MAX_SLIDER_CORNER)
        set(v) = sp.edit()
            .putInt(KEY_SLIDER_CORNER, v.coerceIn(MIN_SLIDER_CORNER, MAX_SLIDER_CORNER)).apply()

    /** 滑块比文字多出的宽度（dp，左右合计）。 */
    var sliderExtraWidth: Int
        get() = sp.getInt(KEY_SLIDER_W, DEF_SLIDER_W).coerceIn(MIN_SLIDER_W, MAX_SLIDER_W)
        set(v) = sp.edit().putInt(KEY_SLIDER_W, v.coerceIn(MIN_SLIDER_W, MAX_SLIDER_W)).apply()

    // ---------- 文字 ----------

    /** 标签文字大小（sp × 10，存整数避免浮点精度问题）。 */
    var textSizeTenths: Int
        get() = sp.getInt(KEY_TEXT, DEF_TEXT).coerceIn(MIN_TEXT, MAX_TEXT)
        set(v) = sp.edit().putInt(KEY_TEXT, v.coerceIn(MIN_TEXT, MAX_TEXT)).apply()

    /** 标签文字大小（sp，浮点）。 */
    var textSizeSp: Float
        get() = textSizeTenths / 10f
        set(v) = run { textSizeTenths = (v * 10).toInt() }

    // ---------- 毛玻璃 ----------

    // ⚠️ ADR-042：已删除 blurRadius —— 模糊功能整体取消（ADR-037），
    //    该字段无人读取，属死代码。

    // ---------- 位置 / 宽度（ADR-041） ----------

    /**
     * Dock **距屏幕底部**的距离（dp）。
     *
     * ⚠️ 用户反馈：「dock高度调节指的是 dock 在屏幕上的位置，不是 y 方向的宽度」。
     *    之前 dockHeight 控制的是 Dock 卡片自身的厚度（minimumHeight），
     *    语义完全不对。位置改由本字段控制。
     */
    var dockBottomOffset: Int
        get() = sp.getInt(KEY_BOTTOM_OFFSET, DEF_BOTTOM_OFFSET)
            .coerceIn(MIN_BOTTOM_OFFSET, MAX_BOTTOM_OFFSET)
        set(v) = sp.edit()
            .putInt(KEY_BOTTOM_OFFSET, v.coerceIn(MIN_BOTTOM_OFFSET, MAX_BOTTOM_OFFSET)).apply()

    /**
     * Dock 的**左右宽度**（dp，即距屏幕左右边缘的距离）。
     *
     * ⚠️ 新增（用户要求「宽度加入单独的宽度调节」）：
     *    之前只有 dockSideMargin 控制左右边距，与位置/厚度混在一起语义不清。
     *    现在边距 = (屏幕宽 - dockWidth) / 2，值越大 Dock 越窄。
     */
    var dockWidth: Int
        get() = sp.getInt(KEY_DOCK_WIDTH, DEF_DOCK_WIDTH)
            .coerceIn(MIN_DOCK_WIDTH, MAX_DOCK_WIDTH)
        set(v) = sp.edit()
            .putInt(KEY_DOCK_WIDTH, v.coerceIn(MIN_DOCK_WIDTH, MAX_DOCK_WIDTH)).apply()

    // ---------- 滑块动画（ADR-039） ----------

    /** 滑动时长（ms）。越长越"沉"，越短越干脆。 */
    var animDuration: Int
        get() = sp.getInt(KEY_ANIM_MS, DEF_ANIM_MS)
            .coerceIn(MIN_ANIM_MS, MAX_ANIM_MS)
        set(v) = sp.edit()
            .putInt(KEY_ANIM_MS, v.coerceIn(MIN_ANIM_MS, MAX_ANIM_MS)).apply()

    /**
     * 阻尼强度（过冲量 ×100）。
     *
     * 值越大回弹越明显：100 = 无回弹（线性），135 = 轻微过冲，
     * 200 = 强烈弹跳。用整数存避免浮点精度问题。
     */
    var dampingOvershoot: Int
        get() = sp.getInt(KEY_DAMPING, DEF_DAMPING)
            .coerceIn(MIN_DAMPING, MAX_DAMPING)
        set(v) = sp.edit()
            .putInt(KEY_DAMPING, v.coerceIn(MIN_DAMPING, MAX_DAMPING)).apply()

    /** 过冲量（浮点，供插值器直接使用）。 */
    val dampingFactor: Float
        get() = dampingOvershoot / 100f

    /** 恢复全部默认值。 */
    fun resetAll() {
        sp.edit().clear().apply()
    }

    /** 当前是否有自定义（用于设置页显示"已自定义"提示）。 */
    fun isCustomized(): Boolean = sp.all.isNotEmpty()

    companion object {
        private const val PREFS = "dock_tuning"

        private const val KEY_HEIGHT = "dock_height"
        private const val KEY_CORNER = "dock_corner"
        private const val KEY_ELEVATION = "dock_elevation"
        private const val KEY_SLIDER_H = "slider_height"
        private const val KEY_SLIDER_CORNER = "slider_corner"
        private const val KEY_SLIDER_W = "slider_extra_width"
        private const val KEY_TEXT = "text_size_tenths"
        private const val KEY_BOTTOM_OFFSET = "dock_bottom_offset"
        private const val KEY_DOCK_WIDTH = "dock_width"
        private const val KEY_ANIM_MS = "anim_duration_ms"
        private const val KEY_DAMPING = "damping_overshoot"

        // ⚠️ ADR-056 液体变形
            
        // ⚠️ ADR-044：默认值 = 用户在设置页调好后发来的那组参数。
        //    来源：2026-09-18 用户真机截图（设置页 10 项读数）。
        //    之前每次改动都重设默认，这次以**用户实际调出来的观感**为准。
        const val DEF_HEIGHT = 58          // Dock 厚度
        const val DEF_CORNER = 28          // Dock 圆角（≤厚度/2；用户选 30 会越界 1dp）
        const val DEF_ELEVATION = 8
        const val DEF_SLIDER_H = 48        // 滑块高度
        const val DEF_SLIDER_CORNER = 30   // 滑块圆角（用户实测指定）
        const val DEF_SLIDER_W = 0         // 滑块宽度增量（贴文字）
        const val DEF_TEXT = 160           // 16.0sp
        const val DEF_BOTTOM_OFFSET = 35   // 距屏幕底部
        const val DEF_DOCK_WIDTH = 45      // 左右各留 45dp
        const val DEF_ANIM_MS = 480        // 滑动时长
        const val DEF_DAMPING = 130        // 阻尼回弹 ×100 = 1.30

        // ⚠️ 上下限是**硬约束**，防止拖出不可用界面：
        //    高度太小 → Dock 不可点；圆角 > 高度/2 → 渲染成胶囊或异常。
        const val MIN_HEIGHT = 40
        const val MAX_HEIGHT = 96
        const val MIN_CORNER = 0
        const val MAX_CORNER = 32
        const val MIN_ELEVATION = 0
        const val MAX_ELEVATION = 24
        const val MIN_SLIDER_H = 24
        const val MAX_SLIDER_H = 60
        const val MIN_SLIDER_CORNER = 0
        const val MAX_SLIDER_CORNER = 40   // ⚠️ 放宽：默认已设 30，且滑块高度可达 60（半高 30）
        const val MIN_SLIDER_W = 0
        const val MAX_SLIDER_W = 40
        const val MIN_TEXT = 90           // 9.0sp
        const val MAX_TEXT = 200          // 20.0sp
        // 动画：200ms 以下太急促，800ms 以上显得迟钝
        const val MIN_ANIM_MS = 200
        const val MAX_ANIM_MS = 800
        // 阻尼：100 = 无过冲（纯缓出），250 = 强烈弹跳
        const val MIN_DAMPING = 100
        const val MAX_DAMPING = 250

        // ⚠️ ADR-056 液体变形默认值
        //   拉伸 22% + 压缩 30% —— 实测观感接近 iOS/小米的"Q弹"，
        //   再大就像橡皮筋了。

        // ⚠️ ADR-041：距底部 0~120dp；宽度用「左右边距」表示，0~120dp
        const val MIN_BOTTOM_OFFSET = 0
        const val MAX_BOTTOM_OFFSET = 120
        const val MIN_DOCK_WIDTH = 0
        const val MAX_DOCK_WIDTH = 120
    }
}
