package com.gzuschedule.app.ui.widget

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import com.gzuschedule.app.R

/**
 * 设置页统一列表行（ADR-045）。
 *
 * ⚠️ 背景：用户采纳的优化建议指出，设置页的入口
 *   「目前看起来像普通的静态文字」—— 建议左图标 + 右箭头。
 *
 * ⚠️ 为什么用代码设置文案而不是直接写在 XML：
 *   `<include layout="@layout/item_settings_row" />` **不能覆盖子 View 的
 *   android:text / android:src** —— include 只支持覆盖 layout_* 属性。
 *   所以文案与图标必须在代码里填。
 *
 * 用法：
 *   SettingsRow.bind(binding.btnGrades, R.drawable.ic_grades, "成绩查询")
 *   // 需要右侧当前值时：
 *   SettingsRow.bind(binding.btnFirstMonday, R.drawable.ic_calendar,
 *                    "第一周星期一", value = "2026-08-31")
 */
object SettingsRow {

    /**
     * 填充一行。
     *
     * @param row      `item_settings_row` 的根（include 带 id 后即可直接传）
     * @param iconRes  左侧图标
     * @param title    标题文案
     * @param value    右侧当前值；null 或空则隐藏该控件
     * @param showChevron 是否显示右侧箭头（默认显示；纯展示行可关掉）
     */
    fun bind(
        row: View,
        @DrawableRes iconRes: Int,
        title: String,
        value: String? = null,
        showChevron: Boolean = true,
    ) {
        row.findViewById<ImageView>(R.id.rowIcon).setImageResource(iconRes)
        row.findViewById<TextView>(R.id.rowTitle).text = title

        val valueView = row.findViewById<TextView>(R.id.rowValue)
        if (value.isNullOrBlank()) {
            valueView.visibility = View.GONE
        } else {
            valueView.text = value
            valueView.visibility = View.VISIBLE
        }

        row.findViewById<ImageView>(R.id.rowChevron).visibility =
            if (showChevron) View.VISIBLE else View.GONE
    }

    /** 只更新右侧当前值（如设置完第一周星期日后局部刷新）。 */
    fun setValue(row: View, value: String?) {
        val v = row.findViewById<TextView>(R.id.rowValue)
        if (value.isNullOrBlank()) {
            v.visibility = View.GONE
        } else {
            v.text = value
            v.visibility = View.VISIBLE
        }
    }
}
