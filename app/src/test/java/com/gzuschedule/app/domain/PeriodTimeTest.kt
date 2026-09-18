package com.gzuschedule.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 节次→时间映射测试（ADR-014：改用本校真实作息）。
 *
 * 数据来源：同校官方小程序截图（用户提供）。
 * 此前用「通用作息」猜（1-2 节写成 08:00-08:45），与本校完全不符 —— 已整表替换。
 *
 * 本校作息：每 2 小节连排成 1 个大节，共 8 个大节 / 16 小节。
 */
class PeriodTimeTest {

    @Test
    fun `第 1-2 节为 09 00 到 10 20`() {
        assertEquals("09:00", PeriodTime.startOf(1))
        assertEquals("10:20", PeriodTime.endOf(2))
        assertEquals("09:00 - 10:20", PeriodTime.range(1, 2))
    }

    @Test
    fun `第 3-4 节为 10 40 到 12 00（截图实证）`() {
        assertEquals("10:40 - 12:00", PeriodTime.range(3, 4))
    }

    @Test
    fun `第 5-6 节为 12 30 到 13 50`() {
        assertEquals("12:30 - 13:50", PeriodTime.range(5, 6))
    }

    @Test
    fun `第 9-10 节为 15 30 到 16 50（截图实证）`() {
        assertEquals("15:30 - 16:50", PeriodTime.range(9, 10))
    }

    @Test
    fun `第 13-14 节为 19 00 到 20 20（截图实证）`() {
        assertEquals("19:00 - 20:20", PeriodTime.range(13, 14))
    }

    @Test
    fun `第 15-16 节为 20 30 到 21 50`() {
        assertEquals("20:30 - 21:50", PeriodTime.range(15, 16))
    }

    @Test
    fun `同一大节内的小节应映射到相同时间段`() {
        // 第 3 节与第 4 节同属一个大节
        assertEquals(PeriodTime.startOf(3), PeriodTime.startOf(4))
        assertEquals(PeriodTime.endOf(3), PeriodTime.endOf(4))
        // 第 1 与第 2
        assertEquals(PeriodTime.startOf(1), PeriodTime.startOf(2))
    }

    @Test
    fun `全部 8 个大节都有时间`() {
        for (small in intArrayOf(1, 3, 5, 7, 9, 11, 13, 15)) {
            assertTrue("第 $small 节应有开始时间", PeriodTime.startOf(small).isNotBlank())
            assertTrue("第 $small 节应有结束时间", PeriodTime.endOf(small).isNotBlank())
        }
    }

    @Test
    fun `时间随节次递增`() {
        val starts = intArrayOf(1, 3, 5, 7, 9, 11, 13, 15).map { PeriodTime.startOf(it) }
        assertEquals("应已按时间升序", starts.sorted(), starts)
    }

    @Test
    fun `越界节次返回空串不崩溃`() {
        assertEquals("", PeriodTime.startOf(0))
        assertEquals("", PeriodTime.startOf(99))
        assertEquals("", PeriodTime.startOf(-1))
        assertEquals("", PeriodTime.range(99, 100))
    }

    @Test
    fun `节次文案格式`() {
        assertEquals("3-4 节", PeriodTime.periodsLabel(3, 4))
        assertEquals("第 5 节", PeriodTime.periodsLabel(5, 5))
    }

    @Test
    fun `用户真实课表的节次都有时间`() {
        // 截图里出现的真实节次组合
        val pairs = listOf(1 to 2, 3 to 4, 5 to 6, 7 to 8, 9 to 10, 11 to 12, 13 to 14, 15 to 16)
        for ((s, e) in pairs) {
            val r = PeriodTime.range(s, e)
            assertTrue("第 $s-$e 节应有时间，实际 '$r'", r.isNotBlank())
        }
    }

    @Test
    fun `不再是旧通用作息`() {
        // 回归防护：旧表把 1-2 节写成 08:00-08:45，必须已被替换
        assertTrue("不应再出现 08:00", PeriodTime.startOf(1) != "08:00")
        assertTrue("不应再出现 08:45", PeriodTime.endOf(1) != "08:45")
    }
}
