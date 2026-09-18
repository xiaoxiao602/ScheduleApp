package com.gzuschedule.app.ui.widget

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable

/**
 * 弹窗圆角统一（ADR-043）。
 *
 * ⚠️ 用户要求：「把所有的弹窗都做成和主页大卡片一样的圆角」。
 *
 *   为什么必须碰 Window：
 *     AlertDialog / MaterialAlertDialogBuilder 默认套一层**系统 Window 背景**，
 *     那层是不透明直角矩形。只在布局里设 `android:background` 带圆角**没用** ——
 *     四个角仍会被系统背景盖成方的。
 *     正确做法是把 Window 背景设为透明，让布局自己的圆角露出来。
 *
 *   用法：在 `dialog.show()` **之后**调用 `applyRounded(dialog)`。
 *   （必须在 show 之后 —— 那时 window 才可用。）
 */
object DialogCorner {

    /** 把弹窗的系统背景换成透明，使布局自带的圆角生效。 */
    fun applyRounded(dialog: Dialog?) {
        val w = dialog?.window ?: return
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
}
