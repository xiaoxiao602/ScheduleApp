package com.gzuschedule.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 学期周次计算测试（ADR-011）。
 *
 * ⚠️ 回归防护：用户实际数据 ——
 *   第一周星期一 = 2026-08-31
 *   查课表之日   = 2026-09-18（周五）
 * 正确结果应为 **第 3 周**。
 * 旧实现算出「第 1 周」，把当天课程过滤成 0 门（真机复现的 bug）。
 */
class WeekCalculatorTest {

    private val firstMonday = LocalDate.of(2026, 8, 31)

    @Test
    fun `首日即第一周周一`() {
        assertEquals(1, WeekCalculator.weekOf(firstMonday, firstMonday))
    }

    @Test
    fun `同周内每一天都是第一周`() {
        // 周一 -> 周日 全部应为第 1 周
        for (offset in 0..6) {
            assertEquals(
                "第 ${offset} 天应为第 1 周",
                1,
                WeekCalculator.weekOf(firstMonday, firstMonday.plusDays(offset.toLong())),
            )
        }
    }

    @Test
    fun `第二周周一为第二周`() {
        assertEquals(2, WeekCalculator.weekOf(firstMonday, firstMonday.plusDays(7)))
    }

    /**
     * ⚠️ 真机 bug 的核心用例：
     * 2026-08-31 起算，2026-09-18 是第 3 周周五。
     */
    @Test
    fun `用户实际日期 2026-09-18 应为第 3 周`() {
        val target = LocalDate.of(2026, 9, 18)
        assertEquals(3, WeekCalculator.weekOf(firstMonday, target))
    }

    @Test
    fun `2026-09-18 确实是周五`() {
        // 若这条挂了，说明上面用例的日期假设错了
        assertEquals(5, LocalDate.of(2026, 9, 18).dayOfWeek.value)
    }

    @Test
    fun `第三周内各天都是第三周`() {
        val monday3 = firstMonday.plusWeeks(2) // 2026-09-14
        for (offset in 0..6) {
            assertEquals(
                3,
                WeekCalculator.weekOf(firstMonday, monday3.plusDays(offset.toLong())),
            )
        }
    }

    @Test
    fun `早于起始日按第 1 周处理（不返回 0 或负数）`() {
        assertEquals(1, WeekCalculator.weekOf(firstMonday, firstMonday.minusDays(1)))
        assertEquals(1, WeekCalculator.weekOf(firstMonday, LocalDate.of(2020, 1, 1)))
    }

    @Test
    fun `超过 30 周按上限截断`() {
        val far = firstMonday.plusWeeks(60)
        assertEquals(30, WeekCalculator.weekOf(firstMonday, far))
    }

    @Test
    fun `mondayOf 能把任意日期归到本周周一`() {
        // 2026-09-18 周五 -> 本周一 2026-09-14
        assertEquals(
            LocalDate.of(2026, 9, 14),
            WeekCalculator.mondayOf(LocalDate.of(2026, 9, 18)),
        )
        // 周日应归到【当周】周一，而非跨到下周
        assertEquals(
            LocalDate.of(2026, 9, 14),
            WeekCalculator.mondayOf(LocalDate.of(2026, 9, 20)),
        )
        // 周一归到自身
        assertEquals(
            LocalDate.of(2026, 9, 14),
            WeekCalculator.mondayOf(LocalDate.of(2026, 9, 14)),
        )
    }

    @Test
    fun `校验第一周起始日必须是周一`() {
        assertTrue(WeekCalculator.isValidFirstMonday(LocalDate.of(2026, 8, 31)))
        assertFalse(WeekCalculator.isValidFirstMonday(LocalDate.of(2026, 9, 1)))
        assertFalse(WeekCalculator.isValidFirstMonday(LocalDate.of(2026, 9, 6)))
    }

    @Test
    fun `跨年的学期也能正确计算`() {
        // 秋季学期常跨年：2026-08-31 起，2027-02-08 应在第 24 周内
        val target = LocalDate.of(2027, 2, 8)
        val week = WeekCalculator.weekOf(firstMonday, target)
        assertTrue("应落在合理周次范围，实际 $week", week in 20..30)
    }
}
