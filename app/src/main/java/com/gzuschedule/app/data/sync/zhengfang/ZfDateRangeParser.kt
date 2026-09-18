package com.gzuschedule.app.data.sync.zhengfang

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 从 `cxRsd`（日程配置）响应中提取学期第一周的星期一。
 *
 * ⚠️ 为什么写得这么"宽容"：
 * `cxRsd` 的响应结构**尚未经真实数据确认**（抓包时该响应体未捕获）。
 * 与其猜一个字段名然后在真机上失败，不如：
 *   1. 优先按已知候选字段名精确匹配
 *   2. 失败则退化到「扫描响应里所有 ISO 日期，取最早的那个」
 *   3. 再失败则由调用方兜底
 *
 * 第 2 条的依据：日程接口必然返回该学期的日期区间，
 * 取最早日期即为学期起始；若恰好落在周一，就是我们要的基准。
 *
 * 一旦真机确认了字段名，应把精确匹配提到前面并删掉兜底扫描（见 ADR-011）。
 */
object ZfDateRangeParser {

    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE

    /** 优先尝试的字段名（按可能性排序，均未真机确认）。 */
    private val KEY_CANDIDATES = listOf(
        "xqkssj", "kkqssj", "xqksrq", "kssj", "qsrq",
        "xqks", "sjqssj", "firstDay", "startDate", "ksrq",
    )

    /** 任意位置的 ISO 日期（yyyy-MM-dd）。 */
    private val ANY_ISO = Regex("""(20\d{2})-(\d{1,2})-(\d{1,2})""")

    /** 中文日期（2026年8月31日）。 */
    private val ANY_CN = Regex("""(20\d{2})年(\d{1,2})月(\d{1,2})日""")

    /**
     * @return 第一周星期一；解析不出返回 null
     */
    fun firstMonday(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null

        // ---- ① 精确字段名优先 ----
        for (key in KEY_CANDIDATES) {
            val re = Regex(""""$key"\s*:\s*"([^"]{6,20})"""")
            val hit = re.find(raw)?.groupValues?.get(1) ?: continue
            parseDate(hit)?.let { return normalize(it) }
        }

        // ---- ② 兜底：扫描所有日期，取最早的 ----
        val all = mutableListOf<LocalDate>()
        ANY_ISO.findAll(raw).forEach { m ->
            runCatching {
                all += LocalDate.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                )
            }
        }
        ANY_CN.findAll(raw).forEach { m ->
            runCatching {
                all += LocalDate.of(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt(),
                )
            }
        }
        return all.minOrNull()?.let { normalize(it) }
    }

    /**
     * 归一化为「该周的星期一」。
     * 教务返回的起始日未必正好是周一，而周次计算必须以周一为基准，
     * 否则整体会偏移 —— 故统一往前归到本周一。
     */
    private fun normalize(d: LocalDate): LocalDate =
        d.minusDays((d.dayOfWeek.value - 1).toLong())

    private fun parseDate(s: String): LocalDate? {
        runCatching { return LocalDate.parse(s, ISO) }
        runCatching {
            val m = ANY_ISO.find(s) ?: return null
            return LocalDate.of(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
            )
        }
        return null
    }
}
