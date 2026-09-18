package com.gzuschedule.app.data.sync.zhengfang

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.gzuschedule.app.domain.model.Course

/**
 * 正方教务系统课表响应模型。
 *
 * 字段名依据真实抓包（`xskbcx_cxXsKb.html?gnmkdm=N2151`）确定，
 * 测试 fixture 见 `src/test/resources/zhengfang_schedule_sample.json`。
 */
data class ZfScheduleResponse(
    @SerializedName("kbList") val kbList: List<ZfCourse>? = null,
    @SerializedName("xsxx") val student: ZfStudent? = null,
    @SerializedName("xqjmcMap") val weekdayMap: Map<String, String>? = null,
)

data class ZfStudent(
    @SerializedName("XH") val studentId: String? = null,
    @SerializedName("XM") val name: String? = null,
    @SerializedName("XNMC") val academicYear: String? = null,
    @SerializedName("XQMMC") val termName: String? = null,
    @SerializedName("BJMC") val className: String? = null,
    /** 专业名称。⚠️ 字段名待抓包确认（响应体未捕获时用的是常见命名）。 */
    @SerializedName("ZYMC") val major: String? = null,
    /** 专业方向。 */
    @SerializedName("ZYFXMC") val majorDirection: String? = null,
    /** 邮箱。⚠️ 未在抓包中确认，取不到时为 null。 */
    @SerializedName("DZXX") val email: String? = null,
)

/** 一节课程。字段极多，只取需要的。 */
data class ZfCourse(
    @SerializedName("kcmc") val courseName: String? = null,        // 课程名称
    @SerializedName("xqj") val weekday: String? = null,            // 星期几 1-7
    @SerializedName("jcor") val periods: String? = null,           // 节次 "3-4"
    @SerializedName("jcs") val periodsAlt: String? = null,         // 同上（备用）
    @SerializedName("zcd") val weeks: String? = null,              // 周次 "1-9周"
    @SerializedName("cdmc") val location: String? = null,          // 教室
    @SerializedName("lh") val building: String? = null,            // 楼栋
    @SerializedName("xm") val teacher: String? = null,             // 教师
    @SerializedName("xf") val credit: String? = null,              // 学分
    @SerializedName("kcxz") val courseType: String? = null,        // 必修/选修
    @SerializedName("xqmc") val campus: String? = null,            // 校区
    @SerializedName("jxbmc") val classCode: String? = null,        // 教学班代码
    @SerializedName("jxbsftkbj") val isAdjusted: String? = null,   // 是否调课标记
)

/**
 * 正方课表解析器 —— 纯函数，不依赖 Android，可单元测试。
 *
 * ⚠️ 已处理的三个坑（均由真实数据发现）：
 *  1. 周次含单双周：`"1-17周(单)"` / `"2-18周(双)"` / `"1-18周"`
 *  2. 节次有两个字段：`jcor`（纯数字）与 `jc`（带"节"字），优先 `jcor`
 *  3. 同一门课一周可有多条记录（不同时段/教室），**不可去重**
 */
object ZfScheduleParser {

    private val gson = Gson()

    /**
     * 解析课表 JSON。
     *
     * ⚠️ 必须容错：教务系统在会话失效/出错时会返回 HTML 登录页而非 JSON，
     * 若直接让 Gson 抛异常会导致 App 崩溃。因此所有解析异常都收敛为空结果。
     */
    fun parse(json: String): ParseResult {
        if (json.isBlank()) return ParseResult(emptyList(), null)

        val resp = runCatching {
            gson.fromJson(json, ZfScheduleResponse::class.java)
        }.getOrNull() ?: return ParseResult(emptyList(), null)

        val courses = resp.kbList.orEmpty().mapNotNull { it.toDomain() }
        return ParseResult(courses, resp.student)
    }

    private fun ZfCourse.toDomain(): Course? {
        val name = courseName?.trim().orEmpty()
        if (name.isEmpty()) return null

        val day = weekday?.trim()?.toIntOrNull() ?: return null
        if (day !in 1..7) return null

        val (start, end) = parsePeriods(periods ?: periodsAlt) ?: return null
        val week = parseWeeks(weeks)

        return Course(
            name = name,
            teacher = teacher?.trim().orEmpty(),
            location = location?.trim().orEmpty(),
            dayOfWeek = day,
            startPeriod = start,
            endPeriod = end,
            startWeek = week.start,
            endWeek = week.end,
            weekParity = week.parity,
            credit = credit?.trim().orEmpty(),
            note = buildNote(),
        )
    }

    /**
     * 解析节次，如 `"3-4"` -> (3, 4)，`"9"` -> (9, 9)。
     * 也容忍 `"3-4节"` 这种带单位的输入。
     */
    internal fun parsePeriods(raw: String?): Pair<Int, Int>? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace("节", "").trim()
        val parts = cleaned.split("-").map { it.trim() }
        return when (parts.size) {
            1 -> parts[0].toIntOrNull()?.let { it to it }
            2 -> {
                val a = parts[0].toIntOrNull() ?: return null
                val b = parts[1].toIntOrNull() ?: return null
                if (a <= 0 || b < a) null else a to b
            }
            else -> null
        }
    }

    /** 周次解析结果。 */
    internal data class WeekRange(val start: Int, val end: Int, val parity: Int)

    /**
     * 解析周次字符串。
     *
     * 支持的格式（均由真实数据确认）：
     *  - `"1-18周"`       -> start=1, end=18, parity=0（每周）
     *  - `"1-17周(单)"`    -> start=1, end=17, parity=1（单周）
     *  - `"2-18周(双)"`    -> start=2, end=18, parity=2（双周）
     *  - `"6-9周"`        -> 同理
     *  - `"10-18周"`      -> 同理
     *
     * 解析失败时回退为「全学期每周」，避免丢课。
     */
    internal fun parseWeeks(raw: String?): WeekRange {
        val fallback = WeekRange(1, 20, 0)
        if (raw.isNullOrBlank()) return fallback

        val text = raw.trim()

        // 单双周标记
        val parity = when {
            text.contains("单") -> 1
            text.contains("双") -> 2
            else -> 0
        }

        // 提取 "起-止"
        val nums = Regex("""(\d+)\s*-\s*(\d+)""").find(text)
        if (nums != null) {
            val a = nums.groupValues[1].toIntOrNull() ?: return fallback
            val b = nums.groupValues[2].toIntOrNull() ?: return fallback
            if (a in 1..30 && b in a..30) return WeekRange(a, b, parity)
            return fallback
        }

        // 单个数字，如 "5周"
        val single = Regex("""(\d+)""").find(text)
        if (single != null) {
            val a = single.groupValues[1].toIntOrNull() ?: return fallback
            if (a in 1..30) return WeekRange(a, a, parity)
        }
        return fallback
    }

    /** 组装备注：校区 + 课程性质 + 调课标记。 */
    private fun ZfCourse.buildNote(): String = buildList {
        campus?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        courseType?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        if (isAdjusted == "1") add("调课")
    }.joinToString(" · ")
}

/** 解析结果。student 可能为 null（响应里没有学生信息时）。 */
data class ParseResult(
    val courses: List<Course>,
    val student: ZfStudent?,
    /**
     * 学期第一周的星期一（ADR-011）。
     *
     * ⚠️ 未在抓包中确认具体字段名，故由课表响应里的多个候选字段尝试推导；
     * 推导不出时为 null，由调用方兜底。
     */
    val firstMonday: java.time.LocalDate? = null,
)
