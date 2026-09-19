package com.gzuschedule.app.ui.settings

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.gzuschedule.app.data.auth.LoginDiagnostics
import com.gzuschedule.app.R
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.data.local.DockTuningStore
import com.gzuschedule.app.data.local.HapticStore
import com.gzuschedule.app.data.local.Style
import com.gzuschedule.app.ui.widget.Haptics
import com.gzuschedule.app.data.local.UserProfileStore
import com.gzuschedule.app.ui.widget.ProfileEditDialog
import com.gzuschedule.app.data.local.CardStyleStore
import com.gzuschedule.app.ui.color.CourseColorActivity
import com.gzuschedule.app.ui.haptic.HapticActivity
import com.gzuschedule.app.ui.dock.DockTuningActivity
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
import com.gzuschedule.app.data.local.UpdatePrefs
import com.gzuschedule.app.data.update.UpdateChecker
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
        // ⚠️ ADR-107：检查更新
        setupUpdateCheck()

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

        // ---- Dock 外观调节（ADR-094：改为二级页）----
        SettingsRow.bind(
            binding.btnDockTuning.root,
            R.drawable.ic_dock_layout,
            "Dock 外观调节",
        )
        binding.btnDockTuning.root.setOnClickListener {
            startActivity(Intent(requireContext(), DockTuningActivity::class.java))
        }

        // ---- 触感反馈（ADR-094：改为二级页）----
        SettingsRow.bind(
            binding.btnHapticTuning.root,
            R.drawable.ic_vibration,
            "触感反馈",
        )
        binding.btnHapticTuning.root.setOnClickListener {
            startActivity(Intent(requireContext(), HapticActivity::class.java))
        }

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
     * 系统照片选择器（ADR-012 / ADR-104）。
     *
     * ⚠️ 用 PickVisualMedia 而非旧的权限方案：
     * 系统进程展示图片并只授予选中那张的临时读取权，
     * **App 无需 READ_MEDIA_IMAGES 等任何存储权限**。
     *
     * ⚠️ ADR-104：**不做裁剪**（用户决定砍掉）。
     *     裁剪尝试过三种方案全部失败，成本远超收益：
     *       · uCrop 2.2.10        —— 一选图就崩（要求 file:// 输出 Uri）
     *       · 系统裁剪 Intent      —— 小米 ROM 阉割，提示「没有裁剪功能」
     *       · 自研裁剪页           —— Uri 授权跨 Activity 传递在部分 ROM 上失败
     *     现在直接保存原图，由 UserProfileStore 做**中心方形裁切**（无声、无 UI）。
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
    private fun setupUpdateCheck() {
        // ⚠️ ADR-108：用 ic_update（tint=colorControlNormal，灰色），
        //    而不是 ic_sync（tint=colorOnPrimary，白色 —— 给蓝按钮用的）。
        //    用户反馈：「这个更新的图片要和上面图标一样的颜色
        //              图标位置和文字也和上面一样对齐」
        //    SettingsRow.bind 保证图标/标题/箭头的位置与其他行完全一致。
        SettingsRow.bind(binding.btnCheckUpdate.root, R.drawable.ic_update, "检查更新")

        binding.btnCheckUpdate.root.setOnClickListener {
            checkUpdate(manual = true)
        }

        // 长按切换「自动检查」
        binding.btnCheckUpdate.root.setOnLongClickListener {
            val cur = UpdatePrefs.isAutoCheck(requireContext())
            UpdatePrefs.setAutoCheck(requireContext(), !cur)
            MessageDialog.show(
                requireContext(),
                if (!cur) "已开启自动检查" else "已关闭自动检查",
                if (!cur) "每次打开 App 时会自动检查是否有新版本。" else "不再自动检查更新。",
            )
            true
        }
    }

    /**
     * 把 Release 说明里的 Markdown 标记去掉（ADR-112）。
     *
     * ⚠️ 弹窗是纯 TextView，不渲染 Markdown ——
     *    直接显示 `### 修复` / `- xxx` 会很难看。
     *    这里只做最基本的清理（标题符号、列表符号、粗体星号）。
     */
    private fun stripMarkdown(src: String): String =
        src.split("\n")
            .map { line ->
                line.trim()
                    .removePrefix("#### ").removePrefix("### ")
                    .removePrefix("## ").removePrefix("# ")
                    .removePrefix("- ").removePrefix("* ")
                    .replace("**", "")
                    .replace("`", "")
            }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    /**
     * 用系统浏览器打开链接（ADR-112）。
     *
     * ⚠️ 用户需求：「点击确定跳转系统浏览器下载」
     *
     * ⚠️ 用 ACTION_VIEW 让用户自选浏览器（不硬编码某个浏览器）。
     *    若设备没有任何浏览器，退化为复制链接到剪贴板并提示 ——
     *    总比点了没反应好。
     */
    private fun openInBrowser(url: String?) {
        if (url.isNullOrBlank()) {
            MessageDialog.show(requireContext(), "无法打开", "没有可用的下载地址。")
            return
        }
        val ctx = requireContext()
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse(url),
        )

        // ⚠️ 先判断有没有 App 能处理这个 Intent。
        //    直接 startActivity 在无浏览器设备上会抛 ActivityNotFoundException
        //    （用 runCatching 兜底也行，但先判断更干净、无异常开销）。
        val handler = intent.resolveActivity(ctx.packageManager)
        if (handler == null) {
            // 没有浏览器 → 复制链接，用户可手动粘到浏览器
            val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("下载地址", url))
            MessageDialog.show(
                ctx,
                "没有找到浏览器",
                "下载地址已复制到剪贴板，可手动粘贴到浏览器打开：\n$url",
            )
            return
        }
        runCatching { ctx.startActivity(intent) }.onFailure {
            MessageDialog.show(ctx, "打开失败", "无法启动浏览器。\n\n下载地址：\n$url")
        }
    }

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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null

}

    /** 执行检查。[manual] = 用户主动点击（会显示「已是最新」）。 */
    private fun checkUpdate(manual: Boolean) {
        val vName = runCatching {
            requireContext().packageManager
                .getPackageInfo(requireContext().packageName, 0).versionName
        }.getOrNull().orEmpty()

        // 手动点击时给个即时反馈
        if (manual) {
            MessageDialog.show(requireContext(), "正在检查更新…", "正在访问 GitHub 仓库…")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val r = UpdateChecker.check(vName)

            when {
                r.error != null -> {
                    if (manual) {
                        MessageDialog.show(
                            requireContext(), "检查失败",
                            "${r.error}\n\n请检查网络后重试。",
                        )
                    }
                }

                r.hasUpdate -> {
                    // ⚠️ ADR-112：弹窗里给出下载地址，点「去下载」跳系统浏览器。
                    //
                    //    用户需求：「检查更新那里弹窗里面给安装包下载地址
                    //              点击确定跳转系统浏览器下载」
                    //
                    //    ⚠️ 下载地址优先用 APK 直链（点了直接下载）；
                    //       若该 Release 没附 APK，退回 Release 页面。
                    val url = r.apkUrl ?: r.releaseUrl

                    MessageDialog.showWithSecondary(
                        context = requireContext(),
                        title = "发现新版本 ${r.latestVersion}",
                        message = buildString {
                            append("当前版本：$vName")
                            append("\n最新版本：${r.latestVersion}")

                            // ⚠️ Release body 是 Markdown，弹窗里不渲染 ——
                            //    去掉 ## / - 等符号，避免显示成乱码样的原文。
                            r.notes?.let { stripMarkdown(it) }
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    append("\n\n更新内容：\n")
                                    append(it.take(160))
                                }

                            append("\n\n下载地址：\n$url")
                        },
                        okText = "去下载",
                        secondaryText = "稍后",
                        onOk = { openInBrowser(url) },
                    )
                }

                else -> {
                    if (manual) {
                        MessageDialog.show(
                            requireContext(), "已是最新版本",
                            "当前版本 $vName 已是最新。",
                        )
                    }
                }
            }
        }
    }
}
