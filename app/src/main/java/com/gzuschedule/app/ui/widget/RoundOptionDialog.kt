package com.gzuschedule.app.ui.widget

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gzuschedule.app.R

/**
 * 通用「圆角单选弹窗」（ADR-095）。
 *
 * ⚠️ 为什么需要它（用户要求）：
 *    「触感选择菜单也改成圆角矩形和我们的 app 风格统一」
 *    「你加弹窗一定要注意这点」
 *
 *    Spinner 的下拉列表是**系统绘制**的，圆角改不了；
 *    `setSingleChoiceItems` 的列表项也没有圆角容器。
 *    所以统一用这个组件 —— 自定义布局 + `DialogCorner` 去掉系统背景。
 *
 * ⚠️ 圆角生效的两个必要条件（缺一不可）：
 *    ① 内容布局自带圆角背景（`bg_dialog_card`，28dp，与主页大卡片一致）
 *    ② **`show()` 之后**调用 `DialogCorner.applyRounded(dialog)`
 *       —— AlertDialog 默认套一层不透明直角 Window 背景，
 *          不处理的话四个角会被盖成方的。
 *    本组件已把这两步封装好，调用方不需要操心。
 *
 * 用法：
 * ```
 * RoundOptionDialog.show(
 *     context = requireContext(),
 *     title = "震动风格",
 *     options = Style.ALL.map { it.label },   // 或传 Option(label, desc)
 *     selectedIndex = currentIndex,
 * ) { index -> ... }
 * ```
 */
object RoundOptionDialog {

    /** 一个选项：主标题 + 可选副标题。 */
    data class Option(val label: String, val desc: String? = null)

    /**
     * 弹出圆角单选列表。
     *
     * @param title          标题（null 则不显示标题行）
     * @param options        选项列表
     * @param selectedIndex  当前选中项下标（-1 表示无选中）
     * @param onPick         选中回调（返回下标）
     * @return 已 show() 的 dialog，调用方可在需要时 dismiss
     */
    fun show(
        context: Context,
        title: String?,
        options: List<Option>,
        selectedIndex: Int = -1,
        onPick: (Int) -> Unit,
    ): AlertDialog {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.dialog_option_list, null, false)

        val container = root.findViewById<LinearLayout>(R.id.optionList)
        val titleView = root.findViewById<TextView>(R.id.optionTitle)

        if (title.isNullOrBlank()) {
            titleView.visibility = View.GONE
        } else {
            titleView.visibility = View.VISIBLE
            titleView.text = title
        }

        // 逐项构造（不用 RecyclerView —— 选项最多 6~8 个，直接 addView 更简单）
        options.forEachIndexed { index, opt ->
            val item = LayoutInflater.from(context)
                .inflate(R.layout.item_option_row, container, false)

            item.findViewById<TextView>(R.id.optLabel).text = opt.label

            val descView = item.findViewById<TextView>(R.id.optDesc)
            if (opt.desc.isNullOrBlank()) {
                descView.visibility = View.GONE
            } else {
                descView.visibility = View.VISIBLE
                descView.text = opt.desc
            }

            // 选中项显示对勾
            item.findViewById<ImageView>(R.id.optCheck).visibility =
                if (index == selectedIndex) View.VISIBLE else View.INVISIBLE

            item.isSelected = index == selectedIndex

            container.addView(item)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()

        // 点击 → 回调并关闭（先绑回调再 show，避免时序问题）
        options.forEachIndexed { index, _ ->
            container.getChildAt(index)?.setOnClickListener {
                onPick(index)
                dialog.dismiss()
            }
        }


        // ⚠️ 必须在 show() 之后 —— 那时 window 才存在
        dialog.show()
        DialogCorner.applyRounded(dialog)
        dialog.setOnDismissListener { DialogCorner.resetBackground(dialog) }

        return dialog
    }
}
