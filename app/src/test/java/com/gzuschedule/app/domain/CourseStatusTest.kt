package com.gzuschedule.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * [CourseStatus] 单测（ADR-051）。
 *
 * ⚠️ 用户要求的「上完的课显示蓝勾」依赖这个判定 ——
 *    边界（正在上 vs 已上完）错了会同时影响进度条与对勾，属数据正确性。
 *
 * 作息参考（ADR-014）：第 3-4 节 = 10:40-12:00。
 */
class CourseStatusTest {

    private val day = LocalDate.of(2026, 9, 18)

    private fun at(h: Int, m: Int) = LocalDateTime.of(day, java.time.LocalTime.of(h, m))

    @Test
    fun `还没开始`() {
        val s = CourseStatus.of(day, 3, 4, at(9, 0))
        assertEquals(CourseStatus.State.UPCOMING, s)
        assertFalse(CourseStatus.isDone(day, 3, 4, at(9, 0)))
    }

    @Test
    fun `刚开始那一刻算进行中`() {
        assertEquals(CourseStatus.State.ONGOING, CourseStatus.of(day, 3, 4, at(10, 40)))
    }

    @Test
    fun `课中间算进行中`() {
        assertEquals(CourseStatus.State.ONGOING, CourseStatus.of(day, 3, 4, at(11, 20)))
    }

    @Test
    fun `正好下课那一刻算已上完（与进度条语义一致）`() {
        assertEquals(CourseStatus.State.DONE, CourseStatus.of(day, 3, 4, at(12, 0)))
        assertTrue(CourseStatus.isDone(day, 3, 4, at(12, 0)))
    }

    @Test
    fun `下课之后算已上完`() {
        assertEquals(CourseStatus.State.DONE, CourseStatus.of(day, 3, 4, at(12, 1)))
        assertEquals(CourseStatus.State.DONE, CourseStatus.of(day, 3, 4, at(23, 59)))
    }

    @Test
    fun `用户截图里的场景 —— 马克思 3-4 节 16点看已上完`() {
        assertTrue(CourseStatus.isDone(day, 3, 4, at(16, 4)))
    }

    @Test
    fun `越界节次安全回落为未开始`() {
        assertEquals(CourseStatus.State.UPCOMING, CourseStatus.of(day, 999, 999, at(12, 0)))
    }
}
