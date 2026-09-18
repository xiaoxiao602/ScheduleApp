package com.gzuschedule.app.data.sync.zhengfang

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.gzuschedule.app.domain.model.Exam
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 正方【考试安排】解析器（ADR-013）。
 *
 * 接口：POST /jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105
 * 响应形如 jqGrid 的分页结构：`{"items":[{...}], "totalResult":N}`
 *
 * ⚠️ 字段名依据正方 jwglxt 通用命名（cxXsksxx = 学生考试信息），
 * 但**本校响应尚未用真实数据验证**（学生刚入学、暂无考试），
 * 故解析器对每个字段都做容错：缺失即回退空串/null，不抛异常。
 * 一旦拿到真实数据，应据实收紧（见 ADR-013 后续项）。
 */
object ZfExamParser {

    /** jqGrid 外层。 */
    private data class Wrapper(
        @SerializedName("items") val items: List<Item>? = null,
    )

    private data class Item(
        /** 课程名。 */
        @SerializedName("kcmc") val courseName: String? = null,
        /** 考试时间，如 "2026-01-05 09:00~11:00"。 */
        @SerializedName("kssj") val examTime: String? = null,
        /** 考试日期（部分学校单独给）。 */
        @SerializedName("ksrq") val examDate: String? = null,
        /** 开始时间。 */
        @SerializedName("kssjStr") val startStr: String? = null,
        /** 结束时间。 */
        @SerializedName("jssjStr") val endStr: String? = null,
        /** 考场。 */
        @SerializedName("cdmc") val room: String? = null,
        /** 座位号。 */
        @SerializedName("zwh") val seat: String? = null,
        /** 考试形式（闭卷/开卷）。 */
        @SerializedName("ksxs") val format: String? = null,
        /** 考试校区。 */
        @SerializedName("xqmc") val campus: String? = null,
        /** 备注。 */
        @SerializedName("bz") val note: String? = null,
    )

    private val gson = Gson()

    // 支持的日期格式
    private val DATE_FMTS = listOf(
        DateTimeFormatter.ofPattern("yyyy-MM-dd"),
        DateTimeFormatter.ofPattern("yyyy/MM/dd"),
        DateTimeFormatter.ofPattern("yyyy年M月d日"),
    )
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * 解析考试列表。
     *
     * @return 解析出的考试；无数据或格式不符返回空列表（**不抛异常**）
     */
    fun parse(raw: String?, term: String): List<Exam> {
        if (raw.isNullOrBlank()) return emptyList()
        // 会话失效时服务端返回 HTML 登录页
        if (raw.trimStart().startsWith("<")) return emptyList()

        val wrapper = runCatching { gson.fromJson(raw, Wrapper::class.java) }.getOrNull()
        val items = wrapper?.items ?: return emptyList()

        return items.mapNotNull { item ->
            val name = item.courseName?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null

            // 日期与时间可能来自合并字段，也可能分开给
            val (date, start, end) = extractWhen(item)

            Exam(
                courseName = name,
                date = date,
                startTime = start,
                endTime = end,
                location = listOfNotNull(
                    item.campus?.takeIf { it.isNotBlank() },
                    item.room?.takeIf { it.isNotBlank() },
                ).joinToString(" "),
                seat = item.seat.orEmpty(),
                format = item.format.orEmpty(),
                note = item.note.orEmpty(),
            )
        }
    }

    /**
     * 从可能的多种字段组合中取出 (日期, 开始, 结束)。
     * 容错点：`kssj` 常是 "2026-01-05 09:00~11:00" 这种合并串。
     */
    private fun extractWhen(item: Item): Triple<LocalDate?, LocalTime?, LocalTime?> {
        val merged = item.examTime.orEmpty()
        var date = parseDate(item.examDate) ?: parseDate(merged)
        var start = parseTime(item.startStr)
        var end = parseTime(item.endStr)

        // 从合并串里补全
        if (merged.isNotBlank()) {
            val datePart = Regex("""\d{4}[-/年]\d{1,2}[-/月]\d{1,2}""").find(merged)?.value
            if (date == null && datePart != null) date = parseDate(datePart)

            if (start == null || end == null) {
                val times = Regex("""\d{1,2}:\d{2}""").findAll(merged).map { it.value }.toList()
                if (start == null && times.isNotEmpty()) start = parseTime(times[0])
                if (end == null && times.size > 1) end = parseTime(times[1])
            }
        }
        return Triple(date, start, end)
    }

    private fun parseDate(s: String?): LocalDate? {
        if (s.isNullOrBlank()) return null
        for (fmt in DATE_FMTS) {
            runCatching { return LocalDate.parse(s.trim(), fmt) }
        }
        // 从长串里抠日期
        val m = Regex("""(\d{4})[-/年](\d{1,2})[-/月](\d{1,2})""").find(s) ?: return null
        return runCatching {
            LocalDate.of(
                m.groupValues[1].toInt(),
                m.groupValues[2].toInt(),
                m.groupValues[3].toInt(),
            )
        }.getOrNull()
    }

    private fun parseTime(s: String?): LocalTime? {
        if (s.isNullOrBlank()) return null
        runCatching { return LocalTime.parse(s.trim(), TIME_FMT) }
        val m = Regex("""(\d{1,2}):(\d{2})""").find(s) ?: return null
        return runCatching {
            LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
        }.getOrNull()
    }
}
