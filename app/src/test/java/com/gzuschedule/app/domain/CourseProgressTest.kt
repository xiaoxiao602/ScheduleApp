package com.gzuschedule.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课程进度计算测试（ADR-031）。
 *
 * ⚠️ 这是**真单元测试**（非 ad-hoc 脚本）：
 * CourseProgress 是纯 Kotlin，无 Android 依赖，可在 JVM 直接跑。
 * 「进度条是否显示、走多远」这类逻辑必须靠测试锁定，
 * 不能只靠截图目测。
 */
class CourseProgressTest {

    private val date: LocalDate = LocalDate.of(2026, 9, 18)

    private fun at(h: Int, m: Int) = LocalDateTime.of(date, java.time.LocalTime.of(h, m))

    // ── 基本区间 ──────────────────────────────────────────────

    @Test
    fun `上课前 进度为0 且不在上课中`() {
        val r = CourseProgress.of(date, "09:00", "10:20", at(8, 30))
        assertNotNull(r)
        assertEquals(0f, r!!.fraction, 0.001f)
        assertFalse(r.inProgress)
        assertEquals(0L, r.minutesLeft)
    }

    @Test
    fun `刚好到开始时间 进入上课中`() {
        val r = CourseProgress.of(date, "09:00", "10:20", at(9, 0))
        assertNotNull(r)
        assertTrue(r!!.inProgress)
        assertEquals(0f, r.fraction, 0.001f)
        assertEquals(80L, r.minutesLeft)
    }

    @Test
    fun `上课中途 进度约一半`() {
        // 09:00-10:20 共 80 分钟，09:40 应为 50%
        val r = CourseProgress.of(date, "09:00", "10:20", at(9, 40))
        assertNotNull(r)
        assertTrue(r!!.inProgress)
        assertEquals(0.5f, r.fraction, 0.01f)
        assertEquals(40L, r.minutesLeft)
    }

    @Test
    fun `下课时 进度满 且不再算上课中`() {
        val r = CourseProgress.of(date, "09:00", "10:20", at(10, 20))
        assertNotNull(r)
        // 边界：下课瞬间即结束（半开区间 [start, end)）
        assertFalse(r!!.inProgress)
        assertEquals(1f, r.fraction, 0.001f)
        assertEquals(0L, r.minutesLeft)
    }

    @Test
    fun `下课后 进度保持满`() {
        val r = CourseProgress.of(date, "09:00", "10:20", at(11, 0))
        assertNotNull(r)
        assertFalse(r!!.inProgress)
        assertEquals(1f, r.fraction, 0.001f)
    }

    @Test
    fun `临近下课 进度接近满`() {
        val r = CourseProgress.of(date, "09:00", "10:20", at(10, 19))
        assertNotNull(r)
        assertTrue(r!!.inProgress)
        assertTrue("应接近 1 但未满，实际 ${r.fraction}", r.fraction > 0.97f && r.fraction < 1f)
        assertEquals(1L, r.minutesLeft)
    }

    // ── 本校真实作息（ADR-014 的 8 大节） ─────────────────────

    @Test
    fun `第3-4节 10点40到12点整`() {
        val start = CourseProgress.of(date, "10:40", "12:00", at(10, 40))
        assertTrue(start!!.inProgress)
        assertEquals(80L, start.minutesLeft)

        val mid = CourseProgress.of(date, "10:40", "12:00", at(11, 20))
        assertEquals(0.5f, mid!!.fraction, 0.01f)
    }

    @Test
    fun `第13-14节 晚上19点到20点20`() {
        val r = CourseProgress.of(date, "19:00", "20:20", at(19, 40))
        assertNotNull(r)
        assertTrue(r!!.inProgress)
        assertEquals(0.5f, r.fraction, 0.01f)
    }

    // ── 容错 ──────────────────────────────────────────────────

    @Test
    fun `空时间 返回null`() {
        assertNull(CourseProgress.of(date, "", "10:20", at(9, 30)))
        assertNull(CourseProgress.of(date, "09:00", "", at(9, 30)))
    }

    @Test
    fun `格式错误 返回null而不抛异常`() {
        assertNull(CourseProgress.of(date, "9点", "10:20", at(9, 30)))
        assertNull(CourseProgress.of(date, "0900", "10:20", at(9, 30)))
        assertNull(CourseProgress.of(date, "25:00", "26:00", at(9, 30)))
    }

    @Test
    fun `结束早于开始 返回null`() {
        assertNull(CourseProgress.of(date, "10:00", "09:00", at(9, 30)))
    }

    @Test
    fun `结束等于开始 返回null（零长度课）`() {
        assertNull(CourseProgress.of(date, "09:00", "09:00", at(9, 0)))
    }

    @Test
    fun `时间带空格也能解析`() {
        val r = CourseProgress.of(date, " 09:00 ", " 10:20 ", at(9, 40))
        assertNotNull(r)
        assertEquals(0.5f, r!!.fraction, 0.01f)
    }

    // ── 便捷重载 ──────────────────────────────────────────────

    @Test
    fun `ofToday 用当前日期`() {
        val now = at(9, 40)
        val r = CourseProgress.ofToday("09:00", "10:20", now)
        assertNotNull(r)
        assertEquals(0.5f, r!!.fraction, 0.01f)
    }

    @Test
    fun `fraction 永远在 0到1 之间`() {
        // 极限时刻也不越界
        listOf(at(0, 0), at(12, 0), at(23, 59)).forEach { t ->
            val r = CourseProgress.of(date, "09:00", "10:20", t)
            assertNotNull(r)
            assertTrue("fraction=${r!!.fraction}", r.fraction in 0f..1f)
        }
    }
}
