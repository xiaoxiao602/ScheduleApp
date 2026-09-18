package com.gzuschedule.app.ui.main

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import com.gzuschedule.app.R
import com.gzuschedule.app.data.local.DockTuningStore
import com.gzuschedule.app.databinding.ActivityMainBinding
import com.gzuschedule.app.ui.schedule.ScheduleFragment
import com.gzuschedule.app.ui.settings.SettingsFragment
import com.gzuschedule.app.ui.today.TodayFragment
import com.gzuschedule.app.ui.widget.DockBarView

/**
 * 主界面 —— 启动入口（ADR-005/006/019）。
 *
 * 三档导航：今天（首屏）/ 周课表 / 设置。打开先看本地数据，不弹登录。
 *
 * ⚠️ ADR-019：底栏由 BottomNavigationView 换成自绘 DockBarView，
 * 以获得 Expressive 的「药丸指示器 + 图标上移弹簧动效」。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    /** 缓存的系统导航栏高度（px）—— 由 applyWindowInsets 写入，位置计算时读取。 */
    private var cachedNavigationBottom = 0

    /** 当前显示的 Fragment tag（ADR-050：用于 show/hide 切换）。 */
    private var currentTag: String? = null

    /** Dock 项定义：纯文字（ADR-023，已去掉图标）。 */
    private val dockItems by lazy {
        listOf(
            DockBarView.Item(R.id.nav_today, "今天"),
            DockBarView.Item(R.id.nav_week, "周课表"),
            DockBarView.Item(R.id.nav_settings, "设置"),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Android 15+ 强制 edge-to-edge：自行处理系统栏内边距
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()
        applySystemBarAppearance()
        setupDock()
        // ADR-034：启动时即施加内容层实时模糊（Dock 透出它）
        refreshDockAppearance()

        if (savedInstanceState == null) {
            showFragment(0)
        }
    }

    private fun setupDock() {
        binding.dockBar.setItems(dockItems, initial = 0) { index ->
            showFragment(index)
        }
    }

    /**
     * 按 dock 下标切换 Fragment（ADR-050 性能修复）。
     *
     * ⚠️ 用户反馈：「点击 dock 栏切换页面会卡顿 尤其是中端机 很卡」。
     *
     * 旧实现每次 `TodayFragment()` **新建实例** + `replace()`：
     *   1. Fragment 被销毁重建 → 整个布局重新 inflate（View 树从头构造）
     *   2. onCreateView/onViewCreated 全部重跑 → 重新查 Room、重建 RecyclerView
     *   3. 交互动画期间做这些重活 → 中端机立刻掉帧
     *
     * 新实现：**实例缓存 + show/hide**：
     *   * Fragment 只创建一次，之后仅切换可见性（View 树保留）
     *   * 首次进入才走完整生命周期，之后切回是"秒开"
     *   * 不加自定义淡入淡出动画 —— 系统默认过渡已足够，
     *     且省掉一层 View 动画开销（Dock 自身有滑块动画，视觉已够）
     *
     * ⚠️ 用 `commit()` 而非 `commitAllowingStateLoss()`：
     *    切换是用户主动操作，丢状态会造成"点了没反应"，不值得省。
     */
    private fun showFragment(index: Int) {
        val tag = when (dockItems.getOrNull(index)?.id) {
            R.id.nav_today -> TAG_TODAY
            R.id.nav_week -> TAG_WEEK
            R.id.nav_settings -> TAG_SETTINGS
            else -> return
        }
        if (tag == currentTag) return

        val fm = supportFragmentManager
        val tx = fm.beginTransaction()

        // 先隐藏当前
        currentTag?.let { fm.findFragmentByTag(it)?.let { tx.hide(it) } }

        // 目标已存在 → 直接 show；否则创建并 add（不是 replace，避免销毁其他 Fragment）
        val existing = fm.findFragmentByTag(tag)
        if (existing == null) {
            tx.add(R.id.fragmentContainer, newFragmentFor(tag), tag)
        } else {
            tx.show(existing)
        }

        tx.commit()
        currentTag = tag
    }

    private fun newFragmentFor(tag: String): Fragment = when (tag) {
        TAG_TODAY -> TodayFragment()
        TAG_WEEK -> ScheduleFragment()
        else -> SettingsFragment()
    }

    /**
     * ⚠️ 已移除（ADR-027）：曾用 `window.setBackgroundBlurRadius(dp(24))` 想给
     * Dock 做毛玻璃，但这是**整个 Window 的模糊**，不是局部 —— 结果是
     * **Dock 以外的区域也被模糊成白色**，看起来像"底部抬起一大截白色遮障"
     * （用户反馈的 bug）。
     *
     * 正确的「只有 Dock 模糊」需要 RenderEffect / 局部模糊容器，
     * 成本与兼容性都不划算。Dock 已用半透明背景（dock_bar_bg），
     * 已足够接近毛玻璃观感，故**彻底移除该调用**。
     */
    @Suppress("unused")
    private fun enableBackgroundBlurRemoved() {
        // 故意留空：见上方说明。
        // 保留函数名占位以避免误以为"漏了实现"。
    }

    /**
     * 状态栏/导航栏图标配色（ADR-029）。
     *
     * ⚠️ 为什么还要在代码里设一遍：
     * 主题里的 `windowLightStatusBar = ?attr/isLightTheme` 在部分 ROM
     * 或动态换肤时解析不准 → 深色模式下状态栏图标仍是黑色 → 看不见。
     * 这里用 WindowInsetsControllerCompat **显式**按当前主题设置，
     * 作为可靠兜底（不依赖 isLightTheme 的解析）。
     *
     * isAppearanceLightStatusBars = true 表示"图标用深色"（浅色背景时）。
     */
    private fun applySystemBarAppearance() {
        val isLight = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 系统栏内边距（ADR-032 / ADR-041）。
     *
     * * 状态栏：内容层加 top padding（避免标题被状态栏遮住）
     * * 导航栏：**只缓存高度**，实际的 bottomMargin 由 [refreshDockAppearance] 统一写，
     *   避免两处都写导致互相覆盖（旧版就是两个地方各写一次，谁后写谁赢）。
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.fragmentContainer.setPadding(0, bars.top, 0, 0)
            if (cachedNavigationBottom != bars.bottom) {
                cachedNavigationBottom = bars.bottom
                // 导航栏高度变了 → 重算 Dock 位置（用户设定值 + 系统栏）
                refreshDockAppearance()
            }
            insets
        }
    }

    /** 供子 Fragment 调用：切到「设置」页。 */
    fun navigateToSettings() {
        binding.dockBar.select(index = 2, animate = true)
        showFragment(2)
    }

    /**
     * 应用 Dock 外观设置（ADR-041）。
     *
     * ⚠️ 语义修正（用户：「dock高度调节指的是 dock 在屏幕上的位置，不是 y 方向的宽度」）：
     *   * **距底部距离** ← dockBottomOffset（位置，来自设置页「Dock 高度」滑块）
     *   * **左右宽度**   ← dockWidth（新增，独立滑块）
     *   * Dock 卡片厚度仍由 DockBarView 自己管（δ 见 minimumHeight）
     */
    fun refreshDockAppearance() {
        val tuning = DockTuningStore(this)
        val dm = resources.displayMetrics

        // 位置 + 宽度：都改 layoutParams（属宿主职责）
        val lp = binding.dockBar.layoutParams as FrameLayout.LayoutParams
        val bottom = (tuning.dockBottomOffset * dm.density).toInt()
        // dockWidth 存的是「距屏幕左右边缘的距离」；0 表示用旧的 16dp 边距
        val side = if (tuning.dockWidth == 0) {
            (DEFAULT_SIDE_DP * dm.density).toInt()
        } else {
            (tuning.dockWidth * dm.density).toInt()
        }
        if (lp.leftMargin != side || lp.rightMargin != side) {
            lp.leftMargin = side
            lp.rightMargin = side
        }
        // ⚠️ bottomMargin 要叠加系统导航栏高度（见 applyWindowInsets），
        //    这里只写用户设定值 + 缓存的导航栏高度，避免两者互相覆盖。
        val bars = cachedNavigationBottom
        if (lp.bottomMargin != bottom + bars) {
            lp.bottomMargin = bottom + bars
        }
        binding.dockBar.layoutParams = lp

        // ⚠️ 不再对内容层做 RenderEffect（那会把整个界面糊掉）。
        //    模糊已整体取消（ADR-037），Dock 改为不透明背景。
        binding.fragmentContainer.setRenderEffect(null)

        // 尺寸/圆角/文字/滑块由 DockBarView 自己重读并重绘
        binding.dockBar.reloadTuning()
    }

    private companion object {
        /** dockWidth == 0 时使用的默认左右边距（dp）。 */
        const val DEFAULT_SIDE_DP = 16

        /** Fragment tag（ADR-050：show/hide 切换用）。 */
        const val TAG_TODAY = "tab_today"
        const val TAG_WEEK = "tab_week"
        const val TAG_SETTINGS = "tab_settings"
    }
    /**
     * ⚠️ ADR-037：滚动源接口已废弃（模糊取消）。
     * 保留以便将来恢复模糊时接回。
     */
    fun attachDockScrollSource(@Suppress("UNUSED_PARAMETER") scrollView: View?) {
        // 故意留空
    }
}
