package com.gzuschedule.app.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.domain.CourseCountdown
import com.gzuschedule.app.domain.TimeFormats
import com.gzuschedule.app.domain.UpcomingExamFilter
import com.gzuschedule.app.domain.WeekCalculator
import com.gzuschedule.app.domain.model.Course
import com.gzuschedule.app.domain.model.Exam
import com.gzuschedule.app.domain.model.Grade
import com.gzuschedule.app.domain.repository.ExamRepository
import com.gzuschedule.app.domain.repository.GradeRepository
import com.gzuschedule.app.domain.repository.ScheduleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Date
import java.util.Locale

// ⚠️ ADR-049：TodayUiState 与 HOME_MAX_ITEMS 已移到 TodayUiState.kt ——
//    与 ViewModel 同文件会导致 kapt 生成「类重复」的 Java stub（已知问题）。

class TodayViewModel(
    private val scheduleRepo: ScheduleRepository,
    private val gradeRepo: GradeRepository,
    private val examRepo: ExamRepository,
    private val metaReader: suspend (String) -> String?,
) : ViewModel() {

    private val _state = MutableStateFlow(TodayUiState())
    val state: StateFlow<TodayUiState> = _state.asStateFlow()

    init {
        load()
    }

    /**
     * 手动触发一次重算（ADR-055）。
     *
     * ⚠️ 与 ScheduleViewModel 不同，这里**不能**改成纯 Flow 订阅 ——
     *    因为首页内容依赖「当前时刻」（今天几号、第几周、倒计时、进度条），
     *    必须能被心跳周期性驱动重算。
     *
     *    但原实现用 `first()` 只取一次数据库快照，配合 ADR-050 的
     *    Fragment 缓存，会出现「同步完了首页还是空的」。
     *    现在每次 load 都重新订阅一次（`first()` 拿最新值）——
     *    既保留"时变"特性，又能在心跳时拿到最新数据。
     */
    fun load() {
        viewModelScope.launch {
            val terms = scheduleRepo.observeTerms().first()
            val term = terms.firstOrNull().orEmpty()
            val all = if (term.isBlank()) {
                emptyList()
            } else {
                scheduleRepo.observeCourses(term).first()
            }

            val week = computeWeek()
            val today = LocalDate.now().dayOfWeek.value

            // 学业数据（可能为空 —— 刚入学时成绩/考试本来就没有）
            val grades = runCatching {
                gradeRepo.observeGrades().first()
            }.getOrDefault(emptyList())

            val exams = runCatching {
                examRepo.observeExams().first()
            }.getOrDefault(emptyList())

            val now = LocalDateTime.now()
            val todayDate = now.toLocalDate()
            val todayCourses = all
                .filter { it.dayOfWeek == today && it.occursInWeek(week) }
                .sortedBy { it.startPeriod }

            _state.value = TodayUiState(
                hasAnyData = all.isNotEmpty(),
                week = week,
                todayCourses = todayCourses,
                lastSyncDisplay = readLastSync(),
                recentGrades = grades.take(HOME_MAX_ITEMS),
                // ⚠️ ADR-049：「提前一个月显示」—— 只保留未来 30 天内的考试。
                //    Exam.date 是 LocalDate?（可为 null，教务有时不给日期）——
                //    无日期的无法判断远近，不显示（显示反而困惑）。
                upcomingExams = exams
                    .filter { UpcomingExamFilter.isUpcoming(it.date, todayDate) }
                    .sortedWith(
                        compareBy(
                            { it.date ?: LocalDate.MAX },
                            { it.startTime ?: java.time.LocalTime.MAX },
                        ),
                    )
                    .take(HOME_MAX_ITEMS),
                // ⚠️ ADR-049：下一节课的倒计时（用户：「加上距离上课的时间」）
                nextCourseCountdown = nextCourseCountdown(todayCourses, todayDate, now),
            )
        }
    }

    /**
     * 算出「下一节课还有多久」（ADR-049/050）。
     *
     * ⚠️ 用户明确：「课程上课倒计时**只显示当天的**」。
     *    所以只从**今天的课**里找，不跨天。
     *
     * ⚠️ 与上一版的差别：上一版只找「还没开始」的课 —— 一旦今天的课全开始了，
     *    倒计时就整个消失。现在改为：
     *    * 优先取**尚未开始**的第一节（真正的"距上课"）
     *    * 若都在进行中，则取**进行中**的那一节（显示"上课中"）
     *    * 今天没课 / 今天的课全上完 → null（不显示，且不跨天）
     */
    private fun nextCourseCountdown(
        todayCourses: List<Course>,
        today: LocalDate,
        now: LocalDateTime,
    ): String? {
        if (todayCourses.isEmpty()) return null

        // 1) 还没开始的课 —— 正常倒计时
        val upcoming = todayCourses.firstOrNull { c ->
            val start = CourseCountdown.startOf(today, c.startPeriod) ?: return@firstOrNull false
            start.isAfter(now)
        }
        if (upcoming != null) {
            val start = CourseCountdown.startOf(today, upcoming.startPeriod) ?: return null
            return CourseCountdown.text(now, start)
        }

        // 2) 都在进行中/已结束 —— 若有一节**正在进行**，提示"上课中"
        val ongoing = todayCourses.firstOrNull { c ->
            val start = CourseCountdown.startOf(today, c.startPeriod) ?: return@firstOrNull false
            val end = CourseCountdown.endOf(today, c.endPeriod) ?: return@firstOrNull false
            !now.isBefore(start) && now.isBefore(end)
        }
        if (ongoing != null) {
            return "《${ongoing.name}》上课中"
        }

        // 3) 今天课都上完了 —— 不显示
        return null
    }

    /**
     * 上次同步时间的展示文案（ADR-054）。
     *
     * ⚠️ 原来在这里每次 new 两个 `SimpleDateFormat` —— 该方法被 30 秒心跳
     *    反复调用，而 SimpleDateFormat 构造开销很大。现在复用 [TimeFormats]。
     */
    private suspend fun readLastSync(): String? =
        TimeFormats.isoToDisplay(metaReader(AppDatabase.MetaKeys.LAST_SYNC_AT))

    /**
     * 计算当前周次（ADR-011）。
     *
     * 优先读本地保存的「第一周星期一」（同步时从教务写入，或用户手设）。
     * 读不到才回落到旧的估算方式 —— 估算会偏小，仅作最后兜底。
     */
    private suspend fun computeWeek(): Int {
        val raw = metaReader(AppDatabase.MetaKeys.FIRST_MONDAY)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val d = LocalDate.parse(raw) // ISO yyyy-MM-dd
                return WeekCalculator.weekOf(d)
            }
        }
        // 回落：用课程数据里最早的 startWeek（可能偏小）
        val terms = scheduleRepo.observeTerms().first()
        val term = terms.firstOrNull().orEmpty()
        val all = if (term.isBlank()) {
            emptyList()
        } else {
            scheduleRepo.observeCourses(term).first()
        }
        return all.minOfOrNull { it.startWeek }?.coerceAtLeast(1) ?: 1
    }

    class Factory(
        private val scheduleRepo: ScheduleRepository,
        private val gradeRepo: GradeRepository,
        private val examRepo: ExamRepository,
        private val metaReader: suspend (String) -> String?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TodayViewModel(scheduleRepo, gradeRepo, examRepo, metaReader) as T
    }
}
