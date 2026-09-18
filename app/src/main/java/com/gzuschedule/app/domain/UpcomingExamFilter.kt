package com.gzuschedule.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 近期考试筛选（ADR-049）。
 *
 * ⚠️ 用户要求：「在这里显示考试信息 没有就写近期无考试 有考试的话提前一个月显示」。
 *
 * 语义：只把**未来 [WINDOW_DAYS] 天内**的考试算作"近期"；
 *       超出窗口的不显示（首页只显示「近期无考试」）。
 *       —— 这样首页不会被一学期后的考试占满。
 *
 * 纯函数，便于单测（"今天"由调用方注入）。
 */
object UpcomingExamFilter {

    /** 提前显示的天数窗口：一个月按 30 天计。 */
    const val WINDOW_DAYS = 30L

    /**
     * 该考试是否算「近期」。
     *
     * @param examDate 考试日期；null 表示教务未给日期 —— 这类无法判断，**不算近期**
     *                 （否则会显示"某天有考试"却没有日期，反而困惑）。
     */
    fun isUpcoming(examDate: LocalDate?, today: LocalDate): Boolean {
        if (examDate == null) return false
        val days = ChronoUnit.DAYS.between(today, examDate)
        // 0 = 今天；负数 = 已过期
        return days in 0..WINDOW_DAYS
    }

    /**
     * 距考试还有几天。
     * @return 0 表示今天；负数为已过；null 表示无日期
     */
    fun daysLeft(examDate: LocalDate?, today: LocalDate): Long? {
        if (examDate == null) return null
        return ChronoUnit.DAYS.between(today, examDate)
    }

    /** 倒计时文案，如「今天」「明天」「3 天后」。 */
    fun countdownText(examDate: LocalDate?, today: LocalDate): String? {
        val d = daysLeft(examDate, today) ?: return null
        return when {
            d < 0 -> "已结束"
            d == 0L -> "今天"
            d == 1L -> "明天"
            else -> "$d 天后"
        }
    }

    /** 解析教务返回的日期串（"2026-01-15" 或 "2026/01/15"）。 */
    fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.trim().replace('/', '-')
        return runCatching { LocalDate.parse(normalized) }.getOrNull()
    }
}
