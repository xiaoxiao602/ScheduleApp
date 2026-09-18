package com.gzuschedule.app.ui.widget

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import com.gzuschedule.app.databinding.DialogCourseDetailBinding
import com.gzuschedule.app.domain.PeriodTime
import com.gzuschedule.app.domain.model.Course

/**
 * 课程详情弹窗（ADR-017 / ADR-043 / ADR-047）。
 *
 * 抽成公共函数，供「周课表」与「今天」两处复用。
 *
 * ⚠️ ADR-047（用户：「把弹出窗口的知道了按钮放到卡片内部 优化一下排版」）：
 *
 *  ① 去掉 `setPositiveButton("知道了")`。
 *     系统会把该按钮画在**卡片之外**的 ButtonBar 里，导致：
 *       * 圆角卡片与按钮分属两个视觉容器，像拼在一起
 *       * 卡片底部还留一块为 ButtonBar 预留的空白
 *     现在按钮是本布局（dialog_course_detail.xml）内的 `btnDetailClose`，
 *     关窗动作由代码手动绑定 —— 视觉上完全属于卡片。
 *
 *  ② 排版从「emoji + 整段文字」改为「左标签 + 右正文」两列，
 *     标签对齐后一眼能分清教室/老师/周次。
 *
 * 圆角：仍靠 [DialogCorner] 把系统 Window 背景设为透明，
 *      否则本布局的 28dp 圆角会被系统直角背景盖住。
 */
object CourseDetailDialog {

    private val DAY_NAMES = listOf("", "周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun show(context: Context, course: Course) {
        val b = DialogCourseDetailBinding.inflate(LayoutInflater.from(context))

        val dayName = DAY_NAMES.getOrElse(course.dayOfWeek) { "周?" }
        val range = PeriodTime.range(course.startPeriod, course.endPeriod)
        val periodLabel = PeriodTime.periodsLabel(course.startPeriod, course.endPeriod)
        val weeks = weekRangeText(course)

        // ── 头部 ──
        b.tvDetailName.text = course.name
        b.tvDetailSubtitle.text = "$dayName $range · $weeks"

        // ── 信息行（左标签已在布局里写死，这里只填右值）──
        b.tvDetailTime.text = "$dayName $range"
        b.tvDetailPeriod.text = periodLabel
        b.tvDetailRoom.text = course.location.ifBlank { "未安排" }
        b.tvDetailTeacher.text = course.teacher.ifBlank { "未安排" }
        b.tvDetailWeeks.text = weeks

        // 学分：教务给的是字符串（如 "3.0"），空则整行隐藏
        if (course.credit.isBlank()) {
            b.rowCredit.visibility = View.GONE
        } else {
            b.rowCredit.visibility = View.VISIBLE
            b.tvDetailCredit.text = buildString {
                append(course.credit)
                if (course.note.isNotBlank()) append(" · ").append(course.note)
            }
        }

        val dialog = AlertDialog.Builder(context)
            .setView(b.root)
            .create()

        // ⚠️ 不用 setPositiveButton —— 那个按钮在卡片外。
        b.btnDetailClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        DialogCorner.applyRounded(dialog)
    }

    /** 周次文案，如「第 1-9 周」/「第 3-15 周（单）」。 */
    fun weekRangeText(course: Course): String {
        val base = if (course.startWeek == course.endWeek) {
            "第 ${course.startWeek} 周"
        } else {
            "第 ${course.startWeek}-${course.endWeek} 周"
        }
        return when (course.weekParity) {
            1 -> "$base（单周）"
            2 -> "$base（双周）"
            else -> base
        }
    }
}
