package com.gzuschedule.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 假日日历单测（ADR-060）。
 *
 * ⚠️ 这里测的是**数据正确性**，属于既定规则里"该写单测"的范畴：
 *    假日误判会让学生缺课，代价高，必须有测试兜住。
 *
 * ⚠️ 断言刻意不写死具体日期值 —— 只验证「区间展开」「查询命中」
 *    这类逻辑不变量，这样以后改默认假日数据不会把测试改红。
 */
class HolidayCalendarTest {

    private val oct1 = LocalDate.of(2026, 10, 1)
    private val oct7 = LocalDate.of(2026, 10, 7)

    /** 区间展开：1 号到 7 号每一天都应命中。 */
    @Test
    fun `holiday range expands to every day`() {
        val days = (0..6).map { HolidayDay(oct1.plusDays(it.toLong()), "国庆节") }
        val cal = HolidayCalendar(days)

        assertTrue(cal.isHoliday(oct1))
        assertTrue(cal.isHoliday(oct1.plusDays(3)))
        assertTrue(cal.isHoliday(oct7))
        assertFalse(cal.isHoliday(oct1.minusDays(1)))
        assertFalse(cal.isHoliday(oct7.plusDays(1)))
    }

    /** 空日历：任何日期都不是假日，且不抛异常。 */
    @Test
    fun `empty calendar reports no holidays`() {
        val cal = HolidayCalendar.EMPTY
        assertTrue(cal.isEmpty)
        assertFalse(cal.isHoliday(oct1))
        assertNull(cal.nameOf(oct1))
    }

    /** 周边界：周一和周日都应被包含，且 dayOfWeek 正确。 */
    @Test
    fun `week scan covers monday through sunday`() {
        // 2026-09-28 是周一
        val monday = LocalDate.of(2026, 9, 28)
        assertEquals(1, monday.dayOfWeek.value)

        // 该周的周四(10/1)与周五(10/2)放假
        val cal = HolidayCalendar(
            listOf(
                HolidayDay(monday.plusDays(3), "国庆节"),
                HolidayDay(monday.plusDays(4), "国庆节"),
            ),
        )
        val found = cal.holidaysOfWeek(monday)

        assertEquals(setOf(4, 5), found.keys)
        assertEquals("国庆节", found[4])
        // 周一/周二不是假日，不能出现
        assertFalse(found.containsKey(1))
        assertFalse(found.containsKey(2))
    }

    /** JSON 往返：序列化再读回来，内容必须一致。 */
    @Test
    fun `json round trip preserves holidays`() {
        val original = listOf(
            HolidayDay(oct1, "国庆节"),
            HolidayDay(LocalDate.of(2027, 1, 1), "元旦"),
        )
        val cal = HolidayCalendar.fromJson(HolidayCalendar.toJson(original))

        assertTrue(cal.isHoliday(oct1))
        assertEquals("国庆节", cal.nameOf(oct1))
        assertEquals("元旦", cal.nameOf(LocalDate.of(2027, 1, 1)))
    }

    /**
     * ⚠️ 关键防御：损坏的 JSON **不能**让 App 崩，也不能误判成假日。
     *
     * 这是整个假期功能的安全底线 —— 上不了课比看不到假日标记严重得多。
     */
    @Test
    fun `corrupt json degrades to empty instead of throwing`() {
        listOf(null, "", "   ", "not json", "{", "[{\"date\":\"bad\"}]").forEach { bad ->
            val cal = HolidayCalendar.fromJson(bad)
            assertEquals(
                "输入 [$bad] 应退化为空日历",
                true,
                cal.isEmpty,
            )
        }
    }

    /** 非法日期（2 月 30 日）应被跳过，不会污染日历。 */
    @Test
    fun `invalid date entries are skipped`() {
        val json = """[{"date":"2026-02-30","name":"不存在"},{"date":"2026-10-01","name":"国庆节"}]"""
        val cal = HolidayCalendar.fromJson(json)

        assertTrue(cal.isHoliday(oct1))
        assertEquals(1, (1..31).count { cal.isHoliday(LocalDate.of(2026, 2, it)) })
    }
}
