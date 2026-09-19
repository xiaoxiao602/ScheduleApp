package com.gzuschedule.app.ui.today

import com.gzuschedule.app.data.local.DayOverride
import com.gzuschedule.app.domain.model.Course
import com.gzuschedule.app.domain.model.Exam
import com.gzuschedule.app.domain.model.Grade

/**
 * 首页 UI 状态（ADR-049 拆到独立文件）。
 *
 * ⚠️ 为什么要单独一个文件：
 *   原先 `TodayUiState` 与 `TodayViewModel` 同在一个 .kt 里，而该文件同时还有
 *   顶层常量（`HOME_MAX_ITEMS`）—— Kotlin 会额外生成一个 `TodayViewModelKt`
 *   文件门面类。kapt 生成 Java stub 时，会为该文件里的**每个类**各出一份 stub，
 *   加上门面类自身，导致
 *     `错误: 类重复: com.gzuschedule.app.ui.today.TodayUiState`
 *   这种 "类重复" 报错（kapt 已知问题）。把数据类移出该文件即可根治。
 */

const val HOME_MAX_ITEMS = 3

data class TodayUiState(
    val hasAnyData: Boolean = false,
    val week: Int = 1,
    val todayCourses: List<Course> = emptyList(),
    val lastSyncDisplay: String? = null,
    /** 最近成绩（已按时间倒序，最多 [HOME_MAX_ITEMS] 条）。 */
    val recentGrades: List<Grade> = emptyList(),
    /**
     * 近期考试（ADR-049：**只含未来 30 天内**的，按时间正序）。
     *
     * ⚠️ 与 [recentGrades] 不同 —— 考试这块**永远显示**：
     *    为空时 UI 显示「近期无考试」，而不是整块隐藏。
     *    这样用户能区分「没有考试」和「还没同步」。
     */
    val upcomingExams: List<Exam> = emptyList(),
    /**
     * 下一节课的倒计时文案（ADR-049），如「距上课 3h40分」。
     *
     * ⚠️ 用户要求：「课程那个卡片 加上距离上课的时间」。
     *    仅当今天**还没开始**的下一节课存在时非空；已在上课或今天没课时为 null。
     */
    val nextCourseCountdown: String? = null,

    /**
     * 今日的调课设置（ADR-105）。
     *
     * ⚠️ 用户需求：「那一天突然调了，比如星期一的课，我就选周一，
     *    那天的课表就临时变成周一的课」「如果周四五突然没课也可以选无」。
     */
    val dayOverride: DayOverride = DayOverride.None,

    /** 调课针对的日期（UI 显示提示文案用）。 */
    val overrideDate: java.time.LocalDate? = null,
)
