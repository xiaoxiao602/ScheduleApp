package com.gzuschedule.app.ui.widget

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.gzuschedule.app.R
import com.gzuschedule.app.data.local.DayOverride
import java.time.LocalDate

/**
 * 「当日调课」弹窗（ADR-105）。
 *
 * ⚠️ 用户需求：
 *    「当日的调课 —— 原本的课表不动，只是有一天突然调了。
 *      比如星期一的课，那我就选择周一，那天的课表就会临时变成周一的课。
 *      适用于周六日调课」
 *    「如果周四五突然没课，我也可以在今日课表里选择无，那一天就没课了」
 *
 * 三选一：
 *   · 不调课      → 删除记录，恢复原样
 *   · 无课        → 那天清空
 *   · 周一 ~ 周日  → 用那天的课表替换
 *
 * ⚠️ 圆角必须 show() 之后设（ADR-098 的教训：之前 4 个弹窗都写反了）。
 */
object DayOverrideDialog {

    /**
     * 弹出调课选择。
     *
     * @param date     要调课的日期
     * @param current  当前生效的调课设置
     * @param onPick   选择回调（返回新的设置）
     */
    fun show(
        context: Context,
        date: LocalDate,
        current: DayOverride,
        onPick: (DayOverride) -> Unit,
    ) {
        val root = LayoutInflater.from(context)
            .inflate(R.layout.dialog_day_override, null, false)

        val tvTitle = root.findViewById<TextView>(R.id.tvOverrideTitle)
        val tvDesc = root.findViewById<TextView>(R.id.tvOverrideDesc)
        val container = root.findViewById<LinearLayout>(R.id.overrideOptions)

        tvTitle.text = "当日调课"
        tvDesc.text = "把 ${date.monthValue}月${date.dayOfMonth}日（${
            DayOverride.dayName(date.dayOfWeek.value)
        }）临时换成哪天的课表？\n原课表不受影响。"

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()

        // ---- 选项定义 ----
        data class Item(val label: String, val desc: String?, val value: DayOverride)

        val items = buildList {
            add(
                Item(
                    "不调课",
                    "按原本的课表显示",
                    DayOverride.None,
                ),
            )
            add(
                Item(
                    "无课",
                    "那天临时没有课（如突然放假）",
                    DayOverride.NoClass,
                ),
            )
            // 周一~周日
            for (iso in 1..7) {
                add(
                    Item(
                        "用${DayOverride.dayName(iso)}的课表",
                        null,
                        DayOverride.UseDay(iso),
                    ),
                )
            }
        }

        // ---- 渲染选项 ----
        items.forEach { item ->
            val row = LayoutInflater.from(context)
                .inflate(R.layout.item_option_row, container, false)

            row.findViewById<TextView>(R.id.optLabel).text = item.label

            val desc = row.findViewById<TextView>(R.id.optDesc)
            if (item.desc.isNullOrBlank()) desc.visibility = View.GONE
            else {
                desc.visibility = View.VISIBLE
                desc.text = item.desc
            }

            val selected = item.value == current
            row.findViewById<ImageView>(R.id.optCheck).visibility =
                if (selected) View.VISIBLE else View.INVISIBLE

            row.setOnClickListener {
                onPick(item.value)
                dialog.dismiss()
            }

            container.addView(row)
        }

        dialog.show()
        // ⚠️ 必须在 show() 之后 —— window 才存在（ADR-098）
        DialogCorner.applyRounded(dialog)
    }
}
