package com.gzuschedule.app.domain

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 距上课的倒计时文案（ADR-049）。
 *
 * ⚠️ 用户要求：「课程那个卡片 加上距离上课的时间比如 距离上课3h40分钟」。
 *
 * 设计为**纯函数**，输入"现在"和"开课时刻"，输出文案 —— 这样能单测，
 * 且不依赖系统时钟（测试可注入固定时间）。
 *
 * 文案规则：
 *   * > 1 天      →「距上课 3 天」
 *   * ≥ 1 小时    →「距上课 3h40分」（用户给的样例格式）
 *   * < 1 小时    →「距上课 40 分钟」
 *   * < 1 分钟    →「马上开始」
 *   * 已开课      → null（不显示倒计时）
 */
object CourseCountdown {

    /** 单个数字上限：超过就不显示分钟（"8 天"而非"8 天 3 小时"）。 */
    private const val MINUTES_PER_HOUR = 60

    /**
     * @param now      当前时刻
     * @param start    开课时刻
     * @return 倒计时文案；若已开课或时间无效，返回 null
     */
    fun text(now: LocalDateTime, start: LocalDateTime): String? {
        val d = Duration.between(now, start)
        if (d.isNegative || d.isZero) return null

        val minutes = d.toMinutes()
        if (minutes < 1) return "马上开始"

        // 跨天：只说天数（避免"1 天 3 小时"这种冗余）
        val days = d.toDays()
        if (days >= 1) return "距上课 $days 天"

        if (minutes >= MINUTES_PER_HOUR) {
            val h = minutes / MINUTES_PER_HOUR
            val m = minutes % MINUTES_PER_HOUR
            return if (m == 0L) "距上课 ${h}h" else "距上课 ${h}h${m}分"
        }
        return "距上课 $minutes 分钟"
    }

    /**
     * 把「下课时刻」拼出来（用于判断"是否正在上课"）。
     *
     * @param date 上课日期
     * @param endPeriod 结束节次（1-based 大节，含）
     */
    fun endOf(date: LocalDate, endPeriod: Int): LocalDateTime? {
        val endStr = runCatching { PeriodTime.endOf(endPeriod) }.getOrNull() ?: return null
        val time = runCatching { LocalTime.parse(endStr.trim()) }.getOrNull() ?: return null
        return LocalDateTime.of(date, time)
    }

    /**
     * 把「开课时刻」拼出来。
     *
     * ⚠️ 作息表按【大节】定义（ADR-014），`PeriodTime.startOf` 直接给出
     *    "09:00" 这样的起始时刻 —— 无需自己解析区间字符串。
     *
     * @param date 上课日期
     * @param startPeriod 起始节次（1-based 大节）
     */
    fun startOf(date: LocalDate, startPeriod: Int): LocalDateTime? {
        val startStr = runCatching { PeriodTime.startOf(startPeriod) }.getOrNull() ?: return null
        val time = runCatching { LocalTime.parse(startStr.trim()) }.getOrNull() ?: return null
        return LocalDateTime.of(date, time)
    }
}
