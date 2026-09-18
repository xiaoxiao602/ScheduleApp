package com.gzuschedule.app.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.gzuschedule.app.data.auth.LoginDiagnostics
import com.gzuschedule.app.R
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.data.local.DockTuningStore
import com.gzuschedule.app.data.local.UserProfileStore
import com.gzuschedule.app.ui.widget.ProfileEditDialog
import com.gzuschedule.app.data.local.CardStyleStore
import com.gzuschedule.app.ui.color.CourseColorActivity
import com.gzuschedule.app.ui.widget.MessageDialog
import com.gzuschedule.app.ui.widget.SettingsRow
import com.gzuschedule.app.data.local.entity.MetaEntity
import com.gzuschedule.app.domain.TimeFormats
import com.gzuschedule.app.domain.WeekCalculator
import java.time.LocalDate
import com.google.android.material.snackbar.Snackbar
import com.gzuschedule.app.databinding.FragmentSettingsBinding
import com.gzuschedule.app.ui.grade.GradeListActivity
import com.gzuschedule.app.ui.exam.ExamListActivity
import com.gzuschedule.app.ui.login.LoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「设置」页（ADR-006）—— 账号、同步、学业数据二级入口、数据管理。
 *
 * 登录入口在这里，App 启动不弹登录。
 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var db: AppDatabase

    private companion object {
        /**
         * 主题取色失败时的最后兜底（校徽蓝）。
         *
         * ⚠️ 正常路径取主题 colorPrimary（深浅自动适配）；
         * 只有 obtainStyledAttributes 完全取不到时才用它。
         */
        const val FALLBACK_AVATAR_COLOR = 0xFF06459B.toInt()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = AppDatabase.get(requireContext())

        binding.btnSync.setOnClickListener {
            startActivity(Intent(requireContext(), LoginActivity::class.java))
        }

        // ⚠️ ADR-045：设置项改为统一列表行（左图标 + 标题 + 右箭头）。
        //    文案与图标必须在代码里填 —— `<include>` 无法覆盖子 View 的
        //    android:text / android:src，只能覆盖 layout_* 属性。
        //
        // ⚠️ ViewBinding 会把带 id 的 <include> 生成为**子布局的 binding 对象**
        //    （ItemSettingsRowBinding），而不是 View —— 所以传 `.root`。
        SettingsRow.bind(binding.btnGrades.root, R.drawable.ic_grades, "成绩查询")
        SettingsRow.bind(binding.btnExams.root, R.drawable.ic_exams, "考试安排")
        SettingsRow.bind(binding.btnClearData.root, R.drawable.ic_delete, "清除本地数据")
        SettingsRow.bind(binding.btnDiagnostics.root, R.drawable.ic_diagnostics, "诊断信息")

        binding.btnGrades.root.setOnClickListener {
            startActivity(Intent(requireContext(), GradeListActivity::class.java))
        }
        binding.btnExams.root.setOnClickListener {
            startActivity(Intent(requireContext(), ExamListActivity::class.java))
        }
        binding.btnClearData.root.setOnClickListener { confirmClear() }
        binding.btnDiagnostics.root.setOnClickListener { toggleDiagnostics() }
        // ---- 课程外观（ADR-069）：二级页，含卡片样式 + 课程配色 ----
        // ⚠️ 原先这里是**两个**入口（一个弹窗选样式、一个二级页调配色），
        //    用户要求「三种卡片方案放在二级设置页面里」——故合并为一个二级页：
        //      卡片样式（3 种，带预览）+ 课程配色（逐门课）
        //    这样两项相关设置能连续调整、立即对照。
        SettingsRow.bind(
            binding.btnTheme.root,
            R.drawable.ic_palette,
            "课程外观",
        )
        binding.btnTheme.root.setOnClickListener {
            startActivity(Intent(requireContext(), CourseColorActivity::class.java))
        }

        // ---- Dock 外观调节（ADR-033）----
        setupDockTuning()

        // ---- 我的：点头像卡 → 个人资料编辑弹窗（ADR-046）----
        // ⚠️ 原先此处竖排三个按钮（修改显示名/更换头像/恢复默认头像）占掉约 150dp，
        //    现已收进弹窗；整张头像卡可点，右侧有 chevron 提示。
        binding.avatarProfile.setOnClickListener { showProfileEditDialog() }
        binding.btnFirstMonday.root.setOnClickListener { pickFirstMonday() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                refresh()
                renderProfileAsync()
                renderFirstMonday()
            }
        }
    }

    /**
     * 系统照片选择器（ADR-012）。
     *
     * ⚠️ 用 PickVisualMedia 而非旧的权限方案：
     * 系统进程展示图片并只授予选中那张的临时读取权，
     * **App 无需 READ_MEDIA_IMAGES 等任何存储权限**。
     */
    private val pickAvatar = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val ok = UserProfileStore.saveAvatar(requireContext(), uri.toString())
        if (ok) {
            renderProfile()
            MessageDialog.toast(requireContext(), "头像已更新")
        } else {
            MessageDialog.show(requireContext(), "保存失败", "头像没能保存成功，请换一张图片再试。")
        }
    }

    private fun launchAvatarPicker() {
        pickAvatar.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    /**
     * 渲染「我的」区域。
     *
     * 显示名（用户选择「并存」）：
     *   自定义名优先 → 为空回落到教务同步的真名 → 都无显示「未同步」。
     * 头像：有自定义则只显示图片；否则显示姓氏字 + 卡片同色底。
     */
    private suspend fun renderProfileAsync() {
        val custom = db.metaDao().get(AppDatabase.MetaKeys.DISPLAY_NAME)
        val real = db.metaDao().get(AppDatabase.MetaKeys.STUDENT_NAME)
        val no = db.metaDao().get(AppDatabase.MetaKeys.STUDENT_NO)
        val major = db.metaDao().get(AppDatabase.MetaKeys.STUDENT_MAJOR)
        val klass = db.metaDao().get(AppDatabase.MetaKeys.STUDENT_CLASS)

        val shown = custom?.takeIf { it.isNotBlank() }
            ?: real?.takeIf { it.isNotBlank() }
        val meta = listOfNotNull(
            no?.takeIf { it.isNotBlank() },
            (major ?: klass)?.takeIf { it.isNotBlank() },
        ).joinToString(" · ")

        withContext(Dispatchers.Main) {
            binding.tvDisplayName.text = shown ?: "未同步"
            binding.tvStudentMeta.text =
                meta.ifBlank { "登录并同步后显示学号与专业" }
            renderAvatar(shown)
        }
    }

    private fun renderProfile() {
        viewLifecycleOwner.lifecycleScope.launch { renderProfileAsync() }
    }

    /**
     * 头像底色取主题主色（与首页信息卡同色）。
     *
     * ⚠️ `colorPrimary` 由 **appcompat** 声明（不是 Material 的 R），
     * 也不是应用自己的 R.attr —— 后两者都会编译失败（本轮各踩一次）。
     */
    private fun renderAvatar(displayName: String?) {
        val ctx = requireContext()
        val cardColor = themeColor(ctx, androidx.appcompat.R.attr.colorPrimary)
        // ⚠️ ADR-046：avatarProfile 现在是**整张卡片**（可点区域），
        //    头像本体移到 avatarProfileImage。
        binding.avatarProfileImage.setFallbackColor(cardColor)
        binding.avatarProfileImage.bind(UserProfileStore.avatarFile(ctx), displayName)
    }

    /**
     * 打开「个人资料编辑」弹窗（ADR-046）。
     *
     * ⚠️ 用户反馈：「修改用户名 头像等功能没有图标，还是三行 浪费空间」。
     *    原先首屏竖排三个按钮吃掉约 150dp；现在整张头像卡可点，三项收进弹窗。
     *
     * 弹窗里展示大头像 + 当前名 + 学号专业，三个操作走统一列表行样式。
     */
    private fun showProfileEditDialog() {
        val ctx = requireContext()
        ProfileEditDialog.show(
            context = ctx,
            displayName = binding.tvDisplayName.text?.toString().orEmpty(),
            meta = binding.tvStudentMeta.text?.toString().orEmpty(),
            avatarFile = UserProfileStore.avatarFile(ctx),
            onEditName = { editDisplayName() },
            onPickAvatar = { launchAvatarPicker() },
            onResetAvatar = {
                UserProfileStore.clearAvatar(ctx)
                // ⚠️ renderProfileAsync 是 suspend —— 弹窗回调不是协程上下文，
                //    必须起一个再调。
                viewLifecycleOwner.lifecycleScope.launch { renderProfileAsync() }
                MessageDialog.toast(requireContext(), "已恢复姓氏头像")
            },
        )
    }

    /** 解析主题属性色；失败回落到校徽蓝。 */
    private fun themeColor(ctx: android.content.Context, attr: Int): Int {
        val ta = ctx.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, FALLBACK_AVATAR_COLOR)
        ta.recycle()
        return c
    }

    /**
     * 设置「学期第一周星期一」（ADR-013）。
     *
     * 为什么需要手动设：教务的课表接口只给【相对周次】（如 zcd="1-9周"），
     * 不返回绝对日期；cxRsd 返回的是「今天几号」而非学期起始。
     * 因此由用户确认一次（默认按 2026-08-31 预填），之后不再打扰。
     *
     * ⚠️ 只接受周一：周次计算以周一为基准，选到别的日子会让整学期偏移。
     */
    private fun pickFirstMonday() {
        val ctx = requireContext()
        // ⚠️ metaDao().get 是 suspend，不能在普通函数里直接调 —— 先起协程读初值。
        viewLifecycleOwner.lifecycleScope.launch {
            val current = runCatching {
                db.metaDao().get(AppDatabase.MetaKeys.FIRST_MONDAY)?.let { LocalDate.parse(it) }
            }.getOrNull() ?: LocalDate.of(2026, 8, 31)

            withContext(Dispatchers.Main) {
                val dialog = android.app.DatePickerDialog(
                    ctx,
                    { _, y, m, d ->
                        val picked = LocalDate.of(y, m + 1, d)
                        val monday = WeekCalculator.mondayOf(picked)
                        viewLifecycleOwner.lifecycleScope.launch {
                            db.metaDao().put(
                                MetaEntity(
                                    AppDatabase.MetaKeys.FIRST_MONDAY,
                                    monday.toString(),
                                ),
                            )
                            renderFirstMonday()
                            // ⚠️ ADR-087：Snackbar → 圆角弹窗（用户：「底部这个提示太丑了」）
                            MessageDialog.show(
                                ctx,
                                if (picked == monday) "第一周起始日已更新" else "已归到该周周一",
                                if (picked == monday) {
                                    "学期第一周星期一：$monday\n（周次与卡片日期已重新计算）"
                                } else {
                                    "你选的 $picked 不是周一，已自动归到当周周一：$monday"
                                },
                            )
                        }
                    },
                    current.year, current.monthValue - 1, current.dayOfMonth,
                )
                dialog.setTitle("选择第一周的星期一")
                dialog.show()
            }
        }
    }

    /**
     * 显示当前设置值 —— ⚠️ ADR-045：改为显示在列表行的右侧（不再单独占一行）。
     *
     * 之前是「设置第一周星期一」按钮 + 下方一行说明文字，
     * 现在统一进列表行：标题左、当前值右，更紧凑也更像"可点击"。
     */
    private suspend fun renderFirstMonday() {
        val raw = db.metaDao().get(AppDatabase.MetaKeys.FIRST_MONDAY)
        val value = if (raw.isNullOrBlank()) {
            null   // 未设置 → 右侧不显示值，行标题本身已说明
        } else {
            val week = runCatching {
                WeekCalculator.weekOf(LocalDate.parse(raw))
            }.getOrNull()
            week?.let { "$raw（第 $it 周）" } ?: raw
        }
        withContext(Dispatchers.Main) {
            SettingsRow.bind(
                binding.btnFirstMonday.root,
                R.drawable.ic_calendar,
                if (value == null) "第一周星期一（未设置）" else "第一周星期一",
                value = value,
            )
        }
    }

    /** 修改显示名；留空则清除自定义名，回落真名。 */
    private fun editDisplayName() {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            setText(binding.tvDisplayName.text?.toString().orEmpty())
            hint = "显示名"
            setSingleLine()
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val wrap = android.widget.FrameLayout(ctx).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("修改显示名")
            .setMessage("留空则恢复为教务同步的姓名")
            .setView(wrap)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val v = input.text?.toString().orEmpty().trim()
                viewLifecycleOwner.lifecycleScope.launch {
                    if (v.isEmpty()) {
                        db.metaDao().remove(AppDatabase.MetaKeys.DISPLAY_NAME)
                    } else {
                        db.metaDao().put(
                            MetaEntity(AppDatabase.MetaKeys.DISPLAY_NAME, v),
                        )
                    }
                    renderProfileAsync()
                }
            }
            .show()
    }

    private fun toggleDiagnostics() {
        val tv = binding.tvDiagnostics
        if (tv.visibility == View.GONE) {
            tv.text = buildString {
                appendLine("=== 协议配置 ===")
                appendLine(LoginDiagnostics.currentConfig())
                appendLine()
                appendLine("=== 请求日志 ===")
                append(LoginDiagnostics.snapshot().ifBlank { "(暂无)" })
            }
            tv.visibility = View.VISIBLE
        } else {
            tv.visibility = View.GONE
        }
    }

    suspend fun refresh() {
        val courses = withContext(Dispatchers.IO) { db.courseDao().count() }
        val grades = withContext(Dispatchers.IO) { db.gradeDao().count() }
        val exams = withContext(Dispatchers.IO) { db.examDao().count() }
        val last = withContext(Dispatchers.IO) {
            db.metaDao().get(AppDatabase.MetaKeys.LAST_SYNC_AT)
        }

        if (!isAdded) return

        val total = courses + grades + exams
        binding.tvDataState.text = if (total == 0) {
            "尚未同步任何数据"
        } else {
            "课表 $courses · 成绩 $grades · 考试 $exams"
        }

        binding.tvLastSync.text = if (last.isNullOrBlank()) {
            "上次同步：从未"
        } else {
            val pretty = TimeFormats.isoToDisplay(last) ?: last
            "上次同步：$pretty"
        }

        binding.btnSync.text = if (total == 0) "登录并同步数据" else "重新同步（需再次输入账密）"
    }

    private fun confirmClear() {
        AlertDialog.Builder(requireContext())
            .setTitle("清除本地数据")
            .setMessage("将删除本机保存的课表、成绩、考试数据，不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清除") { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.clearAllTables() }
                    LoginDiagnostics.clear()
                    refresh()
                }
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (::db.isInitialized) {
            viewLifecycleOwner.lifecycleScope.launch { refresh() }
        }
    }

    /**
     * show/hide 切换时刷新（ADR-082）。
     *
     * ⚠️ MainActivity 用 `show()/hide()` 切 tab（ADR-050），
     *    Fragment 不重建也不走 onResume。若不接这个回调，
     *    从别的 tab 切回设置页时不会刷新（如"上次同步时间"会显示旧值）。
     *    与课表页/今天页同一类问题，此处补齐。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && ::db.isInitialized) {
            viewLifecycleOwner.lifecycleScope.launch { refresh() }
        }
    }

    /**
     * Dock 外观调节（ADR-035 重写）。
     *
     * ⚠️ 修两个真实 bug（用户反馈「只会变大不会缩小」）：
     *
     * 【根因 1】Slider 的监听器**只增不减**
     *   `addOnChangeListener` / `addOnSliderTouchListener` 是"追加"语义，
     *   不是"设置"。每次进入设置页（onViewCreated 会重建视图）都会再注册一层，
     *   于是回调被触发 N 次 → 保存 N 次 + 刷新 Dock N 次，
     *   而且**松手后的刷新会与你正在拖的值打架**，表现为"改不回去/只会变大"。
     *   ✅ 现在：每次绑定前先 `clearOnChangeListeners()` + `clearOnSliderTouchListeners()`
     *      （用 `removeCallbacks` 无法清，必须用官方的 clear 方法）。
     *      并且用 `isBinding` 标志位把"程序设置值"与"用户拖动"区分开。
     *
     * 【根因 2】init 与 bind 的顺序造成旧值覆盖新值
     *   原来先 init（写 slider.value）再 renderDockLabels（读 store），
     *   而 bind 里的 save 会改 store → 标签显示与滑块位置不一致。
     *   ✅ 现在：先绑定监听（带 isBinding 保护），再统一 renderFromStore()，
     *      保证「滑块位置 = store 值 = 标签文字」三者永远一致。
     */
    private fun setupDockTuning() {
        val store = DockTuningStore(requireContext())
        val b = binding

        // 展开/收起面板
        b.btnDockTuning.setOnClickListener {
            b.dockTuningPanel.visibility =
                if (b.dockTuningPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        /** 参数表：滑块 ↔ 读写 store 的方式。集中管理避免遗漏。 */
        data class Row(
            val slider: com.google.android.material.slider.Slider,
            val label: TextView,
            val read: () -> Int,
            val write: (Int) -> Unit,
            val format: (Int) -> String,
            val min: () -> Int,
            val max: () -> Int,
        )

        val rows = listOf(
            Row(b.sliderDockHeight, b.tvDockHeightLabel,
                { store.dockBottomOffset }, { store.dockBottomOffset = it },
                { "距屏幕底部 · ${it}dp（位置）" },
                { DockTuningStore.MIN_BOTTOM_OFFSET }, { DockTuningStore.MAX_BOTTOM_OFFSET }),
            Row(b.sliderDockWidth, b.tvDockWidthLabel,
                { store.dockWidth }, { store.dockWidth = it },
                { if (it == 0) "Dock 宽度 · 自适应（左右各 16dp）" else "Dock 宽度 · 左右各留 ${it}dp" },
                { DockTuningStore.MIN_DOCK_WIDTH }, { DockTuningStore.MAX_DOCK_WIDTH }),
            Row(b.sliderDockCorner, b.tvDockCornerLabel,
                { store.dockCorner }, { store.dockCorner = it },
                { "Dock 圆角 · ${it}dp" },
                { DockTuningStore.MIN_CORNER }, { DockTuningStore.MAX_CORNER }),
            Row(b.sliderDockThickness, b.tvDockThicknessLabel,
                { store.dockHeight }, { store.dockHeight = it },
                { "Dock 厚度 · ${it}dp" },
                { DockTuningStore.MIN_HEIGHT }, { DockTuningStore.MAX_HEIGHT }),
            Row(b.sliderSliderHeight, b.tvSliderHeightLabel,
                { store.sliderHeight }, { store.sliderHeight = it },
                { "滑块高度 · ${it}dp" },
                { DockTuningStore.MIN_SLIDER_H }, { DockTuningStore.MAX_SLIDER_H }),
            Row(b.sliderSliderCorner, b.tvSliderCornerLabel,
                { store.sliderCorner }, { store.sliderCorner = it },
                { "滑块圆角 · ${it}dp" },
                { DockTuningStore.MIN_SLIDER_CORNER }, { DockTuningStore.MAX_SLIDER_CORNER }),
            Row(b.sliderSliderWidth, b.tvSliderWidthLabel,
                { store.sliderExtraWidth }, { store.sliderExtraWidth = it },
                { "滑块宽度增量 · ${it}dp（左右各 ${it / 2}）" },
                { DockTuningStore.MIN_SLIDER_W }, { DockTuningStore.MAX_SLIDER_W }),
            Row(b.sliderDockTextSize, b.tvDockTextSizeLabel,
                { store.textSizeTenths }, { store.textSizeTenths = it },
                { "文字大小 · ${it / 10f}sp" },
                { DockTuningStore.MIN_TEXT }, { DockTuningStore.MAX_TEXT }),
            Row(b.sliderAnimDuration, b.tvAnimDurationLabel,
                { store.animDuration }, { store.animDuration = it },
                { "滑动时长 · ${it}ms" },
                { DockTuningStore.MIN_ANIM_MS }, { DockTuningStore.MAX_ANIM_MS }),
            Row(b.sliderDamping, b.tvDampingLabel,
                { store.dampingOvershoot }, { store.dampingOvershoot = it },
                { "阻尼回弹 · ${"%.2f".format(it / 100f)}" },
                { DockTuningStore.MIN_DAMPING }, { DockTuningStore.MAX_DAMPING }),
        )

        /**
         * 重新挂监听（每次渲染后都要重挂，因为 renderFromStore 会 clear）。
         *
         * ⚠️ 用 lateinit 局部函数引用，避免「先调用后定义」的编译问题：
         *    Kotlin 的局部函数必须在使用前声明，而 renderFromStore 需要调用它。
         */
        lateinit var attachListeners: () -> Unit

        /** 由 store 渲染到 UI（滑块位置 + 标签）。设置值期间抑制回调。 */
        fun renderFromStore() {
            rows.forEach { r ->
                val v = r.read()
                r.slider.clearOnChangeListeners()
                // ⚠️ ADR-038：把滑块范围**强制对齐** Store 上下限。
                //    之前 XML 里写的范围与 Store 不一致（圆角 48 vs 32、
                //    滑块高度 64 vs 60），拖到超限位置会被 coerceIn 夹回，
                //    表现为「滑块自己弹回去」——用户报告的「很多 bug」主因。
                r.slider.valueFrom = r.min().toFloat()
                r.slider.valueTo = r.max().toFloat()
                r.slider.value = v.toFloat()
                r.label.text = r.format(v)
            }
            attachListeners()
        }

        attachListeners = {
            rows.forEach { r ->
                r.slider.clearOnSliderTouchListeners()
                r.slider.addOnChangeListener { _, value, fromUser ->
                    // ⚠️ 只响应**用户拖动**，程序设置值不写回 store（防回环）
                    if (!fromUser) return@addOnChangeListener
                    r.write(value.toInt())
                    r.label.text = r.format(value.toInt())
                }
                r.slider.addOnSliderTouchListener(
                    object : com.google.android.material.slider.Slider.OnSliderTouchListener {
                        override fun onStartTrackingTouch(s: com.google.android.material.slider.Slider) = Unit
                        override fun onStopTrackingTouch(s: com.google.android.material.slider.Slider) {
                            // 松手才刷新 Dock（避免拖动中频繁重排）
                            applyToDock(store)
                        }
                    },
                )
            }
        }

        renderFromStore()

        // 恢复默认
        b.btnDockReset.setOnClickListener {
            store.resetAll()
            renderFromStore()
            applyToDock(store)
            MessageDialog.toast(requireContext(), "已恢复默认外观")
        }
    }

    /** 把当前 store 值应用到 Dock（即时生效，不重建界面）。 */
    private fun applyToDock(store: DockTuningStore) {
        (activity as? com.gzuschedule.app.ui.main.MainActivity)?.refreshDockAppearance()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
