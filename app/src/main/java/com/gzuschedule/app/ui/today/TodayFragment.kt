package com.gzuschedule.app.ui.today

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.data.local.LocalExamRepository
import com.google.android.material.button.MaterialButton
import com.gzuschedule.app.R
import com.gzuschedule.app.data.local.LocalGradeRepository
import com.gzuschedule.app.data.local.LocalScheduleRepository
import com.gzuschedule.app.data.local.UserProfileStore
import com.gzuschedule.app.ui.widget.CourseDetailDialog
import com.gzuschedule.app.ui.widget.CourseProgressBinder
import java.time.LocalDateTime
import com.gzuschedule.app.databinding.FragmentTodayBinding
import com.gzuschedule.app.databinding.ItemCourseBinding
import com.gzuschedule.app.databinding.ItemExamHomeBinding
import com.gzuschedule.app.databinding.ItemGradeHomeBinding
import com.gzuschedule.app.domain.PeriodTime
import com.gzuschedule.app.domain.model.Course
import com.gzuschedule.app.domain.UpcomingExamFilter
import com.gzuschedule.app.domain.model.Exam
import com.gzuschedule.app.domain.model.Grade
import com.gzuschedule.app.ui.exam.ExamListActivity
import com.gzuschedule.app.ui.grade.GradeListActivity
import com.gzuschedule.app.ui.login.LoginActivity
import com.gzuschedule.app.data.local.DayOverride
import com.gzuschedule.app.ui.widget.DayOverrideDialog
import com.gzuschedule.app.domain.CourseCountdown
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 「今天」页 —— 首屏（ADR-006）。
 *
 * 展示：日期 + 第 N 周 + 同步状态 + 今日课程列表。
 * 这是最高频的使用场景，打开 App 0 次点击即见。
 */
class TodayFragment : Fragment() {

    companion object {
        /**
         * ⚠️ 已废弃（ADR-021）：头像底色现在取**主题主色**（深浅自动切换）。
         * 保留仅为兼容旧引用；硬编码色在深色模式下会与背景撞色。
         */
        @Deprecated("改用主题 colorPrimary")
        const val FALLBACK_AVATAR_COLOR = 0xFF06459B.toInt()

        /**
         * 倒计时重算频率：每 N 次心跳重算一次（ADR-049）。
         * 心跳 10s → N=3 → 30s 一次，足够匹配「分钟」级显示，也不会频繁重建列表。
         */
        private const val COUNTDOWN_EVERY_N_TICKS = 3
    }

    private var _binding: FragmentTodayBinding? = null
    private val binding get() = _binding!!

    private lateinit var viewModel: TodayViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentTodayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = AppDatabase.get(requireContext())
        viewModel = ViewModelProvider(
            this,
            TodayViewModel.Factory(
                scheduleRepo = LocalScheduleRepository(db),
                gradeRepo = LocalGradeRepository(db),
                examRepo = LocalExamRepository(db),
                metaReader = { key -> db.metaDao().get(key) },
                // ⚠️ ADR-105：当日调课
                dayOverrideDao = db.dayOverrideDao(),
            ),
        )[TodayViewModel::class.java]

        // ⚠️ ADR-109：adapter 现在是**字段**（见类成员声明处），
        //    因为心跳里的 refreshCountdowns() 要用它。
        adapter = TodayCourseAdapter()
        binding.todayList.layoutManager = LinearLayoutManager(requireContext())
        binding.todayList.adapter = adapter

        // 顶部头像 + 卡片按钮 → 都进「设置」页（登录入口在那里）
        binding.avatarHeader.setOnClickListener { goToSettings() }
        binding.tvEmptyIcon.setOnClickListener { goToSettings() }
        setupActionButton()

        // 学业数据「全部」入口（ADR-009）
        binding.btnAllGrades.setOnClickListener {
            startActivity(Intent(requireContext(), GradeListActivity::class.java))
        }
        binding.btnAllExams.setOnClickListener {
            startActivity(Intent(requireContext(), ExamListActivity::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it, adapter) }
            }
        }

        // ⚠️ ADR-055：每次页面**变得可见**时重算一次。
        //    配合 ADR-050 的 Fragment 缓存（show/hide 而非 replace），
        //    Fragment 不会被重建 —— 于是「在设置页登录并同步后切回今天」
        //    原先不会刷新，首页仍显示空。
        //    这里在 RESUMED 时补一次 load()：既覆盖"同步后回来"，
        //    也覆盖"跨过午夜/下课时刻"这类时间变化。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.load()
            }
        }

        // ⚠️ ADR-043：进度条心跳。
        //    之前 refreshProgress() 定义在 adapter 里但**从未被调用** ——
        //    进度条只在列表首次加载时算一次，之后永远不动（用户反馈「没有动画」）。
        //    这里在 STARTED 状态下每 TICK_MS 触发一次，配合等长的线性动画
        //    形成连续爬行；离开页面自动取消。
        //
        // ⚠️ ADR-049：同一心跳里也刷新「下一节课倒计时」——
        //    它是相对当前时刻算的，不刷新会一直停在首次渲染的值。
        // ⚠️ ADR-106：倒计时心跳。
        //    ⚠️⚠️ 不再调 viewModel.load()！
        //       旧实现每 30 秒 load() 一次，而 load() 是**异步读库**的 ——
        //       多次未完成的 load() 会积压，state 被反复覆写 →
        //       倒计时数字来回跳，看起来"越来越长"（用户反馈的 bug）。
        //       现在只重算倒计时文案（纯计算，不读库、不碰 state）。
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    adapter.refreshProgress()
                    // 倒计时重算（每 30 秒一次即可 —— 显示粒度为分钟）
                    countdownTick += 1
                    if (countdownTick % COUNTDOWN_EVERY_N_TICKS == 0) {
                        refreshCountdowns()
                    }
                    delay(CourseProgressBinder.TICK_MS)
                }
            }
        }
    }

    /**
     * ⚠️ ADR-109：升为**字段**（原来是 onCreateView 的局部变量）。
     *    因为心跳里的 refreshCountdowns() 需要访问它来设置倒计时。
     */
    private lateinit var adapter: TodayCourseAdapter

    /** 心跳计数（用于降低倒计时重算频率）。 */
    private var countdownTick = 0

    /**
     * 刷新页头头像（ADR-012）。
     *
     * 显示名规则与设置页一致：自定义名优先，回落真名。
     * 在 onResume 调用，以便从设置页改完名字/头像返回后立即生效。
     */
    private fun refreshHeaderAvatar() {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.get(ctx)
            val custom = db.metaDao().get(AppDatabase.MetaKeys.DISPLAY_NAME)
            val real = db.metaDao().get(AppDatabase.MetaKeys.STUDENT_NAME)
            val shown = custom?.takeIf { it.isNotBlank() }
                ?: real?.takeIf { it.isNotBlank() }

            // 底色取主题主色（与信息卡同色）。
            // ⚠️ colorPrimary 由 appcompat 声明 —— 用 Material 的 R 或应用自己的 R 都编译不过。
            val ta = ctx.obtainStyledAttributes(
                intArrayOf(androidx.appcompat.R.attr.colorPrimary),
            )
            val cardColor = ta.getColor(0, FALLBACK_AVATAR_COLOR)
            ta.recycle()

            binding.avatarHeader.setFallbackColor(cardColor)
            binding.avatarHeader.bind(UserProfileStore.avatarFile(ctx), shown)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHeaderAvatar()
    }

    /**
     * show/hide 切换时刷新（ADR-082 修复「切回来数据不更新」）。
     *
     * ⚠️⚠️ 为什么必须有这个：
     *   MainActivity 用 `show()/hide()` 切换三个 tab（ADR-050 性能优化），
     *   Fragment **不会重建、也不会走 onResume** —— 它始终是 STARTED，
     *   只是 visibility 变了。
     *   所以从设置页/课表页切回今天页时，onResume 不会触发，
     *   若期间数据有变化（如同步了新课表），今天页会显示旧数据。
     *
     *   这与之前课表页遇到的问题**完全同类**（那次已修），
     *   今天页/设置页当时漏掉了 —— 本次补齐。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) {
            refreshHeaderAvatar()
            // ⚠️ ADR-087：用户在设置页可能改了「第一周星期一」，
            //    那会影响「今天」页判定"今天是第几周的第几节课"。
            //    show/hide 切回来时也让 ViewModel 重读学期基准。
            viewModel.refreshSemesterBase()
        }
    }

    /**
     * 主操作按钮的接线（ADR-023）。
     *
     * ⚠️ 已撤掉 MaterialSplitButton：
     * 用户要求改回原来那个实心登录按钮。SplitButton 是容器（javap 实测只有
     * ctor + addView），需要手工造子按钮，且深色模式下配色易错 —— 不值当。
     * 现在按钮在 XML 中定义，此处只绑点击、并按状态改文案。
     */
    private fun setupActionButton() {
        binding.btnCardAction.setOnClickListener { goToSettings() }
    }

    private fun goToSettings() {
        (activity as? com.gzuschedule.app.ui.main.MainActivity)?.navigateToSettings()
    }

    private fun render(state: TodayUiState, adapter: TodayCourseAdapter) {
        // ---- 日期 ----
        val today = LocalDate.now()
        val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        binding.tvDate.text = String.format(
            Locale.CHINA, "%d年%d月%d日 %s",
            today.year, today.monthValue, today.dayOfMonth,
            weekdays[today.dayOfWeek.value - 1],
        )

        // ---- 周次 ----
        binding.tvWeekBig.text = if (state.hasAnyData) {
            "第 ${state.week} 周 · 今天"
        } else {
            "尚未同步"
        }

        // ---- 同步状态 ----
        binding.tvSyncState.text = if (state.hasAnyData) {
            state.lastSyncDisplay?.let { "上次同步：$it" } ?: "已同步"
        } else {
            "登录后同步你的课程"
        }
        // 按钮文案随状态变化（按钮本身在 XML 定义，见 ADR-023）
        binding.btnCardAction.text = if (state.hasAnyData) "重新同步" else "登录教务系统"
        // ---- 今日课程 ----
        val todayCourses = state.todayCourses
        binding.tvTodayCount.text = "${todayCourses.size} 门"

        val hasToday = todayCourses.isNotEmpty()
        binding.todayList.visibility = if (hasToday) View.VISIBLE else View.GONE
        binding.todayEmpty.visibility = if (hasToday) View.GONE else View.VISIBLE

        // 区分「今天没课」与「没同步」
        // ⚠️ ADR-105：若有调课，文案要体现出来（否则用户以为调课没生效）
        binding.tvEmptyText.text = when {
            !state.hasAnyData -> "尚未同步课表"
            state.dayOverride is DayOverride.NoClass -> "今天调课：无课"
            else -> "今天没有课，休息一下"
        }

        // ⚠️ ADR-105：调课入口 + 状态提示
        //    · 空状态卡片上的按钮
        //    · 有调课时在标题旁显示提示
        binding.btnDayOverrideEmpty.visibility =
            if (state.hasAnyData) View.VISIBLE else View.GONE
        binding.btnDayOverrideEmpty.setOnClickListener {
            showDayOverrideDialog(state)
        }

        // 有调课时，把「今日课程」的计数文案改成提示
        // ⚠️ ADR-108：这块现在可点击 → 弹调课弹窗（用户要求）
        binding.tvTodayCount.text = if (state.dayOverride is DayOverride.UseDay) {
            val d = (state.dayOverride as DayOverride.UseDay).sourceDay
            "${todayCourses.size} 门 · 按${DayOverride.dayName(d)}"
        } else {
            "${todayCourses.size} 门"
        }
        binding.tvTodayCount.setOnClickListener {
            showDayOverrideDialog(state)
        }

        binding.tvUpdatedAt.text = if (state.hasAnyData) {
            state.lastSyncDisplay?.let { "更新于 $it" } ?: ""
        } else {
            "尚未更新"
        }

        // ⚠️ ADR-106：今日所有课的倒计时列表
        //    （取代旧的单个 tvNextCourseCountdown）
        refreshCountdowns()

        adapter.submit(todayCourses)

        // ---- 学业数据卡片（ADR-009 方案 A：无数据整块隐藏）----
        renderGrades(state.recentGrades)
        renderExams(state.upcomingExams)
    }

    /**
     * 弹出「当日调课」选择（ADR-105）。
     *
     * ⚠️ 用户需求：
     *    「那一天突然调了，比如星期一的课，我就选择周一，
     *      那天的课表就会临时变成周一的课。适用于周六日调课」
     *    「周四五突然没课，我也可以在今日课表里选择无，那一天就没课了」
     */
    private fun showDayOverrideDialog(state: TodayUiState) {
        val date = state.overrideDate ?: return
        DayOverrideDialog.show(
            context = requireContext(),
            date = date,
            current = state.dayOverride,
        ) { picked ->
            viewModel.setDayOverride(date, picked)
        }
    }

    /**
     * 计算「下一节课」的倒计时文案（ADR-109）。
     *
     * ⚠️ 用户需求：
     *    「只显示最近一节课的倒计时 其他的不要
     *      上完最近一节课之后倒计时自动变为距离下一节课的时间」
     *
     * ⚠️ 「自动切换」的原理：每次调用都按 `start.isAfter(now)` 重新筛选，
     *    最近那节一旦开课就被排除 → 自动指向下一节。**无需任何定时器。**
     *
     * ⚠️ 关键：**在 UI 层自己算**，不依赖 ViewModel 的 state。
     *    state 只在 load() 时更新（异步读库），心跳触发时可能拿到旧值 → 数字跳变。
     *
     * @return 「距上课 X」文案 + 对应课程 id；今天没有未开始的课则 null
     */
    private fun nextCountdown(): Pair<String, String>? {
        val now = java.time.LocalDateTime.now()
        val today = now.toLocalDate()
        val courses = viewModel.state.value.todayCourses

        val next = courses
            .mapNotNull { c ->
                val start = CourseCountdown.startOf(today, c.startPeriod)
                if (start == null || !start.isAfter(now)) null else c to start
            }
            .minByOrNull { it.second }
            ?: return null

        val (course, start) = next
        val text = CourseCountdown.text(now, start) ?: return null
        // ⚠️ 用「节次+课程名」做 key —— 同名课在不同节次也能区分
        return text to "${course.startPeriod}|${course.name}"
    }

    /**
     * 刷新所有课程卡片的倒计时（ADR-109）。
     *
     * ⚠️ 只有「下一节课」那张卡片显示倒计时，其余全部隐藏。
     */
    private fun refreshCountdowns() {
        val next = nextCountdown()
        adapter.setNextCountdownKey(next?.second, next?.first)
    }

    /** 渲染「最近成绩」。空列表则隐藏整个区块。 */
    private fun renderGrades(grades: List<Grade>) {
        val list = binding.gradeList
        list.removeAllViews()

        val show = grades.isNotEmpty()
        binding.gradeSection.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        val inflater = LayoutInflater.from(requireContext())
        grades.forEach { g ->
            val row = ItemGradeHomeBinding.inflate(inflater, list, false)
            row.tvGradeName.text = g.courseName
            row.tvGradeMeta.text = buildList {
                if (g.credit.isNotBlank()) add("${g.credit} 学分")
                if (g.category.isNotBlank()) add(g.category)
            }.joinToString(" · ")
            row.tvGradeScore.text = g.score
            list.addView(row.root)
        }
    }

    /**
     * 渲染「近期考试」（ADR-049）。
     *
     * ⚠️ 用户要求：「显示考试信息 没有就写近期无考试 有考试的话提前一个月显示」。
     *    故区块**始终显示**：有考试列出，没有则显示「近期无考试」。
     *    「提前一个月」的筛选在 ViewModel 里做（只留未来 30 天内的）。
     */
    private fun renderExams(exams: List<Exam>) {
        val list = binding.examList
        list.removeAllViews()

        val hasAny = exams.isNotEmpty()
        binding.tvExamEmpty.visibility = if (hasAny) View.GONE else View.VISIBLE
        // 「全部」入口仅在真有考试时才有意义
        binding.btnAllExams.visibility = if (hasAny) View.VISIBLE else View.GONE
        if (!hasAny) return

        val inflater = LayoutInflater.from(requireContext())
        val dateFmt = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
        val today = LocalDate.now()

        exams.forEach { e ->
            val row = ItemExamHomeBinding.inflate(inflater, list, false)
            row.tvExamName.text = e.courseName
            row.tvExamMeta.text = buildList {
                // ⚠️ ADR-049：附上倒计时（如「3 天后」），比纯日期更直观
                UpcomingExamFilter.countdownText(e.date, today)?.let { add(it) }
                e.date?.let { add(it.format(dateFmt)) }
                e.startTime?.let { add(it.format(timeFmt)) }
                if (e.location.isNotBlank()) add(e.location)
            }.joinToString(" · ")
            row.tvExamType.text = e.format.ifBlank { "考试" }
            list.addView(row.root)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
