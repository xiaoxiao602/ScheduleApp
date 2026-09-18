package com.gzuschedule.app.domain

import java.time.LocalDate

/**
 * 一天（日期）的「非教学日」状态（ADR-060）。
 *
 * ⚠️ 数据来源与课表**不是同一个接口**：
 *   课表    → `/jwglxt/kbcx/xskbcx_cxXsgrkb.html`
 *   日程    → `/jwglxt/kbcx/xskbcx_cxRsd.html`
 *   校历/假日 → `/jwglxt/xtgl/index_initMenu.html`（首页里的校历块）
 *
 * 用户反馈：「假期还显示有课 拉取的数据不能识别假期吗」
 * —— 根因不是「识别失败」，而是我们**从来没拉过校历接口**。
 * 校历响应结构尚未确认，故先由探针抓取、确认后再写解析器（见 ZfCalendarParser）。
 */
data class HolidayDay(
    /** 日期（ISO）。 */
    val date: LocalDate,
    /** 假日名称，如「国庆节」。取不到时用「假期」。 */
    val name: String,
)

/**
 * 假期区间集合 —— 课表渲染与「今天有课吗」都要查它。
 *
 * ⚠️ 为什么是「区间列表」而不是「日期集合」：
 *   校历返回的假日天然是连续区间（如国庆 7 天）；存区间体积小、可读性好，
 *   查表时线性扫描区间即可 —— 一学期通常不超过 10 个区间，无需优化。
 */
class HolidayCalendar(
    private val holidays: List<HolidayDay> = emptyList(),
) {

    /** 日期索引，避免每次查询都线性扫。 */
    private val byDate: Map<LocalDate, String> =
        holidays.associate { it.date to it.name }

    val isEmpty: Boolean get() = byDate.isEmpty()

    /** 该日期是否为假日（非教学日）。 */
    fun isHoliday(date: LocalDate): Boolean = byDate.containsKey(date)

    /** 假日名称；不是假日返回 null。 */
    fun nameOf(date: LocalDate): String? = byDate[date]

    /** 该周的周一..周日里，哪几天是假日。key = dayOfWeek(1..7)。 */
    fun holidaysOfWeek(monday: LocalDate): Map<Int, String> {
        if (byDate.isEmpty()) return emptyMap()
        val result = LinkedHashMap<Int, String>(2)
        for (d in 0..6) {
            val date = monday.plusDays(d.toLong())
            byDate[date]?.let { result[d + 1] = it }
        }
        return result
    }

    companion object {
        val EMPTY = HolidayCalendar(emptyList())

        /**
         * 从 JSON 反序列化（meta 表存的就是这个格式）。
         *
         * ⚠️ 手写解析而不用 Gson：本类在 `domain` 层，
         *    引入 Gson 会让领域层依赖具体序列化框架。
         *    格式很简单（`[{"date":"2026-10-01","name":"国庆节"},...]`），
         *    手写正则足够，且任何异常都收敛为 EMPTY —— 假期数据缺失
         *    绝不能让整个课表崩掉。
         */
        fun fromJson(json: String?): HolidayCalendar {
            if (json.isNullOrBlank()) return EMPTY
            return runCatching {
                val items = Regex(
                    """\{\s*"date"\s*:\s*"([^"]+)"\s*,\s*"name"\s*:\s*"([^"]*)"\s*}""",
                ).findAll(json).mapNotNull { m ->
                    val date = runCatching { LocalDate.parse(m.groupValues[1]) }.getOrNull()
                        ?: return@mapNotNull null
                    HolidayDay(date, m.groupValues[2].ifBlank { "假期" })
                }.toList()
                if (items.isEmpty()) EMPTY else HolidayCalendar(items)
            }.getOrDefault(EMPTY)
        }

        /** 序列化为 meta 表可存的 JSON。 */
        fun toJson(holidays: List<HolidayDay>): String =
            holidays.joinToString(",", "[", "]") { h ->
                """{"date":"${h.date}","name":"${h.name.replace("\"", "")}"}"""
            }
    }
}
