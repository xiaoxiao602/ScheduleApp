package com.gzuschedule.app.ui.widget

import android.content.Context
import android.graphics.Outline
import android.net.Uri
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.gzuschedule.app.R
import com.gzuschedule.app.data.local.UserProfileStore
import java.io.File

/**
 * 头像控件（ADR-012 / ADR-015）。
 *
 * 两种形态，二选一：
 *   ① 有自定义头像 -> 只显示图片
 *   ② 无自定义头像 -> 圆形色块 + 姓氏首字
 *
 * ⚠️ 历史问题与最终修法：
 * 姓氏字曾长期不显示。依次排查并修掉：
 *   1. 字号算错（px 当 sp 用）—— 字小到看不见
 *   2. 布局前 width/height 为 0 时没有兜底字号
 *   3. **纯代码 addView 子视图** —— 测量/布局时序难控，问题最隐蔽
 * 最终改为 inflate 静态布局（view_avatar.xml），把测量交给系统。
 * 同时用 post 在真正完成布局后再校正字号，避免依赖调用时机。
 */
class AvatarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    private val image: ImageView
    private val initialText: TextView

    private var fallbackColor: Int = DEFAULT_FALLBACK
    private var pendingFile: File? = null
    private var pendingName: String? = null

    init {
        LayoutInflater.from(context).inflate(R.layout.view_avatar, this, true)
        image = findViewById(R.id.ivAvatar)
        initialText = findViewById(R.id.tvInitial)

        // 自身裁成圆形（自定义图片需裁圆，姓氏色块也靠它成圆）
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = minOf(view.width, view.height) / 2f
                outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
    }

    /** 设置默认头像底色（一般取卡片同色）。 */
    fun setFallbackColor(color: Int) {
        fallbackColor = color
        applyState()
    }

    /**
     * 更新显示内容。
     * @param customAvatar 自定义头像文件；null 表示用姓氏头像
     * @param displayName  显示名，用于取姓氏首字
     */
    fun bind(customAvatar: File?, displayName: String?) {
        pendingFile = customAvatar
        pendingName = displayName
        applyState()
        // ⚠️ 布局完成后再校正一次字号 —— 首次调用时 width/height 可能仍是 0
        post { applyState() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyState()
    }

    private fun applyState() {
        val f = pendingFile
        if (f != null && f.isFile) {
            // ---- ① 自定义头像：只显示图片 ----
            initialText.visibility = View.GONE
            image.visibility = View.VISIBLE
            image.setImageURI(Uri.fromFile(f))
            background = null
        } else {
            // ---- ② 姓氏头像：圆形色块 + 首字 ----
            image.visibility = View.GONE
            image.setImageDrawable(null)

            initialText.text = UserProfileStore.initial(pendingName)
            initialText.visibility = View.VISIBLE
            initialText.textSize = computeTextSizeSp()

            // 底色用自绘 drawable，避免依赖 Material 组件的形状计算
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(fallbackColor)
            }
        }
    }

    /**
     * 字号（sp）。
     * ⚠️ `TextView.textSize` 单位是 sp；布局未知时给 16sp 兜底，避免 0 号字不可见。
     */
    private fun computeTextSizeSp(): Float {
        val density = resources.displayMetrics.scaledDensity.coerceAtLeast(1f)
        val sidePx = minOf(width, height)
        return if (sidePx <= 0) 16f else (sidePx / density) * 0.42f
    }

    private companion object {
        const val DEFAULT_FALLBACK = 0xFF06459B.toInt()
    }
}
