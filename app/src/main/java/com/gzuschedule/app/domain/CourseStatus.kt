package com.gzuschedule.app.domain

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课程状态判定（ADR-051）。
 *
 * ⚠️ 用户要求：「上完的课这里显示一个 和卡片一样的蓝色的勾」。
 *
 * 三种状态互斥，用于驱动卡片上的不同视觉：
 *   * [State.UPCOMING]  还没开始 → 无徽标（或倒计时）
 *   * [State.ONGOING]   正在上   → 进度条
 *   * [State.DONE]      已上完   → 蓝色对勾
 *
 * 抽成纯函数便于单测 —— 边界（正好下课那一刻算不算上完）必须明确。
 */
object CourseStatus {

    enum class State { UPCOMING, ONGOING, DONE }

    /**
     * 判断某节课在 [now] 时刻的状态。
     *
     * @param date        上课日期（用于拼出当天的开始/结束时刻）
     * @param startPeriod 起始大节（1-based）
     * @param endPeriod   结束大节（含）
     */
    fun of(date: LocalDate, startPeriod: Int, endPeriod: Int, now: LocalDateTime): State {
        val start = CourseCountdown.startOf(date, startPeriod) ?: return State.UPCOMING
        val end = CourseCountdown.endOf(date, endPeriod) ?: return State.UPCOMING

        return when {
            now.isBefore(start) -> State.UPCOMING
            // ⚠️ 下课瞬间即算「上完」—— 与 ADR-031 的进度条语义一致
            //    （进度条取下课时归零/隐藏），避免两个组件在同一刻给出矛盾信号。
            now.isBefore(end) -> State.ONGOING
            else -> State.DONE
        }
    }

    /** 便捷判断：是否已上完。 */
    fun isDone(date: LocalDate, startPeriod: Int, endPeriod: Int, now: LocalDateTime): Boolean =
        of(date, startPeriod, endPeriod, now) == State.DONE
}
