package com.gzuschedule.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dock 外观参数边界测试（ADR-033）。
 *
 * ⚠️ 这些是**真单元测试**：只测**边界与夹取逻辑**，
 *    不依赖 Android 的 SharedPreferences —— 为此把上下限与夹取规则
 *    抽成可独立验证的纯逻辑（见 clamp 测试）。
 *
 * 为什么必须测：
 *   用户可自由拖动滑块，若不做上下限约束，
 *   高度 0 → Dock 消失且点不到；圆角过大 → 渲染成异常形状。
 *   这类"能被用户拖坏"的参数必须有硬边界。
 */
class DockTuningBoundsTest {

    /** 与 DockTuningStore 的夹取语义一致（coerceIn）。 */
    private fun clamp(v: Int, lo: Int, hi: Int) = v.coerceIn(lo, hi)

    @Test
    fun `高度下限被夹到 40`() {
        assertEquals(40, clamp(0, DockTuningStore.MIN_HEIGHT, DockTuningStore.MAX_HEIGHT))
        assertEquals(40, clamp(-100, DockTuningStore.MIN_HEIGHT, DockTuningStore.MAX_HEIGHT))
    }

    @Test
    fun `高度上限被夹到 96`() {
        assertEquals(96, clamp(999, DockTuningStore.MIN_HEIGHT, DockTuningStore.MAX_HEIGHT))
    }

    @Test
    fun `高度默认值在合法范围内`() {
        val d = DockTuningStore.DEF_HEIGHT
        assertTrue("默认高度必须在上下限内", d in DockTuningStore.MIN_HEIGHT..DockTuningStore.MAX_HEIGHT)
    }

    @Test
    fun `滑块高度默认值合法`() {
        val d = DockTuningStore.DEF_SLIDER_H
        assertTrue(d in DockTuningStore.MIN_SLIDER_H..DockTuningStore.MAX_SLIDER_H)
    }

    @Test
    fun `滑块高度不得超出 Dock 高度上限`() {
        // 滑块比 Dock 还高会导致溢出，必须能被约束
        assertTrue(
            "滑块上限应 <= Dock 上限",
            DockTuningStore.MAX_SLIDER_H <= DockTuningStore.MAX_HEIGHT,
        )
    }

    @Test
    fun `文字大小默认值在合法范围内`() {
        // ⚠️ ADR-044：不再写死具体值 —— 默认值会随用户偏好调整，
        //    断言"在范围内"才是这条测试真正要保证的不变量。
        val d = DockTuningStore.DEF_TEXT
        assertTrue(
            "DEF_TEXT=$d 应在 ${DockTuningStore.MIN_TEXT}..${DockTuningStore.MAX_TEXT}",
            d in DockTuningStore.MIN_TEXT..DockTuningStore.MAX_TEXT,
        )
        assertTrue("文字应可读（>=10sp）", d / 10f >= 10f)
    }

    @Test
    fun `文字大小上下限正确`() {
        assertEquals(90, clamp(0, DockTuningStore.MIN_TEXT, DockTuningStore.MAX_TEXT))
        assertEquals(200, clamp(999, DockTuningStore.MIN_TEXT, DockTuningStore.MAX_TEXT))
    }

    @Test
    fun `位置与宽度上下限正确`() {
        // ⚠️ ADR-042：模糊已取消，改为校验新增的「位置/宽度」字段
        assertEquals(0, clamp(-5, DockTuningStore.MIN_BOTTOM_OFFSET, DockTuningStore.MAX_BOTTOM_OFFSET))
        assertEquals(120, clamp(999, DockTuningStore.MIN_BOTTOM_OFFSET, DockTuningStore.MAX_BOTTOM_OFFSET))
        assertEquals(0, clamp(-5, DockTuningStore.MIN_DOCK_WIDTH, DockTuningStore.MAX_DOCK_WIDTH))
        assertEquals(120, clamp(999, DockTuningStore.MIN_DOCK_WIDTH, DockTuningStore.MAX_DOCK_WIDTH))
    }

    @Test
    fun `所有默认值都不触发夹取（零回归保证）`() {
        data class Pair(val name: String, val def: Int, val lo: Int, val hi: Int)
        val all = listOf(
            Pair("thickness", DockTuningStore.DEF_HEIGHT, DockTuningStore.MIN_HEIGHT, DockTuningStore.MAX_HEIGHT),
            Pair("corner", DockTuningStore.DEF_CORNER, DockTuningStore.MIN_CORNER, DockTuningStore.MAX_CORNER),
            Pair("elevation", DockTuningStore.DEF_ELEVATION, DockTuningStore.MIN_ELEVATION, DockTuningStore.MAX_ELEVATION),
            Pair("bottomOffset", DockTuningStore.DEF_BOTTOM_OFFSET, DockTuningStore.MIN_BOTTOM_OFFSET, DockTuningStore.MAX_BOTTOM_OFFSET),
            Pair("dockWidth", DockTuningStore.DEF_DOCK_WIDTH, DockTuningStore.MIN_DOCK_WIDTH, DockTuningStore.MAX_DOCK_WIDTH),
            Pair("sliderH", DockTuningStore.DEF_SLIDER_H, DockTuningStore.MIN_SLIDER_H, DockTuningStore.MAX_SLIDER_H),
            Pair("sliderCorner", DockTuningStore.DEF_SLIDER_CORNER, DockTuningStore.MIN_SLIDER_CORNER, DockTuningStore.MAX_SLIDER_CORNER),
            Pair("sliderW", DockTuningStore.DEF_SLIDER_W, DockTuningStore.MIN_SLIDER_W, DockTuningStore.MAX_SLIDER_W),
            Pair("text", DockTuningStore.DEF_TEXT, DockTuningStore.MIN_TEXT, DockTuningStore.MAX_TEXT),
            Pair("animMs", DockTuningStore.DEF_ANIM_MS, DockTuningStore.MIN_ANIM_MS, DockTuningStore.MAX_ANIM_MS),
            Pair("damping", DockTuningStore.DEF_DAMPING, DockTuningStore.MIN_DAMPING, DockTuningStore.MAX_DAMPING),
        )
        all.forEach { p ->
            val got = clamp(p.def, p.lo, p.hi)
            assertEquals("默认值 ${p.name} 被夹取了 —— 说明默认值超界，会造成启动即变样", p.def, got)
        }
    }

    @Test
    fun `圆角上限不超过高度上限的一半（避免渲染异常）`() {
        // 圆角 > 高度/2 时，圆角矩形退化成胶囊，观感突变
        assertTrue(
            "MAX_CORNER(${DockTuningStore.MAX_CORNER}) 应 <= MAX_HEIGHT/2(${DockTuningStore.MAX_HEIGHT / 2})",
            DockTuningStore.MAX_CORNER <= DockTuningStore.MAX_HEIGHT / 2,
        )
    }

    @Test
    fun `默认圆角不超过默认厚度的一半`() {
        // ⚠️ 几何硬约束：圆角 > 高度/2 时，两端圆弧会连起来，
        //    Dock 渲染成胶囊形（不再是圆角矩形）。
        //    ⚠️ ADR-044：用户选的 30dp 圆角配 58dp 厚度正好越界 1dp，
        //       故圆角调整为 28dp（肉眼与 30 无差）。
        assertTrue(
            "DEF_CORNER=${DockTuningStore.DEF_CORNER} 应 <= " +
                "DEF_HEIGHT/2=${DockTuningStore.DEF_HEIGHT / 2}",
            DockTuningStore.DEF_CORNER <= DockTuningStore.DEF_HEIGHT / 2,
        )
    }

    @Test
    fun `默认厚度足够容纳滑块与内边距`() {
        // Dock 必须比滑块高出一截，否则滑块会贴边
        assertTrue(
            "DEF_HEIGHT 应 > DEF_SLIDER_H",
            DockTuningStore.DEF_HEIGHT > DockTuningStore.DEF_SLIDER_H,
        )
    }
}
