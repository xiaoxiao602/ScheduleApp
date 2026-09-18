package com.gzuschedule.app.ui.widget

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import com.gzuschedule.app.R
import com.gzuschedule.app.databinding.DialogProfileEditBinding
import java.io.File

/**
 * 个人资料编辑弹窗（ADR-046）。
 *
 * ⚠️ 用户反馈：「修改用户名 头像等功能没有图标，还是三行 浪费空间」。
 *
 * 优化建议第一条即指出这三项占掉宝贵的首屏空间，应「点击顶部的头像卡片
 * 直接进入个人资料编辑页」。本弹窗就是那个「编辑页」。
 *
 * 结构与 [CourseDetailDialog] 一致：
 *   * create() 后 show()，再 [DialogCorner.applyRounded] 让 Window 背景透明，
 *     这样布局自带的 28dp 圆角才会显露（否则四角被系统直角背景盖住）。
 *   * 三个操作复用 [SettingsRow] 的统一样式（图标 + 文案 + chevron）。
 *
 * @param onEditName   点「修改显示名」
 * @param onPickAvatar 点「更换头像」
 * @param onResetAvatar 点「恢复默认头像」
 */
object ProfileEditDialog {

    fun show(
        context: Context,
        displayName: String,
        meta: String,
        avatarFile: File?,
        onEditName: () -> Unit,
        onPickAvatar: () -> Unit,
        onResetAvatar: () -> Unit,
    ) {
        val b = DialogProfileEditBinding.inflate(LayoutInflater.from(context))

        // 头像预览（与设置页头像卡同一套渲染逻辑）
        b.dlgAvatar.bind(avatarFile, displayName)
        b.dlgName.text = displayName
        b.dlgMeta.text = meta

        // 三个操作行：图标 + 文案 + chevron
        SettingsRow.bind(b.dlgEditName.root, R.drawable.ic_edit, "修改显示名")
        SettingsRow.bind(b.dlgPickAvatar.root, R.drawable.ic_camera, "更换头像")
        SettingsRow.bind(b.dlgResetAvatar.root, R.drawable.ic_restore, "恢复默认头像")

        val dialog = AlertDialog.Builder(context)
            .setView(b.root)
            .create()

        // ⚠️ 三个操作都要先关弹窗再执行 —— 否则改名输入框/选图界面会叠在弹窗上。
        b.dlgEditName.root.setOnClickListener {
            dialog.dismiss()
            onEditName()
        }
        b.dlgPickAvatar.root.setOnClickListener {
            dialog.dismiss()
            onPickAvatar()
        }
        b.dlgResetAvatar.root.setOnClickListener {
            dialog.dismiss()
            onResetAvatar()
        }

        // ⚠️ ADR-047：关闭按钮在卡片内部（不用 setPositiveButton）
        b.btnProfileClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        DialogCorner.applyRounded(dialog)
    }
}
