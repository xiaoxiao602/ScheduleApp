package com.gzuschedule.app.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SpringInterpolator] 单测（ADR-056）。
 *
 * ⚠️ 这是**动画物理正确性**测试 —— 用户要的「Q弹」全靠这条曲线：
 *    曲线错了会表现为"弹过头""不收敛""不动"。
 *    纯数学、可测，值得测。
 *
 * ⚠️ ADR-090：液体形变功能已移除后，本文件只测 `getInterpolation()`。
 */
class SpringInterpolatorTest {

    @Test
    fun `起点为 0、终点为 1`() {
        val s = SpringInterpolator(160f)
        assertEquals(0f, s.getInterpolation(0f), 1e-4f)
        assertEquals(1f, s.getInterpolation(1f), 1e-4f)
    }

    @Test
    fun `越界输入被夹住（不产生荒谬值）`() {
        val s = SpringInterpolator(160f)
        assertEquals(0f, s.getInterpolation(-0.5f), 1e-4f)
        assertEquals(1f, s.getInterpolation(1.5f), 1e-4f)
    }

    @Test
    fun `中途有过冲（值超过 1）—— 这就是「Q弹」`() {
        val s = SpringInterpolator(220f)   // 大阻尼 = 强回弹
        val overshoots = (1..99).map { s.getInterpolation(it / 100f) }
        assertTrue(
            "应在过程中出现 >1 的值（过冲），实际最大 ${overshoots.max()}",
            overshoots.max() > 1.0f,
        )
    }

    @Test
    fun `小阻尼时几乎不过冲（干脆利落）`() {
        val s = SpringInterpolator(100f)   // 阻尼滑块最小值
        val maxV = (1..99).map { s.getInterpolation(it / 100f) }.max()
        assertTrue("阻尼 100 时过冲应很小，实际 $maxV", maxV < 1.08f)
    }

    @Test
    fun `阻尼越大过冲越明显（映射方向正确）`() {
        fun peak(d: Float) = (1..99).map { SpringInterpolator(d).getInterpolation(it / 100f) }.max()
        val low = peak(100f)
        val high = peak(250f)
        assertTrue("250 的过冲($high) 应大于 100 的($low)", high > low)
    }

    @Test
    fun `曲线大致单调推进（不会卡在中间）`() {
        val s = SpringInterpolator(160f)
        // 后半段应回到 1 附近
        assertEquals(1f, s.getInterpolation(0.95f), 0.05f)
    }

    // ⚠️ ADR-090：原本还有三个测 `normalizedVelocity()` 的用例，
    //    但那套「速度 → 拉伸量」的液体形变功能已整体移除
    //    （用户：「删除这个效果吧 目前做不好」），故一并删除。
}
