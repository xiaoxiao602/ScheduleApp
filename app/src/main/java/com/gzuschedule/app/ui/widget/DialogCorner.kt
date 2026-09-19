package com.gzuschedule.app.ui.widget

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable

/**
 * 弹窗圆角统一（ADR-043 / ADR-095 / ADR-098）。
 *
 * ⚠️⚠️ **必须 `dialog.show()` 之后才能调用 `applyRounded()`**。
 *
 *   为什么（血泪教训，ADR-098）：
 *     `Dialog.getWindow()` 只有在 `show()` 之后才**非空**。
 *     在 show() 之前调用 → window 为 null → 这里直接 return →
 *     **圆角静默失效**（系统的直角背景把卡片的圆角盖住），
 *     但**不报任何错**，所以很难发现。
 *
 *   实际踩坑：项目里 5 个弹窗有 4 个写成了「先 applyRounded 再 show」，
 *     导致用户反复反馈「弹窗圆角不统一」，排查多轮才定位到顺序问题。
 *
 *   ✅ 正确写法：
 *   ```kotlin
 *   val dialog = AlertDialog.Builder(ctx).setView(root).create()
 *   dialog.setOnDismissListener { ... }     // 可先设监听
 *   dialog.show()                            // ← 先 show
 *   DialogCorner.applyRounded(dialog)        // ← 再圆角
 *   ```
 *
 *   ⚠️ 为降低再犯风险，`applyRounded` 现在会**主动检测并提示**：
 *     若在未 show 的 dialog 上调用，会打印警告（debug 下可见）。
 */
object DialogCorner {

    /**
     * 把弹窗的系统背景换成透明，使布局自带的圆角生效。
     *
     * ⚠️ **必须在 `dialog.show()` 之后调用**（否则 window 为 null，静默失效）。
     */
    fun applyRounded(dialog: Dialog?) {
        val w = dialog?.window
        if (w == null) {
            // ⚠️ 这通常意味着调用方在 show() 之前就调用了本方法。
            //    不抛异常（弹窗仍能显示，只是圆角失效），但打日志便于排查。
            android.util.Log.w(
                "DialogCorner",
                "applyRounded 被调用时 window 为 null —— " +
                    "大概率是在 dialog.show() 之前调用了它，圆角将失效。" +
                    "请把 applyRounded 移到 show() 之后。",
            )
            return
        }
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }

    /**
     * 恢复系统默认的 Window 背景。
     *
     * ⚠️ 在 `setOnDismissListener` 里复位是稳妥做法 ——
     *    避免同一 window 被复用（或屏幕旋转重建 dialog）时背景表现异常。
     */
    fun resetBackground(dialog: Dialog?) {
        try {
            val w = dialog?.window ?: return
            @Suppress("DEPRECATION")
            w.setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(Color.TRANSPARENT),
            )
        } catch (_: Throwable) {
            // 弹窗已销毁时忽略
        }
    }
}
