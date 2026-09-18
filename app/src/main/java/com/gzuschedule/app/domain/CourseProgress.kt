package com.gzuschedule.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 课程进度计算（ADR-031）。
 *
 * 用途：课程进行中时，在卡片上显示一条进度条，随真实时间推进。
 *
 * 设计原则：
 *   * **纯函数**，不依赖 Android —— 可直接写单元测试（本项目的验证约定：
 *     逻辑必须可被真单测覆盖，而不是只靠截图）。
 *   * 时间全部以 `LocalDateTime` 传入，便于测试注入"现在"。
 *
 * 关键边界（都有对应测试）：
 *   * 课未开始        → progress = 0，inProgress = false
 *   * 刚好在上课时间  → inProgress = true
 *   * 下课时          → progress = 1，inProgress = false（下课即结束）
 *   * 已下课          → progress = 1，inProgress = false
 *   * 时间解析失败    → 返回 null，调用方隐藏进度条（不崩）
 */
object CourseProgress {

    /** 进度结果。 */
    data class Result(
        /** 0f..1f，已进行的比例。 */
        val fraction: Float,
        /** 是否正在上课（开始 <= now < 结束）。 */
        val inProgress: Boolean,
        /** 距离下课还有多少分钟（已下课时为 0）。 */
        val minutesLeft: Long,
    )

    /**
     * 计算某次课在 [now] 时刻的进度。
     *
     * @param date      上课日期
     * @param startTime 开始时间 "HH:mm"（来自 PeriodTime）
     * @param endTime   结束时间 "HH:mm"
     * @param now       当前时刻（测试时注入）
     * @return 结果；若时间串无法解析则返回 null
     */
    fun of(
        date: LocalDate,
        startTime: String,
        endTime: String,
        now: LocalDateTime,
    ): Result? {
        val s = parseTime(startTime) ?: return null
        val e = parseTime(endTime) ?: return null

        val start = LocalDateTime.of(date, s)
        val end = LocalDateTime.of(date, e)

        // 数据异常（结束早于开始）时不显示，避免负数进度
        if (!end.isAfter(start)) return null

        val totalSeconds = java.time.Duration.between(start, end).seconds.toDouble()
        val elapsed = java.time.Duration.between(start, now).seconds.toDouble()

        val fraction = (elapsed / totalSeconds).coerceIn(0.0, 1.0).toFloat()
        val inProgress = now >= start && now < end
        val minutesLeft = if (inProgress) {
            java.time.Duration.between(now, end).toMinutes().coerceAtLeast(0)
        } else {
            0
        }

        return Result(fraction = fraction, inProgress = inProgress, minutesLeft = minutesLeft)
    }

    /** 便捷重载：用「今天」的日期。 */
    fun ofToday(startTime: String, endTime: String, now: LocalDateTime): Result? =
        of(now.toLocalDate(), startTime, endTime, now)

    /**
     * 解析 "HH:mm"。
     * ⚠️ 必须容错：教务数据里时间格式可能带空格或不全。
     */
    private fun parseTime(raw: String): LocalTime? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        return runCatching {
            val parts = t.split(":")
            if (parts.size != 2) return null
            val h = parts[0].trim().toIntOrNull() ?: return null
            val m = parts[1].trim().toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            LocalTime.of(h, m)
        }.getOrNull()
    }
}
