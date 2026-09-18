package com.gzuschedule.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * [UpcomingExamFilter] 单测（ADR-049）。
 *
 * ⚠️ 用户要求「有考试的话提前一个月显示」—— 窗口边界是核心行为，
 *    错一天就会漏提醒或多显示，必须测。
 */
class UpcomingExamFilterTest {

    private val today = LocalDate.of(2026, 9, 18)

    @Test
    fun `窗口是 30 天`() {
        assertEquals(30L, UpcomingExamFilter.WINDOW_DAYS)
    }

    @Test
    fun `今天考试算近期`() {
        assertTrue(UpcomingExamFilter.isUpcoming(today, today))
    }

    @Test
    fun `第 30 天仍算近期（边界含）`() {
        assertTrue(UpcomingExamFilter.isUpcoming(today.plusDays(30), today))
    }

    @Test
    fun `第 31 天不算（窗口外）`() {
        assertFalse(UpcomingExamFilter.isUpcoming(today.plusDays(31), today))
    }

    @Test
    fun `已过去的考试不算`() {
        assertFalse(UpcomingExamFilter.isUpcoming(today.minusDays(1), today))
    }

    @Test
    fun `无日期的考试不算近期`() {
        assertFalse(UpcomingExamFilter.isUpcoming(null, today))
    }

    @Test
    fun `倒计时文案 —— 今天 明天 N天后`() {
        assertEquals("今天", UpcomingExamFilter.countdownText(today, today))
        assertEquals("明天", UpcomingExamFilter.countdownText(today.plusDays(1), today))
        assertEquals("3 天后", UpcomingExamFilter.countdownText(today.plusDays(3), today))
        assertEquals("已结束", UpcomingExamFilter.countdownText(today.minusDays(2), today))
        assertNull(UpcomingExamFilter.countdownText(null, today))
    }

    @Test
    fun `日期解析容错 —— 斜杠与空值`() {
        assertEquals(today, UpcomingExamFilter.parseDate("2026-09-18"))
        assertEquals(today, UpcomingExamFilter.parseDate("2026/09/18"))
        assertEquals(today, UpcomingExamFilter.parseDate("  2026-09-18  "))
        assertNull(UpcomingExamFilter.parseDate(null))
        assertNull(UpcomingExamFilter.parseDate(""))
        assertNull(UpcomingExamFilter.parseDate("待定"))
    }
}
