package com.gzuschedule.app.domain.model

import java.time.LocalDate
import java.time.LocalTime

/**
 * 一节课（课表条目）。
 *
 * 数据来源为教务系统，本地缓存于 Room。
 */
data class Course(
    /** 本地主键（自增），0 表示未入库。 */
    val id: Long = 0,
    /** 课程名称。 */
    val name: String,
    /** 教师姓名。 */
    val teacher: String = "",
    /** 上课地点。 */
    val location: String = "",
    /** 星期几，1=周一 … 7=周日。 */
    val dayOfWeek: Int,
    /** 第几节开始（1-based）。 */
    val startPeriod: Int,
    /** 第几节结束（含）。 */
    val endPeriod: Int,
    /** 起始周（含）。 */
    val startWeek: Int = 1,
    /** 结束周（含）。 */
    val endWeek: Int = 20,
    /** 单双周：0=每周, 1=单周, 2=双周。 */
    val weekParity: Int = 0,
    /** 学分（若有）。 */
    val credit: String = "",
    /** 原始课表数据中的备注。 */
    val note: String = "",
) {
    /** 是否在指定周次上课。 */
    fun occursInWeek(week: Int): Boolean {
        if (week < startWeek || week > endWeek) return false
        return when (weekParity) {
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
        }
    }

    /** 节次范围，如 "1-2"。 */
    val periodRange: String
        get() = if (startPeriod == endPeriod) "$startPeriod" else "$startPeriod-$endPeriod"
}

/** 考试安排。 */
data class Exam(
    val id: Long = 0,
    val courseName: String,
    /** 考试日期（ISO）。 */
    val date: LocalDate?,
    /** 开始时间。 */
    val startTime: LocalTime?,
    /** 结束时间。 */
    val endTime: LocalTime?,
    val location: String = "",
    /** 座位号。 */
    val seat: String = "",
    /** 考试形式，如「闭卷」。 */
    val format: String = "",
    val note: String = "",
) {
    val isUpcoming: Boolean
        get() = date != null && !date.isBefore(LocalDate.now())
}

/** 单门课程成绩。 */
data class Grade(
    val id: Long = 0,
    /** 学年学期，如 "2025-2026-1"。 */
    val term: String,
    val courseName: String,
    /** 成绩（可能是数字或等级，故用字符串）。 */
    val score: String,
    /** 绩点。 */
    val gpa: String = "",
    val credit: String = "",
    /** 课程性质，如「必修」。 */
    val category: String = "",
    /** 学分绩点。 */
    val creditGpa: String = "",
)

/** 学期信息。 */
data class Term(
    /** 如 "2025-2026-1"。 */
    val code: String,
    val displayName: String,
) {
    companion object {
        /** 从学期码推断显示名，如 "2025-2026-1" -> "2025-2026 学年第一学期"。 */
        fun displayOf(code: String): String {
            val parts = code.split("-")
            return if (parts.size >= 3) {
                val termName = when (parts[2]) {
                    "1" -> "第一学期"
                    "2" -> "第二学期"
                    "3" -> "第三学期"
                    else -> "第${parts[2]}学期"
                }
                "${parts[0]}-${parts[1]} 学年$termName"
            } else code
        }
    }
}
