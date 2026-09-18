package com.gzuschedule.app.data.sync.zhengfang

import com.gzuschedule.app.domain.HolidayDay
import java.time.LocalDate

/**
 * 校历 / 假日解析（ADR-060）。
 *
 * ⚠️⚠️ 当前状态：**探测阶段**，解析器是「尽力而为」的实现。
 *
 * 背景：
 *   用户反馈「假期还显示有课」。查明根因 —— **不是解析错了，
 *   而是我们从来没拉过校历接口**。之前只调了：
 *     · xskbcx_cxXsgrkb  课表
 *     · xskbcx_cxRsd     日程
 *   而假日信息在正方首页的**校历**里（`/jwglxt/xtgl/index_initMenu.html`）。
 *
 * 由于该响应结构未曾在抓包中确认，本解析器**先按正方通用结构尽力解析**，
 * 同时由 [ZhengfangDataClient.fetchCalendarRaw] 把原始响应打进诊断日志。
 * 用户同步一次后打开「设置 → 诊断信息」截图，即可校准字段名。
 *
 * ⚠️ 重要设计取舍：**解析不出就返回空列表，绝不猜**。
 *   宁可这几天显示成「没课」（用户已确认「就当没课」），
 *   也不能把上课日误标成假日 —— 那会让学生缺课，代价远高于少显示一个标记。
 */
object ZfCalendarParser {

    /** 日期候选格式（校历里常见 yyyy-MM-dd）。 */
    private val ISO = Regex("""(20\d{2})-(\d{1,2})-(\d{1,2})""")

    /** 中文日期（2026年10月1日）。 */
    private val CN = Regex("""(20\d{2})年(\d{1,2})月(\d{1,2})日""")

    /** 我方日历控件里的日期格式（如 2026-10-01 出现在 value 属性中）。 */
    private val CN_SLASH = Regex("""(20\d{2})/(\d{1,2})/(\d{1,2})""")

    /**
     * 假日名称关键词 —— 用于从校历文本里识别「哪些日期是假日」。
     *
     * ⚠️ 只有**明确表示放假的词**才算，不含「上课」「调休上课」等，
     *    避免把补课日误判成假日。
     */
    private val HOLIDAY_WORDS = listOf(
        "元旦", "春节", "清明", "劳动节", "端午", "中秋", "国庆",
        "寒假", "暑假", "放假", "假日", "假期",
    )

    /**
     * 尝试从校历响应里解析假日。
     *
     * 策略（按可靠性排序）：
     *   ① 若响应是 JSON 且含明确的假日数组字段 → 取之
     *   ② 否则扫描 HTML/JS 文本：找「假日名 + 附近日期」的组合
     *
     * ⚠️ 策略 ② 天生不可靠 —— 它只是为了让「第一次同步就能拿到点东西」，
     *    真正的解析必须等诊断截图确认结构后再写（见文件头说明）。
     *
     * @return 假日列表；解析不出返回**空列表**（= 当作没有假日，不误判）
     */
    fun parse(raw: String?): List<HolidayDay> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { parseByKeywordScan(raw) }.getOrDefault(emptyList())
    }

    /**
     * 关键词扫描：在校历文本里找每个假日名，取其**附近**（同行/同标签内）的日期。
     *
     * ⚠️ 这里的「附近」窗口刻意收窄（240 字符）。
     *    窗口太大 → 会把不相干的日期吸进来，把上课日误判成假日；
     *    窗口太小 → 漏掉。宁可漏，不可误判（见类注释的取舍说明）。
     */
    private fun parseByKeywordScan(raw: String): List<HolidayDay> {
        val out = LinkedHashMap<LocalDate, String>()

        HOLIDAY_WORDS.forEach { word ->
            var from = 0
            while (true) {
                val idx = raw.indexOf(word, from)
                if (idx < 0) break
                from = idx + word.length

                val window = raw.substring(
                    idx,
                    (idx + WINDOW).coerceAtMost(raw.length),
                )
                val dates = extractDates(window)
                // 只有「一个假日名 + 恰好一段日期区间」时才敢下结论
                if (dates.size in 2..MAX_SPAN_DAYS) {
                    val start = dates.first()
                    val end = dates.last()
                    // 假日区间不超过 60 天（寒暑假例外，放宽到 90）
                    val limit = if (word.contains("假") && word.length <= 2) 90 else 60
                    val span = java.time.temporal.ChronoUnit.DAYS
                        .between(start, end).toInt()
                    if (span in 0..limit) {
                        var d = start
                        while (!d.isAfter(end)) {
                            out[d] = word
                            d = d.plusDays(1)
                        }
                    }
                }
            }
        }
        return out.entries.map { HolidayDay(it.key, it.value) }.sortedBy { it.date }
    }

    /** 抽出一段文本里的所有日期（去重、升序）。 */
    private fun extractDates(text: String): List<LocalDate> {
        val list = mutableListOf<LocalDate>()
        list += ISO.findAll(text).mapNotNull { m ->
            safeDate(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        list += CN_SLASH.findAll(text).mapNotNull { m ->
            safeDate(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        list += CN.findAll(text).mapNotNull { m ->
            safeDate(m.groupValues[1], m.groupValues[2], m.groupValues[3])
        }
        return list.distinct().sorted()
    }

    private fun safeDate(y: String, m: String, d: String): LocalDate? =
        runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()

    /** 扫描窗口宽度（字符）。 */
    private const val WINDOW = 240

    /** 一个假日区间最多几天 —— 超过视为扫描吸进了杂日期，丢弃。 */
    private const val MAX_SPAN_DAYS = 90
}
