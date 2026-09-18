package com.gzuschedule.app.ui.widget

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import com.gzuschedule.app.databinding.DialogMessageBinding

/**
 * 通用提示弹窗（ADR-087）。
 *
 * ⚠️⚠️ 替代 Snackbar（用户反馈「底部这个提示太丑了 换成之前一样的弹窗」）：
 *
 *   Snackbar 的问题：
 *     · 贴在屏幕**最底部** —— 与底部 Dock 抢位置，视觉上堆在一起
 *     · 横贯全屏的深色长条，与 App 的圆角卡片语言完全不符
 *     · 样式由系统控制，能改的余地很小
 *
 *   ✅ 本弹窗与「课程详情」「资料编辑」同一套视觉：
 *      28dp 圆角卡片 + 按钮在卡片内部 + 居中显示（不被 Dock 遮挡）。
 *
 * 用法：
 * ```
 * MessageDialog.show(context, "已更新", "学期第一周已设为 2026-08-31（周一）")
 * MessageDialog.toast(context, "头像已更新")   // 只有标题的简版
 * ```
 */
object MessageDialog {

    /**
     * 显示提示弹窗。
     *
     * @param title   标题（粗体）
     * @param message 正文；为 null 时正文不显示（只有标题）
     */
    fun show(context: Context, title: String, message: String? = null) {
        val b = DialogMessageBinding.inflate(LayoutInflater.from(context))
        b.tvTitle.text = title
        if (message.isNullOrBlank()) {
            b.tvMessage.visibility = android.view.View.GONE
        } else {
            b.tvMessage.text = message
            b.tvMessage.visibility = android.view.View.VISIBLE
        }

        val dialog = AlertDialog.Builder(context)
            .setView(b.root)
            .create()

        // ⚠️ 必须把窗口背景设为透明，否则系统直角背景会盖住卡片的 28dp 圆角。
        //    与 CourseDetailDialog / ProfileEditDialog 的做法一致（DialogCorner）。
        DialogCorner.applyRounded(dialog)

        b.btnOk.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 简版：只有一行标题（用于"头像已更新"这类无需解释的提示）。 */
    fun toast(context: Context, title: String) = show(context, title, null)
}