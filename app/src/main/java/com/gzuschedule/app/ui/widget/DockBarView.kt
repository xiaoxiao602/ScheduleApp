package com.gzuschedule.app.ui.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.gzuschedule.app.R
import com.gzuschedule.app.data.local.DockTuningStore
import kotlin.math.abs

/**
 * 底部 Dock 栏（ADR-031 重构）。
 *
 * ⚠️ 本次修掉三个问题（用户反馈）：
 *
 * 1. **四周齐平遮障、不是独立悬浮**
 *    之前 DockBarView inflate 了 view_dock_bar.xml（那是个 MaterialCardView），
 *    于是变成「CardView 套 CardView」，外层卡片的背景把内层模糊背板盖住，
 *    且外层卡片贴边 → 看起来像一块齐平的白板挡在内容前面。
 *    ✅ 现在：本类**直接继承 FrameLayout**，自己画背景（圆角矩形 drawable），
 *       不再套 CardView。真正悬浮（靠 activity_main 里的 margin + elevation）。
 *
 * 2. **看不到滑块**
 *    滑块用浅蓝 #C7DBFC，Dock 底用 #F2FFFFFF（近乎纯白）—— 对比仅 0.15，
 *    肉眼几乎分不出。
 *    ✅ 现在：滑块改用**半透明的主题色**（colorPrimary 带 alpha），
 *       并用 clipToOutline 裁圆角；同时提高与底色的明度差。
 *
 * 3. **毛玻璃没生效**
 *    模糊背板被外层 CardView 盖住了。
 *    ✅ 现在：背板就在本 View 的最底层，由 BlurDockHelper 填充。
 *
 * 结构（自下而上）：
 *   ① blurBackdrop  ImageView —— 模糊后的背景截图（API 31+）
 *   ② slider        View      —— 选中的圆角滑块
 *   ③ labelsRow     LinearLayout —— 文字
 */
class DockBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 单个 Dock 项（纯文字）。 */
    data class Item(val id: Int, val label: String)

    private val slider: View
    private val labelsRow: LinearLayout
    private val labels = mutableListOf<TextView>()

    private var items: List<Item> = emptyList()
    private var selectedIndex = 0
    private var onSelect: ((Int) -> Unit)? = null

    private var sliderX = 0f
    private var sliderAnimator: ValueAnimator? = null

    /**
     * 外观参数（ADR-033）。
     *
     * 读取优先级：**XML 属性 > 应用内设置 > 默认值**
     * 这样既能在布局里写死，也能让用户在设置页用滑块实时调。
     */
    private val tuning = DockTuningStore(context)

    private var cfgHeight = DockTuningStore.DEF_HEIGHT
    private var cfgCorner = DockTuningStore.DEF_CORNER
    private var cfgElevation = DockTuningStore.DEF_ELEVATION
    private var cfgSliderHeight = DockTuningStore.DEF_SLIDER_H
    private var cfgSliderCorner = DockTuningStore.DEF_SLIDER_CORNER
    private var cfgSliderExtra = DockTuningStore.DEF_SLIDER_W
    private var cfgTextSize = DockTuningStore.DEF_TEXT / 10f
    /** 动画时长（ms）与过冲量（ADR-039，可调）。 */
    private var cfgAnimMs = DockTuningStore.DEF_ANIM_MS
    private var cfgDamping = DockTuningStore.DEF_DAMPING / 100f

            
    /**
     * ⚠️ ADR-048 性能：背景 drawable 只创建一次，之后只改属性。
     * 旧代码每次 applyTuning 都 `GradientDrawable()` 新建一个 —— 拖动滑块时
     * 每帧都分配对象 + 触发 invalidate，低端机上是可见的卡顿来源。
     */
    private val dockBackground by lazy {
        GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }
    }

    /** 滑块背景同理，复用同一实例，只改圆角。 */
    private val sliderBackground by lazy {
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(ContextCompat.getColor(context, R.color.dock_slider_fill))
            setStroke(dp(1), ContextCompat.getColor(context, R.color.dock_slider_stroke))
        }
    }

    init {
        // ⚠️ ADR-032：不再 inflate 外层 CardView —— 本类自己就是那张"圆角卡片"。
        //    view_dock_bar.xml 是 <merge>，其子 View 全部用固定/wrap 高度；
        //    若用 match_parent 会把 Dock 撑成全屏（用户反馈的「飞天挡住所有内容」）。
        LayoutInflater.from(context).inflate(R.layout.view_dock_bar, this, true)

        // ⚠️ ADR-037：模糊背板已移除（取消模糊，改不透明背景）
        slider = findViewById(R.id.dockSlider)
        labelsRow = findViewById(R.id.dockLabels)

        // ADR-033：先解析 XML 属性，再让应用内设置覆盖（用户手调优先）
        readAttrs(context, attrs)
        readTuningOverrides()

        applyTuning()
        // ⚠️ ADR-048：复用缓存的背景实例（不每次新建）；圆角由 applySliderAppearance 设置
        slider.background = sliderBackground
        slider.visibility = VISIBLE
        initBlur()
    }

    /** 解析 XML 属性（app:dockHeight 等）。 */
    private fun readAttrs(ctx: Context, attrs: AttributeSet?) {
        if (attrs == null) return
        val a = ctx.obtainStyledAttributes(attrs, R.styleable.DockBarView)
        try {
            cfgHeight = a.getDimensionPixelSize(
                R.styleable.DockBarView_dockHeight, dp(cfgHeight),
            ).toDp()
            cfgCorner = a.getDimensionPixelSize(
                R.styleable.DockBarView_dockCorner, dp(cfgCorner),
            ).toDp()
            cfgElevation = a.getDimensionPixelSize(
                R.styleable.DockBarView_dockElevation, dp(cfgElevation),
            ).toDp()
            cfgSliderHeight = a.getDimensionPixelSize(
                R.styleable.DockBarView_dockSliderHeight, dp(cfgSliderHeight),
            ).toDp()
            cfgSliderCorner = a.getDimensionPixelSize(
                R.styleable.DockBarView_dockSliderCorner, dp(cfgSliderCorner),
            ).toDp()
            cfgSliderExtra = a.getDimensionPixelSize(
                R.styleable.DockBarView_dockSliderExtraWidth, dp(cfgSliderExtra),
            ).toDp()

            // ⚠️ ADR-048 修「首次安装字体特别小」：
            //    getDimension 的**默认值参数必须是像素**。旧代码把 sp 数值(16.0)
            //    直接当默认值传进去 → 被视作 16px → 再除以 scaledDensity(≈2.75)
            //    → 得到 5.8sp。装了 App 后在设置页调一次就正常，是因为
            //    readTuningOverrides() 会覆盖它 —— 所以只有「首次安装」可见。
            cfgTextSize = a.getDimension(
                R.styleable.DockBarView_dockTextSize, spToPx(cfgTextSize),
            ) / resources.displayMetrics.scaledDensity
        } finally {
            a.recycle()
        }
    }

    /** 应用内设置覆盖 XML（用户手调优先）。 */
    private fun readTuningOverrides() {
        if (!tuning.isCustomized()) return
        cfgHeight = tuning.dockHeight
        cfgCorner = tuning.dockCorner
        cfgElevation = tuning.dockElevation
        cfgSliderHeight = tuning.sliderHeight
        cfgSliderCorner = tuning.sliderCorner
        cfgSliderExtra = tuning.sliderExtraWidth
        cfgTextSize = tuning.textSizeSp
        cfgAnimMs = tuning.animDuration
        cfgDamping = tuning.dampingFactor
        // ADR-056 液体变形
                            }

    /** 把参数落到视图上。 */
    private fun applyTuning() {
        // 自身高度：Dock 是"一张卡片"，高度只由内容决定
        minimumHeight = dp(cfgHeight)

        // ⚠️ ADR-048 性能修复（用户：「非旗舰机上运行有点卡顿」）：
        //
        //   ① clipToOutline + 自定义 outlineProvider 会让**每一帧**都做
        //      离屏裁剪（GPU 要建 stencil / 走 saveLayer），低端机吃力。
        //      但我们的背景本身就是 GradientDrawable 的**圆角矩形** ——
        //      它自己就带圆角，**不需要**再裁一次。
        //      故关掉 clipToOutline，只在真的需要裁子 View 时才开。
        //
        //   ② outlineProvider 每次 getOutline 都调 dp() 与新建 Rect，
        //      而它在一帧内可能被调用多次 —— 改为缓存圆角像素值。
        clipToOutline = false
        outlineProvider = null

        // 背景：圆角 + 半透明（这已足够表现"圆角卡片"，无需额外裁剪）
        dockBackground.cornerRadius = dp(cfgCorner).toFloat()
        dockBackground.setColor(ContextCompat.getColor(context, R.color.dock_bar_bg))
        if (background !== dockBackground) background = dockBackground

        // 阴影：让它看起来浮在内容之上
        elevation = dp(cfgElevation).toFloat()

        // 文字层高度跟随 Dock 高度
        labelsRow.layoutParams = labelsRow.layoutParams.apply {
            height = dp(cfgHeight)
        }
        labels.forEach { it.textSize = cfgTextSize }

        // 滑块外观（圆角）
        applySliderAppearance()
    }

    /**
     * 应用滑块尺寸与圆角。
     *
     * ⚠️ ADR-038 修：之前用 minOf(cfgCorner, cfgSliderHeight/2)，
     *    导致设置页的「滑块圆角」滑块**拖动无效**（被 Dock 圆角覆盖）。
     *    现在直接使用用户设定的滑块圆角，仅在超过高度一半时收敛（避免退化成胶囊）。
     */
    private fun applySliderAppearance() {
        // ⚠️⚠️ ADR-088：不再把圆角限制在「高度的一半」。
        //
        //   旧实现 `val maxR = cfgSliderHeight / 2` ——
        //   滑块高度 48dp 时 maxR = 24，用户把「滑块圆角」调到 30 也**无效**
        //   （被静默夹回 24）。用户反馈「滑块圆角调不上去」即此。
        //
        //   ✅ 现在直接采用用户设定值。
        //      圆角 > 半高时，ShapeDrawable 会画成**胶囊/椭圆**（左右全圆头），
        //      这是 iOS segmented control 一类现代控件的常见形态，观感正常。
        //      用户既然能调到 30，就让他看到 30 的实际效果，不静默篡改。
        val r = cfgSliderCorner.coerceAtLeast(0)
        val px = dp(r).toFloat()
        // ⚠️ ADR-048：只改属性，不新建对象、不 requestLayout
        //    （圆角变化不影响测量尺寸，故无需重新布局）
        if (sliderBackground.cornerRadius != px) {
            sliderBackground.cornerRadius = px
            slider.invalidate()
        }
    }

    /** px → dp（用于把 getDimensionPixelSize 的结果存回 dp 字段）。 */
    private fun Int.toDp(): Int =
        (this / resources.displayMetrics.density).toInt()

    private fun dpToPx(v: Int): Int = dp(v)

    /** 供设置页调用：重新读取参数并立即生效（无需重建 Activity）。 */
    fun reloadTuning() {
        readTuningOverrides()
        applyTuning()
        requestLayout()
        invalidate()
    }

    // ---------- 毛玻璃 ----------

    /**
     * 毛玻璃 / 滑块质感（ADR-037：**已取消模糊**）。
     *
     * ⚠️ 三次尝试全部撤回（用户最终要求「取消模糊，改成不透明的」）：
     *   ADR-030 PixelCopy 抓屏 → 抓到自身 → 发蓝、且不实时
     *   ADR-034 内容层 RenderEffect → **整个界面被模糊** + 透明区变白
     *   ADR-036 局部 BlurBackdrop → 未验证即被要求取消
     *
     * 结论：**Dock 改为不透明背景**，滑块保持现状（不变）。
     * 不再对任何 View 施加 RenderEffect。
     */
    private fun initBlur() {
        // 故意留空：模糊已取消（见上方说明）。
        // 保留方法以免调用点散落，也便于将来若要做模糊时集中处理。
    }

    /**
     * ⚠️ ADR-037：滚动源接口已废弃（模糊取消后不再需要重抓）。
     * 保留空实现以免调用点编译失败，将来若恢复模糊可在此接回。
     */
    fun attachScrollSource(@Suppress("UNUSED_PARAMETER") scrollView: View?) {
        // 故意留空
    }

    /** 设置项。 */
    fun setItems(items: List<Item>, initial: Int = 0, onSelect: (Int) -> Unit) {
        this.items = items
        this.onSelect = onSelect
        labelsRow.removeAllViews()
        labels.clear()

        items.forEachIndexed { index, item ->
            val tv = TextView(context).apply {
                text = item.label
                textSize = 13.5f
                gravity = Gravity.CENTER
                isClickable = false
                setTextColor(ContextCompat.getColor(context, R.color.dock_text_unselected))
            }
            labels.add(tv)
            // ⚠️ 必须显式用 LinearLayout.LayoutParams —— 本类继承 FrameLayout，
            //    不加限定符时 LayoutParams 会解析成 FrameLayout 的（只有 2 参构造）。
            labelsRow.addView(
                tv,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
            )
        }
        selectedIndex = initial
        updateColors()
        post {
            syncSliderSize()
            sliderX = targetX()
            applySlider()
        }
    }

    /** 选中某项（带动画）。 */
    fun select(index: Int, animate: Boolean = true) {
        if (index !in items.indices) return
        val changed = index != selectedIndex
        selectedIndex = index
        updateColors()
        layoutSlider(animate && changed)
    }

    fun selected(): Int = selectedIndex

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // ⚠️ ADR-040：不再收集每格文字边界 —— 位置全部按「等分槽位」计算，
        //    这样首末格与中间格的活动范围完全一致（对称）。
        syncSliderSize()

        // ⚠️ ADR-037：闪烁 bug 的根因 —— 这里原来无条件写 sliderX = targetX()，
        //    而 syncSliderSize() 会改 layoutParams → 触发 requestLayout → 又进 onLayout，
        //    于是「动画推进一帧 → onLayout 把位置拽回终点」反复拉锯 = 闪烁。
        //    现在：**动画运行中 或 用户正在拖动时**都不同步位置，避免抢 translationX。
        if (sliderAnimator?.isRunning != true && !dragging) {
            sliderX = targetX()
            applySlider()
        }
    }

    /**
     * 目标 X —— 选中格的**槽位中心**再左移半个增量（ADR-040）。
     *
     * ⚠️ 不再用 `tv.left`：各格文字宽度不同（"今天"/"周课表"），
     *    以文字左边缘定位会让首末格的可滑动范围与中间格不一致。
     *    改用「等分槽位中心」，所有格的活动范围完全相同 → 左右对称。
     */
    private fun targetX(): Float {
        val n = labels.size
        if (n == 0) return 0f
        val pad = labelsRow.paddingLeft
        val slotW = slotWidth()
        val idx = selectedIndex.coerceIn(0, n - 1)
        // 槽位中心
        val center = pad + slotW * idx + slotW / 2f
        // 滑块自身宽度 = slotW + extra，居中于槽位 → 左边缘 = center - (slotW+extra)/2
        return center - (slotW + dp(cfgSliderExtra)) / 2f
    }

    /**
     * 同步滑块尺寸（只能在布局阶段写 layoutParams）。
     *
     * ⚠️ ADR-040：宽度**不再**用 `文字宽 + extra`。
     *
     *   旧做法的 bug（用户反馈「左右可滑动距离不一致，应该对称」）：
     *     宽度 = 文字宽 + extra，左边界 = 文字 left - extra/2。
     *     但各格文字宽度不同（"今天"2 字 vs "周课表"3 字），
     *     且首末格紧贴 dockLabels 的 paddingHorizontal，
     *     于是滑块在**每个位置的可活动范围**都不一样 —— 看着就是不对称。
     *
     *   新做法：把可用宽度**等分**成 N 份，滑块宽度 = 每份宽度（+extra，左右各半），
     *     位置 = 该份的中心 ± extra/2。
     *     → 首末格与中间格的活动范围完全一致，必然对称。
     */
    private fun syncSliderSize() {
        val n = labels.size
        if (n == 0 || width == 0) return
        val slotW = slotWidth()
        val lp = slider.layoutParams
        // 宽度 = 一等份 + 增量（增量左右各分一半，故不必额外补偿）
        val targetW = (slotW + dp(cfgSliderExtra)).coerceAtMost(width)
        val targetH = dp(cfgSliderHeight)
        if (lp.width != targetW || lp.height != targetH) {
            lp.width = targetW
            lp.height = targetH
            slider.layoutParams = lp
        }
    }

    /** 每格等分宽度（已扣除 dockLabels 的左右内边距）。 */
    private fun slotWidth(): Int {
        val n = labels.size
        if (n == 0) return 0
        val pad = labelsRow.paddingLeft + labelsRow.paddingRight
        return ((width - pad) / n).coerceAtLeast(1)
    }

    /**
     * 滑块滑动（animate=true 时带**液体变形** + 阻尼动画）。
     *
     * ⚠️ ADR-056「液体变形」实现要点（用户：「小米 苹果 他们的 dock 栏滑块
     *    在拖动的时候会模仿液体的变形效果 比较 q弹」）：
     *
     *  ① **速度驱动拉伸**：滑块的宽度随**瞬时速度**增加，方向沿运动轴。
     *     像一滴水被甩出去 —— 走得快就拉长，停下来就恢复。
     *     实现：把「进度差」换算成速度，映射为水平缩放 scaleX。
     *
     *  ② **弹簧回弹**：用 `SpringInterpolator`（自实现）而非 PathInterpolator，
     *     带来真实的过冲 + 二次回摆（PathInterpolator 只能过冲一次）。
     *
     *  ③ **形变锚点**：缩放的 pivot 设在**运动方向的前缘**，
     *     这样拉伸看起来是"前端先冲出去"，而不是"从中间鼓起来"。
     *     更进一步：拖动中 pivot 跟随手指，释放后回到中心。
     *
     *  ④ **体积守恒感**：横向拉伸时纵向**轻微压缩**（scaleY 略小于 1），
     *     模拟液体被拉长后变细 —— 这是"Q弹"的关键细节，
     *     只拉长不压扁会像"橡皮筋"而不是"液体"。
     *
     * ⚠️ 与硬件的配合：用 `ViewPropertyAnimator` 直接驱动 `scaleX/scaleY`
     *    （属性动画走 RenderThread，比自绘省 CPU）。
     */
    private fun layoutSlider(animate: Boolean) {
        if (selectedIndex !in labels.indices) return
        syncSliderSize()
        val target = targetX()

        if (animate && sliderX != target) {
            sliderAnimator?.cancel()
            val start = sliderX
            val distance = target - start
            val dir = if (distance >= 0) 1f else -1f

            sliderAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = cfgAnimMs.toLong()
                interpolator = SpringInterpolator(damping = cfgDamping)

                addUpdateListener {
                    val p = it.animatedValue as Float
                    sliderX = start + distance * p
                    applySlider()
                }

                // ⚠️⚠️⚠️ ADR-089：这里**什么都不做**才是对的。
                //
                //   闪烁的真正根因（前两次都没修对）：
                //     `SpringInterpolator.normalizedVelocity(t)` 在 t>=1 时**返回 0**
                //     → 动画最后一帧 stretch = 0 → scaleX 本来就已经是 1.0f。
                //     缩放是**自然收敛**的，不需要任何"归位"动作。
                //
                //     而之前我在 onAnimationEnd 里调用 smoothResetScale()，
                //     它启动了 ViewPropertyAnimator 去写 scaleX ——
                //     与 ValueAnimator 的帧回调**抢同一个属性**，
                //     两个动画机制交替写入 → 闪烁/突变。
                //
                //   ✅ 正确做法：让 ValueAnimator 独占 scaleX/scaleY，
                //      结束后不碰它们（已经在 1.0）。
                //      ⚠️ 唯一需要兜底的是 onAnimationCancel —— 被 cancel 时
                //         动画停在中间帧，scale 可能 != 1，此时必须复位。
                //         但复位也不能用第二个动画，直接赋值即可
                //         （cancel 场景本就是"打断"，瞬间归位不会突兀）。

                start()
            }
        } else {
            sliderX = target
            applySlider()
        }
    }


    /**
     * ⚠️ ADR-040：`sliderX` 现在**已经是滑块左边缘**（在 targetX 里算好了
     * 槽位居中的左边缘），所以这里直接赋值，**不再减 extra/2** ——
     * 再减一次会让滑块整体左移、首末格更不对称。
     *
     * 只用 translationX 表达位置，不碰 layoutParams（碰了会触发 requestLayout 抹掉动画）。
     */
    private fun applySlider() {
        slider.translationX = sliderX
    }

    private fun updateColors() {
        labels.forEachIndexed { i, tv ->
            val active = i == selectedIndex
            tv.setTextColor(
                ContextCompat.getColor(
                    context,
                    if (active) R.color.dock_text_selected else R.color.dock_text_unselected,
                ),
            )
            tv.setTypeface(
                tv.typeface,
                if (active) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
        }
    }

    // ---------- 触摸：点击 + 拖动 ----------

    private var downX = 0f
    private var downSliderX = 0f
    private var dragging = false
    /** 按下时**滑块所在**的索引（不是触点所在的索引）。 */
    private var downIndex = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downSliderX = sliderX
                dragging = false
                // ⚠️ ADR-048 修「拖动有时不切页」：
                //    这里必须用**滑块当前所在格**，而不是触点所在格。
                //    因为判断"要不要切换"是和滑块的起点比 ——
                //    用触点位置会在点到格间空隙时算出错误基准（indexAt 会夹到最近格，
                //    但触点未必落在滑块那一格），导致该切的时候不切。
                downIndex = selectedIndex
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!dragging && abs(dx) > dp(6).toFloat()) {
                    dragging = true
                    sliderAnimator?.cancel()
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                if (dragging) {
                    // ⚠️ ADR-040：maxSliderX() 与首格公式对称，故左右可动距离相等。
                    // ⚠️ ADR-073：下限必须用 minSliderX() 而不是硬编码 0f ——
                    //    否则左右可动范围不对称（用户反馈「右边能滑到头，左边不行」）。
                    sliderX = (downSliderX + dx).coerceIn(minSliderX(), maxSliderX())
                    applySlider()
                }
                return true
            }

            // ⚠️ ADR-048：ACTION_UP 与 ACTION_CANCEL 必须分开处理。
            //    CANCEL 表示手势被父容器抢走（如页面开始滚动），
            //    此时**不应该**切换页面 —— 旧代码把两者放同一分支，会误切。
            MotionEvent.ACTION_UP -> {
                // ⚠️ ADR-090：液体形变功能已整体移除（用户：「删除这个效果吧 目前做不好」）。
                //    原来这里要先归位 scale 再启动动画，现在两者都不需要了。
                val wasDragging = dragging
                dragging = false

                if (wasDragging) {
                    val nearest = nearestIndex(sliderX)
                    // 松手后一定吸附到最近的格；只有目标与起点不同才回调
                    select(nearest, animate = true)
                    if (nearest != downIndex) onSelect?.invoke(nearest)
                } else {
                    val idx = indexAt(event.x)
                    if (idx >= 0) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        select(idx, animate = true)
                        onSelect?.invoke(idx)
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // 手势取消：把滑块弹回它原本所在的格，**不切换页面**
                dragging = false
                sliderAnimator?.cancel()
                sliderX = targetX()
                applySlider()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * 触摸点落在哪一格（ADR-040：按**等分槽位**判断，不按文字边界）。
     *
     * ⚠️ 旧实现用 `tv.left..tv.left+width` —— 文字之间有间隙，
     *    落在间隙里会返回 -1（点了没反应）；首末格还受 padding 影响。
     */
    private fun indexAt(x: Float): Int {
        val n = labels.size
        if (n == 0 || slotWidth() == 0) return -1
        val slotW = slotWidth()
        val pad = labelsRow.paddingLeft
        val i = ((x - pad) / slotW).toInt()
        return i.coerceIn(0, n - 1)
    }

    /**
     * 离触摸位置最近的格子（ADR-040：按**槽位中心**距离判断）。
     *
     * ⚠️ 旧实现比较 `left - x`（文字左边缘），
     *    而且传入的是 `sliderX`（滑块左边缘）——两者基准不同，
     *    导致首末格判定的吸附点偏移、滑动距离不对称。
     */
    private fun nearestIndex(x: Float): Int {
        val n = labels.size
        if (n == 0) return 0
        val slotW = slotWidth()
        val pad = labelsRow.paddingLeft
        // 滑块中心（x 是滑块左边缘）
        val center = x + (slotW + dp(cfgSliderExtra)) / 2f
        val i = ((center - pad) / slotW).toInt()
        return i.coerceIn(0, n - 1)
    }

    /**
     * 滑块可移动的**左**边界（左边缘的最小值）—— ADR-073。
     *
     * ⚠️⚠️ 用户反馈「滑块右边可以滑到头，左边不行」。
     *
     *   根因：拖动时的钳制写的是
     *     ```
     *     sliderX = (downSliderX + dx).coerceIn(0f, maxSliderX())
     *                                            ↑ 硬编码 0
     *     ```
     *   左边界用了**硬编码 0**，而右边界用的是精确算出的 [maxSliderX]。
     *   两者基准不同 → 滑动范围必然不对称。
     *
     *   首格的正确左边界 = targetX(idx=0) = padL + slotW/2 - (slotW+extra)/2
     *                    = padL - extra/2
     *   它**不等于 0**：
     *     · 若 padL > extra/2 → 首格左边界 > 0 → 左边还能再往左滑（滑过头）
     *     · 若 padL < extra/2 → 首格左边界 < 0 → 滑块被卡住，滑不到首格应有的位置
     *                                     （用户看到的「左边滑不到头」）
     *
     *   ✅ 修复：用与 [maxSliderX] **完全对称**的公式算左边界，
     *      这样无论 padding 和 extra 怎么调，左右可动范围恒等。
     */
    private fun minSliderX(): Float {
        val n = labels.size
        if (n == 0) return 0f
        val slotW = slotWidth()
        val pad = labelsRow.paddingLeft
        val center = pad + slotW * 0 + slotW / 2f
        return center - (slotW + dp(cfgSliderExtra)) / 2f
    }

    /**
     * 滑块可移动的右边界（左边缘的最大值）—— ADR-040。
     *
     * ⚠️ 旧实现用 `slotBounds.last().first`（末格文字左边缘），
     *    但滑块宽度是 slotW+extra，直接用它会让末格越界。
     *    现在用 targetX 的公式算末格左边缘，保证与首格对称。
     */
    private fun maxSliderX(): Float {
        val n = labels.size
        if (n == 0) return 0f
        val slotW = slotWidth()
        val pad = labelsRow.paddingLeft
        val center = pad + slotW * (n - 1) + slotW / 2f
        return center - (slotW + dp(cfgSliderExtra)) / 2f
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** sp → px（用于把 sp 默认值喂给需要 px 的 API）。 */
    private fun spToPx(sp: Float): Float = sp * resources.displayMetrics.scaledDensity

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sliderAnimator?.cancel()
    }
}
