package com.gzuschedule.app.ui.schedule

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.data.local.DayOverrideDao
import com.gzuschedule.app.data.local.DayOverride
import com.gzuschedule.app.domain.HolidayCalendar
import com.gzuschedule.app.domain.TermTitle
import com.gzuschedule.app.domain.WeekCalculator
import com.gzuschedule.app.domain.model.Course
import com.gzuschedule.app.domain.repository.ScheduleRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ScheduleUiState(
    val isLoading: Boolean = true,
    val courses: List<Course> = emptyList(),
    val term: String = "",
    val termDisplayName: String = "",
    /** 当前查看的周次。 */
    val week: Int = 1,
    /** 学期第一周星期一（ISO 字符串）；取不到为 null，表头便不显示日期。 */
    val firstMonday: String? = null,
    /**
     * 「当前周」—— 由第一周星期一与今天算出，**始终反映真实日期**。
     *
     * ⚠️ 与 [week] 的区别：
     *   · [week] 是用户正在看的周（可能被手动切到别的周）
     *   · [currentWeek] 永远是今天所在的周
     * 上一周/下一周按钮的置灰判断、以及「回到本周」按钮都依赖它，
     * 所以不能用 [week] 代替（手动切走后就不是今天了）。
     */
    val currentWeek: Int = 1,
    /** 本学期总周数（用于周次选择器上限与「第 N / M 周」显示）。 */
    val totalWeeks: Int = DEFAULT_TOTAL_WEEKS,
    /**
     * 假日日历（ADR-060）。用户已确认「假期就当没课」——
     * 故渲染时把假日的课程**直接跳过**，不做任何标记。
     */
    val holidays: HolidayCalendar = HolidayCalendar.EMPTY,

    /**
     * 当日调课表（ADR-105）：日期字符串（ISO）→ 调课设置。
     *
     * ⚠️ 用户需求：「今天页改变，那一周的课程页看也改变，变成我调的」。
     *    渲染时按「该周每一天对应的日期」查这张表：
     *      · UseDay(d) → 显示第 d 天的课
     *      · NoClass   → 那天不显示课
     */
    val dayOverrides: Map<String, DayOverride> = emptyMap(),
) {
    /** 当前查看的是不是今天所在的周。 */
    val isViewingCurrentWeek: Boolean get() = week == currentWeek

    companion object {
        /** 默认学期总周数（取不到课程数据的周次上限时用）。 */
        const val DEFAULT_TOTAL_WEEKS = 20
    }
}

/**
 * 周课表 ViewModel（ADR-055 修复「登录后周课表不显示」）。
 *
 * ⚠️ 原实现的 bug：
 *   ```kotlin
 *   init { load() }                    // 只跑一次
 *   repo.observeCourses(term).first()  // 取一次就断流
 *   ```
 *   配合 ADR-050 的「Fragment 实例缓存 + show/hide」，ScheduleFragment
 *   **只被创建一次** —— 于是：
 *     ① 打开 App 时无数据 → 显示空
 *     ② 用户登录并同步 → 数据入库
 *     ③ 切回周课表 → Fragment 还在（缓存），**不会重新 load()** → 永远空白
 *
 * ⚠️ 新实现：**持续订阅 Room 的 Flow**，而不是手动拉一次。
 *   `observeTerms()` 与 `observeCourses()` 都是 Room 的 `Flow` ——
 *   数据一入库，Room 自动推送新值，UI 跟着刷新，**无需任何手动 reload**。
 *   这也是 Flow 相对于「查询一次」的根本价值。
 *
 * ⚠️ 周次是**用户可手动切换**的状态，与数据库推送的课程数据分开管理：
 *   课程数据变化时不能重置用户正在看的周次。
 */
class ScheduleViewModel(
    private val repo: ScheduleRepository,
    private val metaReader: suspend (String) -> String?,
    /** ⚠️ ADR-105：当日调课 DAO。 */
    private val dayOverrideDao: DayOverrideDao,
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleUiState())
    val state: StateFlow<ScheduleUiState> = _state.asStateFlow()

    /** 用户手动切换的周次（null = 跟随「当前周」自动计算）。 */
    private val manualWeek = MutableStateFlow<Int?>(null)

    /**
     * 「学期基准重读」触发器（ADR-087）。
     *
     * ⚠️⚠️ 为什么需要它：
     *   用户在设置页改了「第一周星期一」后，本 ViewModel 的 state 不会自动更新 ——
     *   因为 observeData() 只在 courses / term / manualWeek 变化时重新收集，
     *   而 firstMonday 是在 collect **内部**从 DB 现读的。
     *   → 结果：改了学期起始日，周次和卡片日期都不变，必须重启 App。
     *
     *   ✅ 用一个自增计数触发 combine 重新发射 → 重新读 DB → state 更新 → 重绘。
     */
    private val semesterReloadTick = kotlinx.coroutines.flow.MutableStateFlow(0)

    /**
     * 让 ViewModel 重新读取「第一周星期一」等学期基准并重算周次。
     * 由 Fragment 在页面重新显示时调用（见 ScheduleFragment.reloadAppearanceIfChanged）。
     */
    fun refreshSemesterBase() {
        semesterReloadTick.value += 1
    }

    init {
        observeData()
    }

    /**
     * 持续订阅数据库。
     *
     * ⚠️ `flatMapLatest`：学期变化时自动切换到新学期的课程流，
     *    旧流被取消，不会泄漏，也不会出现两个学期的课混在一起。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeData() {
        viewModelScope.launch {
            repo.observeTerms()
                .flatMapLatest { terms ->
                    val term = terms.firstOrNull().orEmpty()
                    if (term.isBlank()) {
                        kotlinx.coroutines.flow.flowOf(emptyList<Course>() to term)
                    } else {
                        repo.observeCourses(term).map { it to term }
                    }
                }
                .combine(manualWeek) { (courses, term), manual ->
                    Triple(courses, term, manual)
                }
                // ⚠️ ADR-087：把「重读触发器」也并入 combine ——
                //    设置页改完「第一周星期一」后触发，让下面 collect 重新执行，
                //    从而重新读 DB 的 FIRST_MONDAY 并重算周次。
                .combine(semesterReloadTick) { triple, _ -> triple }
                // ⚠️ ADR-105：把调课表也并入 —— 用户在今日页调课后，
                //    周课表页要立刻跟着变（用户需求：「那一周的课程页看也改变」）。
                .combine(dayOverrideDao.observeAll()) { triple, overrides ->
                    Triple(triple.first, triple.second, triple.third) to overrides
                }
                .collect { (triple, overrides) ->
                    val (courses, term, manual) = triple
                    // ⚠️ 元数据在 collect 里读 —— 同步后 meta 也会变，
                    //    重新读才能拿到新的学期名/第一周周一/假日。
                    val fm = metaReader(AppDatabase.MetaKeys.FIRST_MONDAY)
                        ?.takeIf { it.isNotBlank() }
                    val autoWeek = computeCurrentWeek(fm, courses)

                    // ⚠️ ADR-105：把调课记录整理成「日期 → 调课」映射。
                    //    渲染层按「该周每一天对应的日期」查表决定显示哪天的课。
                    val overrideMap = overrides.associate {
                        it.date to DayOverride.from(it)
                    }

                    _state.value = ScheduleUiState(
                        isLoading = false,
                        courses = courses,
                        term = term,
                        termDisplayName = TermTitle.of(
                            yearName = metaReader(AppDatabase.MetaKeys.ACADEMIC_YEAR_NAME),
                            termName = metaReader(AppDatabase.MetaKeys.TERM_NAME),
                        ),
                        // 用户手动切过周就尊重用户选择；否则跟随当前周
                        week = (manual ?: autoWeek).coerceIn(1, totalWeeksOf(courses)),
                        firstMonday = fm,
                        currentWeek = autoWeek,
                        totalWeeks = totalWeeksOf(courses),
                        holidays = HolidayCalendar.fromJson(
                            metaReader(AppDatabase.MetaKeys.HOLIDAYS),
                        ),
                        // ⚠️ ADR-105：当日调课表（日期字符串 → 调课设置）
                        dayOverrides = overrideMap,
                    )
                }
        }
    }

    /**
     * 本学期总周数（ADR-060）。
     *
     * ⚠️ 从课程数据的最大 endWeek 推导，而不是写死 20：
     *    不同学期周数不同（有的 18 周、有的 20 周），
     *    写死会让「第 N / M 周」显示错、周次选择器多出空白周。
     *    取不到时回落到 [ScheduleUiState.DEFAULT_TOTAL_WEEKS]。
     */
    private fun totalWeeksOf(courses: List<Course>): Int =
        courses.maxOfOrNull { it.endWeek }
            ?.coerceIn(1, MAX_WEEKS)
            ?: ScheduleUiState.DEFAULT_TOTAL_WEEKS

    /**
     * 当前周次（ADR-011）。
     *
     * 优先用「第一周星期一」精确计算；缺失时才回落到课程数据的估算。
     *
     * ⚠️ 旧实现有 bug：`minStart.coerceIn(minStart, maxEnd)` 恒等于 minStart，
     * 实际上从未做过任何校正，几乎总是显示第 1 周。
     */
    private fun computeCurrentWeek(firstMonday: String?, courses: List<Course>): Int {
        firstMonday?.let { raw ->
            runCatching {
                return WeekCalculator.weekOf(LocalDate.parse(raw))
            }
        }
        // 回落：取课程数据里最早的 startWeek（偏小，仅兜底）
        return courses.minOfOrNull { it.startWeek }?.coerceAtLeast(1) ?: 1
    }

    fun previousWeek() {
        val s = _state.value
        if (s.week > 1) manualWeek.value = s.week - 1
    }

    fun nextWeek() {
        val s = _state.value
        // ⚠️ 上限改为「本学期实际周数」而不是写死的 30 ——
        //    写死会让用户能翻到完全没有课的空白周（ADR-060）。
        if (s.week < s.totalWeeks) manualWeek.value = s.week + 1
    }

    /** 跳到指定周（周次选择器用）。 */
    fun goToWeek(week: Int) {
        val s = _state.value
        manualWeek.value = week.coerceIn(1, s.totalWeeks)
    }

    /** 回到「当前周」（取消手动切换）。 */
    fun resetToCurrentWeek() {
        manualWeek.value = null
    }

    class Factory(
        private val repo: ScheduleRepository,
        private val metaReader: suspend (String) -> String?,
        /** ⚠️ ADR-105：当日调课 DAO。 */
        private val dayOverrideDao: DayOverrideDao,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ScheduleViewModel(repo, metaReader, dayOverrideDao) as T
    }

    private companion object {
        /** 周次上限硬保护 —— 防止脏数据（如 endWeek=999）撑爆周次选择器。 */
        const val MAX_WEEKS = 30
    }
}
