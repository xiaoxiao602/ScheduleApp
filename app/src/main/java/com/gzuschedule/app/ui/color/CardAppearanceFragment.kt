package com.gzuschedule.app.ui.color

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.gzuschedule.app.R
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.data.local.CardStyleStore
import com.gzuschedule.app.data.local.LocalScheduleRepository
import com.gzuschedule.app.databinding.DialogColorPickerBinding
import com.gzuschedule.app.databinding.FragmentCardAppearanceBinding
import com.gzuschedule.app.databinding.ItemCardStyleBinding
import com.gzuschedule.app.databinding.ItemColorSwatchBinding
import com.gzuschedule.app.databinding.ItemCourseColorBinding
import com.gzuschedule.app.domain.CourseColorAssigner
import com.gzuschedule.app.domain.CoursePalette
import com.gzuschedule.app.domain.model.Course
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.gzuschedule.app.ui.widget.DialogCorner

/**
 * 课程外观二级页（ADR-069）。
 *
 * 一个页面管两件事：
 *   ① 卡片样式 —— 三种方案（彩色填充 / 白底+色条 / 纯白底），带真实预览
 *   ② 课程配色 —— 逐门课指定颜色
 *
 * ⚠️⚠️ 为什么把「卡片样式」从弹窗搬到这里：
 *   用户原话：「有**三种**课程卡片的方案，放在**二级设置页面**里」。
 *   之前是设置页里的一个 AlertDialog 单选（2 个纯文字选项），
 *   现在改为独立二级页，三种方案各带**真实卡片预览** ——
 *   视觉差异用眼看比读描述快得多。
 */
class CardAppearanceFragment : Fragment() {

    private var _binding: FragmentCardAppearanceBinding? = null
    private val binding get() = _binding!!

    private lateinit var store: CardStyleStore
    private lateinit var repo: LocalScheduleRepository

    private var courses: List<Course> = emptyList()
    private var autoColors: Map<String, Int> = emptyMap()

    /** 预览卡片用的示意色（固定品牌蓝，不随课程变） */
    private val demoColor = 0xFF1E88E5.toInt()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentCardAppearanceBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        store = CardStyleStore(requireContext())
        repo = LocalScheduleRepository(AppDatabase.get(requireContext()))

        binding.btnResetColors.setOnClickListener { resetAllColors() }

        renderStyles()

        viewLifecycleOwner.lifecycleScope.launch {
            repo.observeTerms().collect { terms ->
                val term = terms.firstOrNull().orEmpty()
                courses = if (term.isBlank()) {
                    emptyList()
                } else {
                    // ⚠️ 用 first() 而不是 collect + return@collect ——
                    //    后者会让协程永久挂起在无限热流里（ADR-069 修的 bug）
                    repo.observeCourses(term).first().distinctBy { it.name }.sortedBy { it.name }
                }
                autoColors = CourseColorAssigner.assign(
                    courses.map { it.name },
                    CoursePalette.AUTO_ASSIGN.size,
                )
                renderCourses()
            }
        }
    }

    // ==================== 一、卡片样式 ====================

    /**
     * 渲染三个样式选项。
     *
     * ⚠️ 每个选项的预览卡片要**按该样式的真实效果**渲染：
     *    彩色填充 → 整块演示色 + 白色占位条
     *    白底+色条 → 白底 + 粗演示色条 + 深色占位条
     *    纯白底 → 白底 + 深色占位条（无任何颜色）
     *   预览与实物一致，用户才敢选。
     */
    private fun renderStyles() {
        binding.styleList.removeAllViews()
        val current = store.style

        CardStyleStore.ALL.forEach { style ->
            val row = ItemCardStyleBinding.inflate(layoutInflater, binding.styleList, false)
            row.tvStyleName.text = style.displayName
            row.tvStyleDesc.text = style.description

            // ---- 预览卡片按样式渲染 ----
            val isSolid = style == CardStyleStore.Style.SOLID
            val showBar = style == CardStyleStore.Style.ACCENT_BAR

            row.previewCard.setCardBackgroundColor(
                if (isSolid) demoColor
                else ContextCompat.getColor(requireContext(), R.color.course_card_surface),
            )
            row.previewBar.visibility = if (showBar) View.VISIBLE else View.GONE
            row.previewBar.setBackgroundColor(demoColor)

            // 选中标记
            row.checkMark.visibility = if (style == current) View.VISIBLE else View.GONE

            // ⚠️⚠️ ADR-071 修「点不了」：
            //   只给 row.root 绑点击是不够的 —— 布局里的 MaterialCardView
            //   会消费触摸事件，点在预览卡区域时事件不冒泡到根节点。
            //   虽然 XML 里已把子 View 设为 clickable=false，
            //   但为了彻底可靠，**所有子 View 都绑同一个点击**，
            //   这样无论用户点在哪一块都能生效。
            val onClick = View.OnClickListener {
                store.style = style
                renderStyles()          // 刷新选中态
            }
            row.root.setOnClickListener(onClick)
            row.previewCard.setOnClickListener(onClick)
            row.previewBar.setOnClickListener(onClick)
            row.tvStyleName.setOnClickListener(onClick)
            row.tvStyleDesc.setOnClickListener(onClick)

            binding.styleList.addView(row.root)
        }
    }

    // ==================== 二、课程配色 ====================

    private fun effectiveColor(courseName: String): Int {
        store.customColorOf(courseName)?.let { return it }
        val idx = autoColors[courseName]
            ?: CourseColorAssigner.assignFallback(courseName, CoursePalette.AUTO_ASSIGN.size)
        return CoursePalette.AUTO_ASSIGN[idx % CoursePalette.AUTO_ASSIGN.size]
    }

    private fun renderCourses() {
        binding.courseList.removeAllViews()

        if (courses.isEmpty()) {
            binding.courseList.addView(
                TextView(requireContext()).apply {
                    text = "还没有课程数据，请先在设置页同步"
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    setPadding(0, 48, 0, 0)
                    setTextColor(Color.GRAY)
                },
            )
            binding.btnResetColors.visibility = View.GONE
            return
        }

        courses.forEach { course ->
            val row = ItemCourseColorBinding.inflate(layoutInflater, binding.courseList, false)
            row.tvCourseName.text = course.name
            row.colorDot.background.setTint(effectiveColor(course.name))
            row.root.setOnClickListener { showColorPicker(course.name) }
            binding.courseList.addView(row.root)
        }

        binding.btnResetColors.visibility =
            if (store.hasAnyCustomColor()) View.VISIBLE else View.GONE
    }

    private var pendingDialog: AlertDialog? = null

    /** 选色：预设色板 + 自定义色轮。 */
    private fun showColorPicker(courseName: String) {
        val dlg = DialogColorPickerBinding.inflate(layoutInflater)
        val current = effectiveColor(courseName)
        val perRow = 4
        var row: LinearLayout? = null

        CoursePalette.PRESETS.forEachIndexed { i, color ->
            if (i % perRow == 0) {
                row = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                }
                dlg.presetGrid.addView(row)
            }
            val cell = ItemColorSwatchBinding.inflate(layoutInflater, row, false)
            cell.root.background.setTint(color)
            if (color == current) {
                cell.root.foreground = ContextCompat.getDrawable(
                    requireContext(), R.drawable.bg_color_swatch_selected,
                )
            }
            cell.root.setOnClickListener {
                store.setCustomColor(courseName, color)
                renderCourses()
                pendingDialog?.dismiss()
            }
            row!!.addView(cell.root)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(courseName)
            .setView(dlg.root)
            .setNeutralButton("恢复自动") { _, _ ->
                store.setCustomColor(courseName, null)
                renderCourses()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dlg.btnCustomColor.setOnClickListener {
            dialog.dismiss()
            showHsvPicker(courseName, current)
        }
        pendingDialog = dialog
        dialog.show()
        // ⚠️ ADR-098：必须在 show() 之后 —— window 才存在，否则圆角静默失效
        DialogCorner.applyRounded(dialog)
    }

    /** HSV 取色（三个滑块，无第三方依赖）。 */
    private fun showHsvPicker(courseName: String, initial: Int) {
        val ctx = requireContext()
        val dp = resources.displayMetrics.density
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)

        val preview = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (56 * dp).toInt(),
            ).apply { bottomMargin = (12 * dp).toInt() }
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_color_swatch)
            background.setTint(initial)
        }
        fun refresh() = preview.background.setTint(Color.HSVToColor(hsv))

        fun slider(label: String, max: Float, v: Float, onChange: (Float) -> Unit): LinearLayout {
            val s = com.google.android.material.slider.Slider(ctx).apply {
                valueFrom = 0f; valueTo = max; value = v
                addOnChangeListener { _, x, _ -> onChange(x) }
            }
            return LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(ctx).apply { text = label; textSize = 12f })
                addView(s)
            }
        }

        val h = slider("色相", 360f, hsv[0]) { hsv[0] = it; refresh() }
        val s = slider("饱和度", 1f, hsv[1]) { hsv[1] = it; refresh() }
        val v = slider("明度", 1f, hsv[2]) { hsv[2] = it; refresh() }

        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(preview); addView(h); addView(s); addView(v)
        }

        AlertDialog.Builder(ctx)
            .setTitle("自定义颜色")
            .setView(content)
            .setPositiveButton("应用") { _, _ ->
                store.setCustomColor(courseName, Color.HSVToColor(hsv))
                renderCourses()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { d ->
                d.show()
                // ⚠️ ADR-098：show() 之后才能设 Window 背景
                DialogCorner.applyRounded(d)
            }
    }

    private fun resetAllColors() {
        AlertDialog.Builder(requireContext())
            .setTitle("恢复默认配色")
            .setMessage("将清除所有课程的单独配色，回到自动分配。")
            .setPositiveButton("恢复") { _, _ ->
                store.clearAllCustomColors()
                renderCourses()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .also { d ->
                d.show()
                DialogCorner.applyRounded(d)
            }
    }


    override fun onDestroyView() {
        super.onDestroyView()
        pendingDialog = null
        _binding = null
    }
}