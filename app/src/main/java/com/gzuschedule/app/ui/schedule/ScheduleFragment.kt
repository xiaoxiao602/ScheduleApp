package com.gzuschedule.app.ui.schedule

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.google.android.material.card.MaterialCardView
import com.gzuschedule.app.R
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.data.local.CardStyleStore
import com.gzuschedule.app.domain.CourseColorAssigner
import com.gzuschedule.app.domain.HolidayCalendar
import com.gzuschedule.app.domain.PeriodTime
import com.gzuschedule.app.data.local.LocalScheduleRepository
import com.gzuschedule.app.databinding.FragmentScheduleBinding
import com.gzuschedule.app.databinding.ItemDateChipBinding
import com.gzuschedule.app.databinding.ItemWeekCourseBinding
import com.gzuschedule.app.databinding.ItemWeekOptionBinding
import com.gzuschedule.app.databinding.SheetWeekPickerBinding
import com.gzuschedule.app.domain.model.Course
import com.gzuschedule.app.ui.widget.CourseDetailDialog
import kotlinx.coroutines.launch

/**
 * 周课表页 —— App 的主界面（ADR-062 重构：网格 → 按天列表）。
 *
 * 数据来自本地数据库；无数据时显示空状态并引导去「设置」同步（ADR-005）。
 *
 * ⚠️⚠️ 本次重构的背景（重要，勿回退）：
 *   用户对「周课表设计」反馈了 3 轮：
 *     第 1 轮 → 改配色（浅底深字）
 *     第 2 轮 → 再改配色（两级文字层次）—— 仍是同一架构，所以还是不对
 *     第 3 轮 → 用户直接甩了一张他认可的截图，要求「照这个样子做」
 *
 *   结论：**问题不在配色数值，在于「网格」这个形态本身**。
 *     7 列 × 8 行的网格里，一个格子宽度只有屏幕的 1/7 左右，
 *     塞「课名 + 教室 + 教师 + 时间」4 行信息必然折叠（用户原话「字都折叠了」）。
 *     在这么小的容器里调字号/调色值，永远是拆东墙补西墙。
 *
 *   故本次**换架构**：
 *     · 去掉网格
 *     · 顶部加横向日期条（照用户截图）
 *     · 下方改为**整屏宽的大卡片**，按天分组列出
 *   容器宽了 7 倍，课程名自然不再折叠 —— 这才是该问题的根本解。
 *
 * ⚠️ C3 交互（用户选定）：
 *   日期条可点选 → 高亮那天并滚动过去；
 *   但下方仍**按整周**列出全部课程（按天分组）——
 *   既还原了用户截图那种"日期条 + 大卡片"的观感，
 *   又不会像纯"只显示选中日"那样丢掉"一周全貌"。
 */
class ScheduleFragment : Fragment() {

    private var _binding: FragmentScheduleBinding? = null
    private val binding get() = _binding!!

    /**
     * 课程名 → 调色板下标（ADR-050）。
     *
     * ⚠️ 在 [render] 里按当前学期的**全部课程**统一分配，
     *    这样不同课不会撞色（旧实现按各自 hashCode 取模，必然撞）。
     */
    private var assignedColors: Map<String, Int> = emptyMap()

    /**
     * 卡片配色模式（ADR-064，用户可在设置页自选）。
     *
     * ⚠️ 在 [onResume] 重新读取 —— 用户从设置页切完返回时，
     *    本 Fragment 可能没被重建（实例缓存），不重读会看不到变化。
     */
    private lateinit var cardStyleStore: CardStyleStore
    private var cardStyle: CardStyleStore.Style = CardStyleStore.DEF_STYLE

    /**
     * 自定义颜色的"指纹"（ADR-070）。
     *
     * ⚠️ 用来判断用户是否改过任何一门课的颜色。
     *    课程名 + 色值的拼接串，变化即触发重绘。
     */
    private var colorStamp: String = ""

    /** 用户点选的日期（1=周一 … 7=周日）。null = 未手动选，默认高亮今天。 */
    private var selectedDay: Int? = null

    /**
     * 当前渲染出的「天 → 该天第一个卡片的 View」索引。
     * 点日期条时用它滚动到对应位置（C3 的"快速定位"部分）。
     */
    private val dayAnchors = HashMap<Int, View>()

    private lateinit var viewModel: ScheduleViewModel

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentScheduleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        cardStyleStore = CardStyleStore(requireContext())
        cardStyle = cardStyleStore.style

        val db = AppDatabase.get(requireContext())
        viewModel = ViewModelProvider(
            this,
            ScheduleViewModel.Factory(
                repo = LocalScheduleRepository(db),
                metaReader = { key -> db.metaDao().get(key) },
            ),
        )[ScheduleViewModel::class.java]

        // ⚠️ ADR-075：周次控件已从 MaterialButtonToggleGroup 换成
        //    自定义 LinearLayout（三段：‹ / 文字 / ›）。
        //    原先这里有一段 `addOnButtonCheckedListener { clearChecked() }`
        //    用于清除 ToggleGroup 的选中态 —— 换成普通布局后该方法不存在，
        //    且语义上也不再需要（三个都是纯命令按钮，没有"选中"概念），故删除。

        binding.btnPrevWeek.setOnClickListener {
            // ⚠️ 切周后"选中日"要重置 —— 否则会保留上一周选的星期几，
            //    但用户真正想看的通常是"今天"，切周后按今天高亮更符合预期。
            selectedDay = null
            viewModel.previousWeek()
        }
        binding.btnNextWeek.setOnClickListener {
            selectedDay = null
            viewModel.nextWeek()
        }
        binding.btnGoSync.setOnClickListener { goToProfile() }

        // 点周次标签 → 弹周次选择器（ADR-058）。一次跳任意周。
        binding.btnWeekPicker.setOnClickListener { showWeekPicker() }
        // 「回到本周」= 取消手动切换
        binding.btnBackToNow.setOnClickListener {
            selectedDay = null
            viewModel.resetToCurrentWeek()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun goToProfile() {
        (activity as? com.gzuschedule.app.ui.main.MainActivity)?.navigateToSettings()
    }

    private fun render(state: ScheduleUiState) {
        binding.progress.visibility = if (state.isLoading) View.VISIBLE else View.GONE

        val hasData = state.courses.isNotEmpty()
        binding.scrollContent.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.emptyState.visibility = if (hasData) View.GONE else View.VISIBLE
        binding.weekBar.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.headerBlock.visibility = if (hasData) View.VISIBLE else View.GONE
        binding.dateStrip.visibility = if (hasData) View.VISIBLE else View.GONE

        if (!hasData) return

        // ⚠️ ADR-050：先按【全部课程】统一分配颜色，再渲染卡片。
        //    必须在建卡片之前 —— 否则各卡片自己算色仍会撞。
        assignedColors = CourseColorAssigner.assign(
            state.courses.map { it.name },
            COURSE_PALETTE.size,
        )

        renderHeader(state)
        renderDateStrip(state)
        renderDayList(state)
    }

    /** 渲染标题区 + 周切换条（ADR-062 照用户截图重新排版）。 */
    private fun renderHeader(state: ScheduleUiState) {
        binding.tvTermName.text = localizeTerm(state.termDisplayName)

        // ⚠️⚠️ ADR-075：文字层级重排（用户反馈「文字挤一起了」）。
        //
        //   旧排版：切换条里塞「2026年9月 · 第 3 周」，下面再一行
        //          「9/14 - 9/20 · 第 3/18 周」—— 两行都在说"什么时候"，
        //          信息重复且第一行过长（在 M3 按钮里被 padding 挤压）。
        //
        //   新排版：按"信息归属"重新分配 ——
        //          切换条（可点，是操作区）→ 只放 **周次**：「第 3 周」
        //          说明行（纯文本，是信息区）→ 放 **时间范围 + 进度**：
        //              「2026年9月 · 9/14 - 9/20 · 第 3/18 周」
        //
        //   ⚠️ 为什么月份挪到下面：月份属于"日期信息"，和日期区间放一起
        //      才是同类项；放在可点击的切换条里既不语义化，也撑爆了按钮宽度。
        val monday = firstMondayOrNull()?.plusWeeks((state.week - 1).toLong())
        binding.btnWeekPicker.text = "第 ${state.week} 周"

        val range = weekDateRange(state.firstMonday, state.week)
        val weekText = "第 ${state.week}/${state.totalWeeks} 周"
        val monthLabel = monday?.let { "${it.year}年${it.monthValue}月" }.orEmpty()
        binding.tvWeekLabel.text = buildString {
            if (monthLabel.isNotBlank()) append("$monthLabel · ")
            if (range != null) append("$range · ")
            append(weekText)
        }

        // ⚠️ ADR-076：改为**常驻显示，在本周时置灰**。
        //   旧做法是"翻走才显示" —— 时隐时现会让切换条右侧布局跳动，
        //   而且用户看不到这个功能存在，不知道该往哪看。
        //   置灰反而传达了信息："你正在本周"。
        binding.btnBackToNow.isEnabled = !state.isViewingCurrentWeek
        binding.btnBackToNow.alpha = if (state.isViewingCurrentWeek) 0.38f else 1f

        // 边界置灰（而不是点了没反应）
        binding.btnPrevWeek.isEnabled = state.week > 1
        binding.btnNextWeek.isEnabled = state.week < state.totalWeeks
    }

    private fun localizeTerm(raw: String): String {
        if (raw.isBlank() || raw == "课表") return ""
        val m = Regex("""AY(\d{2})-(\d{2})\s*Term(\d)""").find(raw) ?: return raw
        val (y1, y2, t) = m.destructured
        val termName = when (t) {
            "1" -> "第一学期"
            "2" -> "第二学期"
            "3" -> "第三学期"
            else -> "第${t}学期"
        }
        return "20$y1-20$y2 学年 · $termName"
    }

    /** 该周日期区间（`9/14 - 9/20`）；缺第一周周一时返回 null。 */
    private fun weekDateRange(firstMonday: String?, week: Int): String? {
        val start = firstMondayOrNull()?.plusWeeks((week - 1).toLong()) ?: return null
        val end = start.plusDays(6)
        return "${start.format(HEADER_DATE_FMT)} - ${end.format(HEADER_DATE_FMT)}"
    }

    /**
     * 横向日期条（ADR-062 新增，照用户截图）。
     *
     * 形态：周三16 / 周四17 / 周五18 …，每个下面标「2门」。
     * 选中日 → 深蓝实底 + 白字；今天 → 深蓝字（未选中时也认得出）。
     *
     * ⚠️ 手动 inflate 而非 RecyclerView：固定 7 个，用 RecyclerView 是过度设计。
     */
    @SuppressLint("SetTextI18n")
    private fun renderDateStrip(state: ScheduleUiState) {
        binding.dateStrip.removeAllViews()
        dayAnchors.clear()

        val monday = firstMondayOrNull()?.plusWeeks((state.week - 1).toLong())
        val todayIdx = LocalDate.now().dayOfWeek.value
        val isCurrentWeek = state.isViewingCurrentWeek
        // 未手动选过：默认高亮"今天"（仅当正在看本周）
        val active = selectedDay ?: if (isCurrentWeek) todayIdx else null

        for (day in 1..7) {
            val chip = ItemDateChipBinding.inflate(
                LayoutInflater.from(requireContext()),
                binding.dateStrip,
                false,
            )
            val date = monday?.plusDays((day - 1).toLong())
            val isSelected = day == active

            chip.tvDayLabel.text = DAY_LABELS[day - 1]
            chip.tvDayNum.text = date?.dayOfMonth?.toString() ?: "-"

            // 课程数（假日当天算 0 —— 用户选「假期就当没课」，见 ADR-060）
            val isHoliday = monday?.let { state.holidays.holidaysOfWeek(it).containsKey(day) } == true
            val count = if (isHoliday) 0 else {
                state.courses.count { it.dayOfWeek == day && it.occursInWeek(state.week) }
            }
            chip.tvDayCount.text = "${count}门"

            applyChipState(chip, isSelected, day == todayIdx && isCurrentWeek)

            chip.root.setOnClickListener {
                selectedDay = day
                // 只刷新日期条 + 滚动定位，不重建列表（避免闪烁）
                renderDateStrip(viewModel.state.value)
                scrollToDay(day)
            }
            binding.dateStrip.addView(chip.root)
        }
    }

    /**
     * 日期条单元格三态着色（ADR-062）。
     *
     * ⚠️ 选中态与"今天"标记互斥，不会打架：
     *     选中     → 深蓝底 + 白字（最高优先级）
     *     今天未选 → 浅灰底 + 深蓝字
     *     普通     → 浅灰底 + 深灰字
     */
    private fun applyChipState(chip: ItemDateChipBinding, isSelected: Boolean, isToday: Boolean) {
        val ctx = requireContext()
        if (isSelected) {
            chip.root.setBackgroundResource(R.drawable.bg_date_chip_selected)
            val white = ContextCompat.getColor(ctx, R.color.date_chip_on_selected)
            chip.tvDayLabel.setTextColor(white)
            chip.tvDayNum.setTextColor(white)
            chip.tvDayCount.setTextColor(white)
        } else {
            chip.root.setBackgroundResource(R.drawable.bg_date_chip)
            chip.tvDayLabel.setTextColor(
                ContextCompat.getColor(ctx, R.color.date_chip_label),
            )
            val numColor = if (isToday) {
                ContextCompat.getColor(ctx, R.color.date_chip_today)
            } else {
                ContextCompat.getColor(ctx, R.color.date_chip_num)
            }
            chip.tvDayNum.setTextColor(numColor)
            chip.tvDayCount.setTextColor(
                ContextCompat.getColor(ctx, R.color.date_chip_count),
            )
        }
    }

    /**
     * 按天分组列出整周课程（ADR-062 核心）。
     *
     * ⚠️ 为什么要"分组"而不是平铺：
     *   整宽的卡片如果连续排 12 张，用户看不出"哪天到哪天"。
     *   插一个小标题（「周三 · 2 门」）把天分开，滚动时天然形成分节感，
     *   也让"日期条点选 → 滚过去"有了明确的落点。
     *
     * ⚠️ 只渲染**有课的天**：7 个标题里 3 个写"0 门"是纯噪音。
     *    空周（整周没课）显示一句提示，而不是七个空标题。
     */
    private fun renderDayList(state: ScheduleUiState) {
        binding.dayList.removeAllViews()

        val monday = firstMondayOrNull()?.plusWeeks((state.week - 1).toLong())
        val weekHolidays = monday?.let { state.holidays.holidaysOfWeek(it) } ?: emptyMap()

        var renderedDays = 0
        for (day in 1..7) {
            // ⚠️ ADR-060：假日当天不给课程 —— 用户明确选「就当没课」
            if (weekHolidays.containsKey(day)) continue

            val dayCourses = state.courses
                .filter { it.dayOfWeek == day && it.occursInWeek(state.week) }
                .sortedBy { it.startPeriod }
            if (dayCourses.isEmpty()) continue

            val date = monday?.plusDays((day - 1).toLong())
            binding.dayList.addView(dayHeader(day, date, dayCourses.size))

            for (course in dayCourses) {
                val card = courseCard(course)
                if (dayAnchors[day] == null) dayAnchors[day] = card
                binding.dayList.addView(card)
            }
            renderedDays++
        }

        if (renderedDays == 0) {
            binding.dayList.addView(
                TextView(requireContext()).apply {
                    text = "本周没有课程"
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, dp(48), 0, dp(48))
                    setTextColor(ContextCompat.getColor(requireContext(), R.color.schedule_time_text))
                },
            )
        }
    }

    /** 某天的分组标题：「周三 16日 · 2 门」。 */
    private fun dayHeader(day: Int, date: LocalDate?, count: Int): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, dp(8))
        }
        val isToday = date != null && date == LocalDate.now()

        row.addView(
            TextView(ctx).apply {
                text = buildString {
                    append("周${DAY_LABELS[day - 1].removePrefix("周")}")
                    date?.let { append(" ${it.dayOfMonth}日") }
                    if (isToday) append(" · 今天")
                }
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        if (isToday) R.color.brand_primary else R.color.schedule_header_text,
                    ),
                )
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        row.addView(
            TextView(ctx).apply {
                text = "$count 门"
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.schedule_time_text))
            },
        )
        return row
    }

    /**
     * 整宽大课程卡片（ADR-062）。
     *
     * ⚠️ 与旧版网格卡片的根本区别：**宽度从 1/7 屏变成整屏**。
     *    这是"课程名不再折叠"的根本原因 —— 不是靠调字号，
     *    而是靠给它足够的宽度。
     *
     * ⚠️ 配色：饱和底 + 白字（course_1..course_10），
     *    左侧竖条用**同色相更深一档**，形成同色系深浅搭配。
     */
    private fun courseCard(course: Course): View {
        val ctx = requireContext()
        val item = ItemWeekCourseBinding.inflate(LayoutInflater.from(ctx))
        val baseColor = courseColor(course.name)

        item.tvName.text = course.name

        // 「1-2节 1-13周,15-16周」—— 节次 + 周次范围（照用户截图）
        item.tvTime.text = buildString {
            append(PeriodTime.range(course.startPeriod, course.endPeriod))
            val weeks = weekRangeLabel(course)
            if (weeks.isNotBlank()) append("  $weeks")
        }
        item.tvTime.visibility = if (item.tvTime.text.isBlank()) View.GONE else View.VISIBLE

        item.tvLocation.text = course.location
        item.tvLocation.visibility =
            if (course.location.isBlank()) View.GONE else View.VISIBLE

        item.tvTeacher.text = course.teacher
        item.tvTeacher.visibility = if (course.teacher.isBlank()) View.GONE else View.VISIBLE

        // ⚠️ ADR-063：颜色只上在**左侧色条**上，卡片底统一近白。
        //    上一版把整块底色染成课程色 → 用户反馈「好丑」。
        //    整列都是高彩度色块会显得花、吵；把颜色收缩成一条窄色条后，
        //    页面整体是干净的白色系，颜色只做点缀（iOS 日历 / Notion 的做法）。
        //    色条用**高饱和原色**（不压暗）—— 它面积小，需要足够醒目。
        // ⚠️ ADR-069：三种卡片样式的色条处理
        //    SOLID      → 整块已是课程色，色条多余 → 隐藏
        //    ACCENT_BAR → 显示，且**加粗**（用户要求"比现在再粗一点"）
        //    PLAIN      → 纯白底，不要任何装饰 → 隐藏
        item.accentBar.setBackgroundColor(baseColor)
        when (cardStyle) {
            CardStyleStore.Style.ACCENT_BAR -> {
                item.accentBar.visibility = View.VISIBLE
                // 加粗：XML 默认 5dp，这里按样式调到 8dp
                item.accentBar.layoutParams = (item.accentBar.layoutParams).apply {
                    width = dp(ACCENT_BAR_WIDTH_DP)
                }
            }
            else -> item.accentBar.visibility = View.GONE
        }

        // ⚠️⚠️ ADR-074：文字颜色必须随卡片样式变，否则彩色底上黑字看不清。
        //   SOLID      → 白字（彩底上黑字对比度不足）
        //   ACCENT_BAR → 深字（白底上白字会"消失"）
        //   PLAIN      → 深字
        //   这与 values/course_colors.xml 里的 course_cell_title/meta 保持一致 ——
        //   那边定义的就是"浅底深字"，彩底白字由代码覆盖。
        val isSolid = cardStyle == CardStyleStore.Style.SOLID
        val titleColor = if (isSolid) 0xFFFFFFFF.toInt()
            else ContextCompat.getColor(ctx, R.color.course_cell_title)
        val metaColor = if (isSolid) 0xCCFFFFFF.toInt()   // 白 80%
            else ContextCompat.getColor(ctx, R.color.course_cell_meta)

        item.tvName.setTextColor(titleColor)
        item.tvTime.setTextColor(metaColor)
        item.tvLocation.setTextColor(metaColor)
        item.tvTeacher.setTextColor(metaColor)

        // 色条在 SOLID 下隐藏；但为防复用残留，先统一染色
        item.accentBar.setBackgroundColor(baseColor)

        val card = MaterialCardView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            // ⚠️ ADR-085：14 → 18dp（用户要求「课程卡片的圆角加大一点点」）。
            //    与 dimens_cards.xml 的 card_corner_medium(20dp) 接近，
            //    但略小 —— 课表卡片密集排列，圆角太大反而显得松散。
            radius = dp(18).toFloat()
            cardElevation = 0f

            setContentPadding(0, 0, 0, 0)
            useCompatPadding = false
            preventCornerOverlap = false
            // ⚠️ 卡片底色由配色模式决定（ADR-064 用户自选）：
            //    实色 → 整块课程色；浅色 → 近白底 + 左侧色条
            setCardBackgroundColor(
                if (cardStyle == CardStyleStore.Style.SOLID) baseColor
                else ContextCompat.getColor(ctx, R.color.course_cell_bg),
            )
            // PLAIN 样式不要描边（纯白卡片，边界靠阴影区分）；其余样式也没描边
            strokeWidth = 0
            isClickable = true
            isFocusable = true
            // 点击进详情（沿用既有弹窗）
            setOnClickListener { CourseDetailDialog.show(requireContext(), course) }
        }
        card.addView(
            item.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return card
    }

    /** 周次范围标签：全周次时不显示（避免"1-18周"这种废话）。 */
    private fun weekRangeLabel(course: Course): String {
        val total = viewModel.state.value.totalWeeks
        val parity = when (course.weekParity) {
            1 -> "单周"
            2 -> "双周"
            else -> ""
        }
        // 恰好覆盖整学期 → 不写周次（用户截图里"每周都上"的课也没标）
        if (course.startWeek <= 1 && course.endWeek >= total && parity.isEmpty()) return ""
        val range = if (course.startWeek == course.endWeek) {
            "第${course.startWeek}周"
        } else {
            "${course.startWeek}-${course.endWeek}周"
        }
        return if (parity.isEmpty()) range else "$range($parity)"
    }

    /** 把颜色按比例压暗（用于左侧竖条）。 */
    private fun darken(color: Int, factor: Float): Int = ColorUtils.blendARGB(
        color,
        Color.BLACK,
        1f - factor,
    )

    /**
     * 周次选择器（ADR-058）。
     *
     * ⚠️ 用 items 列表而不是 NumberPicker：
     *    单选列表直接一屏看到底，点击即达 —— 这才是「切周方便」。
     */
    /**
     * 周次选择器（ADR-076：改用 BottomSheet）。
     *
     * ⚠️⚠️ 替换原来的 AlertDialog + setSingleChoiceItems：
     *   那个 API 是 Android 最早期形态 —— 弹在屏幕**中间**（大屏单手够不到）、
     *   原生 radio 圆点（暗示"先选再确认"，但这个交互是"点一下就走"）、
     *   长列表在小框里滚动。用户评价「丑爆了」。
     *
     *   ✅ BottomSheet：
     *      · 底部升起 → 落在拇指覆盖区
     *      · 圆角 + 拖拽把手 → M3 标准
     *      · 列表项 = 周次(粗体) + 日期(灰) + 今天徽标/选中勾
     *      · 当前查看的周有背景高亮
     */
    /**
     * 周次选择器（ADR-078：Compose 滚轮）。
     *
     * ⚠️ 用 ComposeView 承载 `WheelPicker` @Composable。
     *    滚轮本体在 ui/widget/WheelPicker.kt，基于官方 API：
     *      · LazyColumn + rememberSnapFlingBehavior → 惯性滑动 + 吸附
     *      · graphicsLayer { rotationX / scaleY / alpha } → 3D 滚筒透视
     *
     * ⚠️ 交互是「滚 + 确定」两段式（同 iOS UIPickerView）：
     *    滚动过程中值一直在变，若边滚边跳页，用户每次滑动都会触发跳转，
     *    体验很乱。故只在点「确定」时应用。
     */
    /**
     * 周次选择器（ADR-081：普通滑动列表）。
     *
     * ⚠️ 本轮决策：滚轮方案（自研 / compose-wheelpicker）均因
     *    依赖链或版本兼容问题搁置，改回列表 —— 功能最可靠。
     *
     * ⚠️ 用 NestedScrollView 承载列表（见 sheet_week_picker.xml）：
     *    BottomSheetDialog 自身有拖拽手势，普通 ScrollView 会与之冲突
     *    （滑动卡住/乱跳）。NestedScrollView 能正确协商嵌套滚动 ——
     *    先滚列表，列表到底才拖动弹窗。这是"滑动有 bug"的正确解法。
     *
     * ⚠️ 交互：点某行 → 立即跳转并关闭（列表版不需要"确定"按钮）。
     */
    private fun showWeekPicker() {
        val s = viewModel.state.value
        if (s.totalWeeks <= 0) return

        val b = SheetWeekPickerBinding.inflate(layoutInflater)
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(requireContext())
        sheet.setContentView(b.root)
        sheet.behavior.state =
            com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED

        val primary = androidx.core.content.ContextCompat.getColor(
            requireContext(), R.color.brand_primary,
        )
        val normal = androidx.core.content.ContextCompat.getColor(
            requireContext(), R.color.schedule_header_text,
        )

        for (w in 1..s.totalWeeks) {
            val item = ItemWeekOptionBinding.inflate(layoutInflater, b.weekList, false)
            item.tvWeekNum.text = "第 $w 周"
            item.tvWeekRange.text = weekDateRange(s.firstMonday, w).orEmpty()

            val isToday = w == s.currentWeek
            val isCurrent = w == s.week

            item.tvTodayBadge.visibility = if (isToday) View.VISIBLE else View.GONE
            item.checkMark.visibility = if (isCurrent) View.VISIBLE else View.GONE

            if (isCurrent) {
                item.root.setBackgroundResource(R.drawable.bg_week_option_selected)
            }
            item.tvWeekNum.setTextColor(if (isCurrent) primary else normal)
            item.tvWeekRange.setTextColor(normal)

            item.root.setOnClickListener {
                selectedDay = null
                viewModel.goToWeek(w)
                sheet.dismiss()
            }
            b.weekList.addView(item.root)
        }

        sheet.show()

        // ⚠️ 滚动到"当前查看的周"，避免用户在 18 周列表里自己找。
        //    用父容器（NestedScrollView）的 scrollTo 而不是 smooth —— 弹窗
        //    刚 show 出来时布局可能还没完成，smooth 动画会从错误位置开始。
        b.weekList.post {
            val idx = (s.week - 1).coerceIn(0, s.totalWeeks - 1)
            val target = b.weekList.getChildAt(idx) ?: return@post
            (b.weekList.parent as? androidx.core.widget.NestedScrollView)
                ?.scrollTo(0, target.top)
        }
    }

    /**
     * 滚动到指定星期（ADR-062 修复「点卡片不滚动」）。
     *
     * ⚠️⚠️ 旧实现 `smoothScrollTo(0, anchor.top)` 是**错的**：
     *   `View.top` 是相对**直接父容器**的坐标。ScrollView 内部还有一层
     *   LinearLayout，导致算出的滚动位置偏小、甚至完全不滚。
     *
     *   ✅ 正确做法：用 `getLocationInWindow` 求出锚点在**屏幕上的 Y**，
     *      再减去 ScrollView 的屏幕 Y，得到锚点在**滚动内容里的真实偏移**。
     *      这是唯一不依赖中间父容器层数的算法。
     */
    private fun scrollToDay(day: Int) {
        val anchor = dayAnchors[day] ?: return
        val sv = binding.scrollContent
        sv.post {
            val anchorLoc = IntArray(2)
            val svLoc = IntArray(2)
            anchor.getLocationInWindow(anchorLoc)
            sv.getLocationInWindow(svLoc)
            // 锚点相对 ScrollView 可视区顶部 + 已滚动距离 = 内容里的真实 Y
            val relativeY = anchorLoc[1] - svLoc[1] + sv.scrollY
            // 留 8dp 余量，避免标题紧贴屏幕顶部
            sv.smoothScrollTo(0, (relativeY - dp(8)).coerceAtLeast(0))
        }
    }

    private fun firstMondayOrNull(): LocalDate? = runCatching {
        viewModel.state.value.firstMonday?.let { LocalDate.parse(it) }
    }.getOrNull()

    /**
     * 课程配色（ADR-050 + ADR-062）。
     *
     * ⚠️ 用户反馈过「英语卡片和高数课的卡片颜色一致」。
     * 旧实现 `abs(name.hashCode()) % 8` 会**撞色**（生日问题，概率 >50%）。
     * 新实现由 [assignedColors] 统一分配，课程数 ≤ 色位数时**绝不撞色**。
     */
    private fun courseColor(courseName: String): Int {
        // ⚠️ ADR-065：用户自定义色**优先于**自动分配。
        //    顺序不能反 —— 否则用户设了颜色却不生效。
        cardStyleStore.customColorOf(courseName)?.let { return it }

        val idx = assignedColors[courseName]
            ?: CourseColorAssigner.assignFallback(courseName, COURSE_PALETTE.size)
        return ContextCompat.getColor(requireContext(), COURSE_PALETTE[idx % COURSE_PALETTE.size])
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 重新读取卡片外观配置并按需重绘。
     *
     * ⚠️⚠️ 这里是「改完不实时更新」的修复核心（ADR-070）。
     *
     *   问题：MainActivity 用 `show()/hide()` 切换 Fragment（ADR-050 的性能优化），
     *        Fragment **不会重建、也不会走 onResume** ——
     *        它一直是 STARTED，只是 visibility 变了。
     *        所以仅靠 onResume 重读配置**在切页场景下根本不会触发**。
     *
     *   ✅ 修复：两条路都接上
     *      · [onHiddenChanged] —— show/hide 切换时触发（从设置/外观页切回）
     *      · [onResume]        —— 从别的 Activity 返回时触发
     *      两者都会调 [reloadAppearanceIfChanged]，任一场景都能刷新。
     */
    /**
     * 重新加载「外观 + 学期基准」，必要时重绘（ADR-087）。
     *
     * ⚠️⚠️ 本方法修两个"改了不生效"的 bug：
     *
     *   ① 样式/颜色指纹比对（ADR-070 遗留）
     *      → 用户在「课程外观」页可能只改某门课颜色（样式没动），
     *        那时也必须重绘。用"自定义色指纹"判断。
     *
     *   ② **第一周星期一变更**（本轮用户反馈「改第一周首页不会动态刷新」）
     *      之前只比对 cardStyle / colorStamp，**完全没管 firstMonday**。
     *      用户在设置页改了学期起始日 → 周次、每张卡片的日期全部要重算，
     *      但本方法认为"什么都没变" → 不重绘 → 必须重启 App 才更新。
     *
     *      ✅ 现在把 firstMonday 也纳入指纹；且因为它影响**周次计算**，
     *         变了就必须让 ViewModel 重新读库（不只是重绘）。
     */
    private fun reloadAppearanceIfChanged() {
        if (!::cardStyleStore.isInitialized) return

        val latest = cardStyleStore.style
        val latestColorStamp = (viewModel.state.value.courses)
            .mapNotNull { c -> cardStyleStore.customColorOf(c.name)?.let { "${c.name}=$it" } }
            .sorted()
            .joinToString("|")

        // ⚠️ 第一周星期一由 ViewModel 从 DB 读，可能已被设置页改过。
        //    这里让 ViewModel 重新加载一次（它会重新读 FIRST_MONDAY 并重算周次）。
        viewModel.refreshSemesterBase()

        if (latest != cardStyle || latestColorStamp != colorStamp) {
            cardStyle = latest
            colorStamp = latestColorStamp
            render(viewModel.state.value)
        }
    }

    /**
     * show/hide 切换时回调（ADR-070）。
     *
     * ⚠️ 这是 Fragment 被 show/hide 时**唯一**可靠的回调。
     *    不用它的话，从「课程外观」页返回到课表页不会重绘。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // 只在自己**重新显示**时刷新
        if (!hidden) reloadAppearanceIfChanged()
    }

    /** 从别的 Activity（如「课程外观」页）返回时触发。 */
    override fun onResume() {
        super.onResume()
        reloadAppearanceIfChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        dayAnchors.clear()
        _binding = null
    }

    private companion object {
        /**
         * 课程调色板（10 色，ADR-062）。
         * ⚠️ 与 res/values/course_colors.xml 的 course_1..course_10 一一对应，
         *    顺序不可随意调整（调色板下标即此处顺序）。
         * ⚠️ 从 8 色扩到 10 色：用户本学期有 7 门课，8 色已够，
         *    但留 10 个冗余，课程更多的学期也能不撞色。
         */
        val COURSE_PALETTE = intArrayOf(
            R.color.course_1, R.color.course_2, R.color.course_3, R.color.course_4,
            R.color.course_5, R.color.course_6, R.color.course_7, R.color.course_8,
            R.color.course_9, R.color.course_10,
        )

        /** 日期条星期标签（照用户截图："周三" 而不是 "三"）。 */
        val DAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

        /**
         * 左侧色条宽度（dp，ADR-069）。
         * ⚠️ 用户要求「左侧色条相比现在再加粗一点」——
         *    原 XML 是 5dp，这里加大到 8dp（提升 60%），
         *    足以在白底卡片上形成明确的色带，又不会喧宾夺主。
         */
        const val ACCENT_BAR_WIDTH_DP = 8

        /** 标题日期格式（ADR-054）。 */
        private val HEADER_DATE_FMT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("M/d", Locale.CHINA)
    }
}