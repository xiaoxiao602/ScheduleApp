package com.gzuschedule.app.ui.widget

import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import com.gzuschedule.app.domain.CourseProgress
import com.gzuschedule.app.domain.PeriodTime
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 课程进度条渲染（ADR-043）。
 *
 * 交互对齐「原生音乐播放器进度条」：进度不跳变，而是平滑推进。
 *
 * ⚠️ 修两个导致「没有动画」的 bug：
 *
 *   ① 动画时长与刷新间隔不匹配（核心）
 *      旧：ANIM_MS = 600ms，但刷新间隔 30s。
 *          → 600ms 内走完整段增量后就**完全静止 29.4 秒**，
 *            下一次刷新再动 600ms。观感是「顿一下、不动、再顿一下」，
 *            而不是连续播放。
 *      新：动画时长 = 刷新间隔（TICK_MS），配合 LinearInterpolator，
 *          进度条**匀速连续爬行**，全程无停顿 —— 这才是播放器手感。
 *
 *   ② 用 DecelerateInterpolator 会「先快后慢」
 *      播放器进度条是**匀速**的（时间均匀流逝），不是缓出。
 *      缓出会让每段增量的尾部拖长，叠加后显得卡顿。
 *
 * ⚠️ 计算结果全部来自 CourseProgress（纯函数，有真单测）。
 */
object CourseProgressBinder {

    /** ProgressBar 的 max 值。用 1000 换取平滑度（避免 int 精度导致跳动）。 */
    private const val SCALE = 1000

    /**
     * 刷新间隔（ms）—— 必须与 TodayFragment 的定时器一致。
     *
     * ⚠️ 动画时长直接用这个值（而非固定的 600ms），
     *    这样上一段动画刚结束，下一次刷新正好接上 → 视觉连续。
     */
    const val TICK_MS = 10_000L

    /**
     * 绑定进度条。
     *
     * @param row       容器（控制显隐）
     * @param bar       进度条
     * @param label     文案
     * @param date      课程日期
     * @param startPeriod 起始小节课号
     * @param endPeriod   结束小节课号
     * @param now       当前时刻
     * @param animate   是否播动画（首次显示应直接定位，避免从 0 冲上去）
     */
    fun bind(
        row: View,
        bar: ProgressBar,
        label: TextView,
        date: LocalDate,
        startPeriod: Int,
        endPeriod: Int,
        now: LocalDateTime,
        animate: Boolean = true,
    ) {
        val start = PeriodTime.startOf(startPeriod)
        val end = PeriodTime.endOf(endPeriod)

        val result = CourseProgress.of(date, start, end, now)
        if (result == null || !result.inProgress) {
            // 未开始或已结束 → 隐藏（只在上课时显示）
            if (row.visibility != View.GONE) {
                row.visibility = View.GONE
                bar.progress = 0
            }
            return
        }

        val wasHidden = row.visibility != View.VISIBLE
        if (wasHidden) row.visibility = View.VISIBLE

        val target = (result.fraction * SCALE).toInt()
        val current = bar.progress

        when {
            // 首次出现 / 跨度很大（比如刚滑到这一项）→ 直接定位，不播动画
            !animate || wasHidden || current == 0 -> bar.progress = target
            current != target -> {
                // ⚠️ 匀速 + 时长=刷新间隔 → 上一段结束正好接上下一次，连续爬行
                ObjectAnimator.ofInt(bar, "progress", current, target).apply {
                    duration = TICK_MS
                    interpolator = LinearInterpolator()
                    start()
                }
            }
        }

        label.text = "上课中 · 还剩 ${result.minutesLeft} 分钟"
    }

    /** 隐藏进度条（课程不在进行中时调用）。 */
    fun hide(row: View, bar: ProgressBar) {
        row.visibility = View.GONE
        bar.progress = 0
    }
}
