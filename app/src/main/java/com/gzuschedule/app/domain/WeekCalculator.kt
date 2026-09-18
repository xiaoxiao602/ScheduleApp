package com.gzuschedule.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 学期周次计算（ADR-011）。
 *
 * ⚠️ 背景：首页「第 N 周」曾长期不准。
 * 旧实现（`guessWeek`）用「课程数据里最早的 startWeek」当上限 —— 这是
 * 保守估算，必然偏小：比如实际第 3 周时，若最早课程从第 1 周开始，
 * 就会算出第 1 周，导致「今天有课」被过滤成「0 门」。
 *
 * 正解：本地保存「第一周星期一」的日期，用它与今天的差值精确计算。
 */
object WeekCalculator {

    /** 一学期最多按 30 周算，超出视为数据异常。 */
    private const val MAX_WEEK = 30

    /**
     * 由「第一周星期一」与目标日期算周次。
     *
     * @param firstMonday 第 1 周的星期一（学期起始基准）
     * @param date 要计算的日期，默认今天
     * @return 周次（1 起）；早于起始日返回 1；超出 [MAX_WEEK] 按上限截断
     */
    fun weekOf(firstMonday: LocalDate, date: LocalDate = LocalDate.now()): Int {
        val days = ChronoUnit.DAYS.between(firstMonday, date)
        // 同一周内（周一到周日）算同一周：向下取整即可，
        // 因为差值第 0..6 天都属于第 1 周。
        // ⚠️ days 是 Long，除法与 floorDiv 都要用 Long 版本，否则类型不匹配。
        val week = Math.floorDiv(days, 7L) + 1L
        return week.coerceIn(1L, MAX_WEEK.toLong()).toInt()
    }

    /**
     * 从任意日期反推该周的星期一。
     * 用 [java.time.DayOfWeek] 的 ISO 值（周一=1 ... 周日=7）。
     */
    fun mondayOf(date: LocalDate): LocalDate =
        date.minusDays((date.dayOfWeek.value - 1).toLong())

    /**
     * 校验一个日期是否适合作为「第一周星期一」。
     * 必须是星期一，否则周次计算会整体偏移。
     */
    fun isValidFirstMonday(date: LocalDate): Boolean = date.dayOfWeek.value == 1
}
