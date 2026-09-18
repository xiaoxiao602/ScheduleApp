package com.gzuschedule.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [CourseCountdown] 单测（ADR-049）。
 *
 * ⚠️ 这是**数据/逻辑正确性**测试（用户要求只有这类才写单测）——
 *    文案格式与边界条件错了会直接误导用户，值得测。
 */
class CourseCountdownTest {

    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 9, 18, h, m)

    @Test
    fun `用户给的样例 —— 3h40分`() {
        // 09:00 上课，现在 05:20
        assertEquals("距上课 3h40分", CourseCountdown.text(at(5, 20), at(9, 0)))
    }

    @Test
    fun `整小时不带分`() {
        assertEquals("距上课 3h", CourseCountdown.text(at(6, 0), at(9, 0)))
    }

    @Test
    fun `不足一小时显示分钟`() {
        assertEquals("距上课 40 分钟", CourseCountdown.text(at(8, 20), at(9, 0)))
        assertEquals("距上课 1 分钟", CourseCountdown.text(at(8, 59), at(9, 0)))
    }

    @Test
    fun `不足一分钟提示马上开始`() {
        // 8:59:30 → 9:00:00，差 30 秒
        val now30 = LocalDateTime.of(2026, 9, 18, 8, 59, 30)
        assertEquals("马上开始", CourseCountdown.text(now30, at(9, 0)))
        // 8:59:50 → 9:00:00，差 10 秒
        val now10 = LocalDateTime.of(2026, 9, 18, 8, 59, 50)
        assertEquals("马上开始", CourseCountdown.text(now10, at(9, 0)))
    }

    @Test
    fun `已开课返回 null（不显示倒计时）`() {
        assertNull(CourseCountdown.text(at(9, 0), at(9, 0)))
        assertNull(CourseCountdown.text(at(10, 0), at(9, 0)))
    }

    @Test
    fun `跨天按小时算（13 小时比 0 天更有用）`() {
        // 今天 20:00 → 明天 09:00 = 13 小时
        val now = LocalDateTime.of(2026, 9, 18, 20, 0)
        val start = LocalDateTime.of(2026, 9, 19, 9, 0)
        assertEquals("距上课 13h", CourseCountdown.text(now, start))
    }

    @Test
    fun `满 24 小时才按天显示`() {
        val now = LocalDateTime.of(2026, 9, 18, 8, 0)
        val start = LocalDateTime.of(2026, 9, 19, 9, 0)   // 25 小时
        assertEquals("距上课 1 天", CourseCountdown.text(now, start))
    }

    @Test
    fun `startOf 能从节次推出开课时刻（第9节 15-30）`() {
        val t = CourseCountdown.startOf(LocalDate.of(2026, 9, 18), 9)
        assertEquals(LocalDateTime.of(2026, 9, 18, 15, 30), t)
    }

    @Test
    fun `startOf 对越界节次返回 null`() {
        assertNull(CourseCountdown.startOf(LocalDate.of(2026, 9, 18), 999))
    }
}
