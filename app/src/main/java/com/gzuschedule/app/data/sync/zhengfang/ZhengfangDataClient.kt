package com.gzuschedule.app.data.sync.zhengfang

import com.gzuschedule.app.data.auth.LoginDiagnostics
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 正方数据拉取器（ADR-008）。
 *
 * 依赖已建立的会话（由 [ZhengfangSessionClient] 完成）。
 * 同一 CookieJar 的 OkHttpClient 复用即可。
 */
class ZhengfangDataClient(
    private val client: OkHttpClient,
    private val gson: Gson = Gson(),
) {

    companion object {
        const val JWXT_BASE = "https://jwxt.gzus.edu.cn"

        /** 课表接口（⚠️ 是 cxXsgrkb 不是 cxXsKb） */
        const val SCHEDULE_PATH = "/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151"

        /** 成绩接口 */
        const val GRADE_PATH = "/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005"

        /** 考试接口 */
        const val EXAM_PATH = "/jwglxt/kwgl/kscx_cxXsksxxIndex.html?doType=query&gnmkdm=N358105"

        /**
         * 日程配置接口（cxRsd）—— 学期日期区间的来源。
         * 课表页面加载时额外调它拿日期，故不能只看课表数据接口。
         */
        const val SCHEDULE_DATE_PATH = "/jwglxt/kbcx/xskbcx_cxRsd.html?gnmkdm=N2151"

        /**
         * 正方首页 —— 校历 / 假日信息的来源（ADR-060）。
         *
         * ⚠️ 定位过程：用户反馈「假期还显示有课」。
         * 排查发现课表(cxXsgrkb)与日程(cxRsd)两个接口都**不含**假日信息，
         * 假日归「校历」管，而校历挂在教务首页上。
         *
         * ⚠️ 会话内一次性：不能重新登录，必须在同步的会话里顺手抓。
         * 同步流程的落地页恰好就是它（见 ZhengfangSessionClient.JWXT_HOME），
         * 因此这个请求同样能通过 Referer 校验。
         */
        const val CALENDAR_PATH = "/jwglxt/xtgl/index_initMenu.html"

        /** 校区编号（抓包确认：xqh_id=01） */
        const val CAMPUS_ID = "01"

        /** 课表页 Referer，服务端会校验来源 */
        const val SCHEDULE_REFERER =
            "$JWXT_BASE/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default"

        const val UA = ZhengfangSessionClient.UA

        /**
         * 诊断日志里响应的截断长度。
         *
         * ⚠️ 首页 HTML 动辄几万字符，全量写进内存日志会挤掉前面的登录日志
         *    （LoginDiagnostics 上限 40 条）。2000 字符足够看到结构。
         */
        const val DIAG_SNIPPET = 2000
    }

    /** 学期参数。 */
    data class Term(val year: String, val term: String) {
        companion object {
            /** 2026-2027 学年第 1 学期（抓包确认：xnm=2026, xqm=3） */
            val CURRENT = Term("2026", "3")
        }
    }

    /**
     * 拉取课表。
     *
     * 抓包确认的请求体：
     *   kclbdm=  kclxdm=  kzlx=ck  xnm=2026  xqm=3  xsdm=
     *
     * @return 解析结果；失败返回 null（区别于「成功但无课」的空列表）
     */
    fun fetchSchedule(term: Term = Term.CURRENT): ParseResult? {
        val form = FormBody.Builder()
            .add("kclbdm", "")
            .add("kclxdm", "")
            .add("kzlx", "ck")
            .add("xnm", term.year)
            .add("xqm", term.term)
            .add("xsdm", "")
            .build()

        val req = Request.Builder()
            .url(JWXT_BASE + SCHEDULE_PATH)
            .post(form)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", JWXT_BASE)
            .header(
                "Referer",
                "$JWXT_BASE/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?gnmkdm=N2151&layout=default",
            )
            .build()

        val body = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return null

        // 会话失效时服务端返回 HTML 登录页，解析器会安全返回空结果。
        // 这里额外区分「HTML」与「真空课表」，避免把失效误当成功。
        if (body.trimStart().startsWith("<")) return null

        return ZfScheduleParser.parse(body)
    }

    /**
     * 拉取【日程配置】(cxRsd) —— 学期日期区间的来源。
     *
     * ⚠️ 关键发现（用户提示「官网课表上就有日期」后定位）：
     * 课表页面加载时会额外调这个接口拿日期，而课表数据接口(cxXsgrkb)
     * 本身不一定含学期起始日。此前只盯着 cxXsgrkb，故一直找不到。
     *
     * 参数与课表一致：xnm / xqm / xqh_id(=01 校区)。
     * 响应结构尚未确认，故先返回原始 JSON 并打诊断，确认后再解析。
     */
    fun fetchScheduleDateRangeRaw(term: Term = Term.CURRENT): String? {
        val form = FormBody.Builder()
            .add("xnm", term.year)
            .add("xqm", term.term)
            .add("xqh_id", CAMPUS_ID)
            .build()

        val req = Request.Builder()
            .url(JWXT_BASE + SCHEDULE_DATE_PATH)
            .post(form)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Origin", JWXT_BASE)
            .header("Referer", SCHEDULE_REFERER)
            .build()

        val body = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull()

        if (body != null) {
            LoginDiagnostics.log("cxRsd 响应(${body.length}字符): ${body.take(240)}")
        } else {
            LoginDiagnostics.log("cxRsd 无响应")
        }
        return body
    }

    /**
     * 拉取【校历首页】—— 校历 / 假日信息的来源（ADR-060）。
     *
     * ⚠️ 这是**探测性**请求：响应结构未确认，故：
     *   1. 原始响应前 [DIAG_SNIPPET] 字符写进诊断日志（用户截图即可校准）
     *   2. 不在此处解析，交由 [ZfCalendarParser]（那也是尽力而为）
     *
     * 必须带 Referer = 首页本身，否则正方会把它当成非法跳转。
     */
    fun fetchCalendarRaw(): String? {
        val url = JWXT_BASE + CALENDAR_PATH
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            .header("Referer", url)
            .build()

        val body = runCatching {
            client.newCall(req).execute().use { resp ->
                LoginDiagnostics.log("校历 HTTP ${resp.code} len=${resp.body?.contentLength()}")
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return null

        if (body.trimStart().startsWith("<") && body.contains("统一身份认证")) {
            LoginDiagnostics.log("校历：会话失效（返回登录页）")
            return null
        }

        // ⚠️ 诊断用：把含「假」字的片段单独打出来，比整页更省事——
        //    校历块在首页 HTML/JS 里，整页几万字符，全打用户也截不全。
        val snippet = body.take(DIAG_SNIPPET)
        LoginDiagnostics.log("校历响应(${body.length}字符): $snippet")
        val holidayHits = Regex("""[^<>{}]{0,60}假[^<>{}]{0,60}""")
            .findAll(body).take(6).map { it.value.trim() }.toList()
        if (holidayHits.isNotEmpty()) {
            LoginDiagnostics.log("含「假」片段:\n" + holidayHits.joinToString("\n"))
        }
        return body
    }


    /**
     * 拉取成绩。
     *
     * ⚠️ 响应格式尚未经真实数据验证（学生刚入学，无成绩）。
     * 因此这里只返回原始 JSON，解析器待有数据时补充。
     */
    fun fetchGradesRaw(term: Term = Term.CURRENT): String? {
        val ts = System.currentTimeMillis()
        val form = FormBody.Builder()
            .add("_search", "false")
            .add("nd", ts.toString())
            .add("queryModel.currentPage", "1")
            .add("queryModel.showCount", "100")
            .add("queryModel.sortName", " ")
            .add("queryModel.sortOrder", "asc")
            .add("time", "0")
            .add("xnm", term.year)
            .add("xqm", term.term)
            .add("kcbj", "")
            .add("sfzgcj", "")
            .build()

        return postJson(GRADE_PATH, form, "/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005&layout=default")
    }

    /**
     * 拉取考试安排。
     *
     * ⚠️ 同成绩：响应格式未验证。
     */
    fun fetchExamsRaw(term: Term = Term.CURRENT): String? {
        val ts = System.currentTimeMillis()
        val form = FormBody.Builder()
            .add("_search", "false")
            .add("nd", ts.toString())
            .add("queryModel.currentPage", "1")
            .add("queryModel.showCount", "100")
            .add("queryModel.sortName", " ")
            .add("queryModel.sortOrder", "asc")
            .add("time", "0")
            .add("xnm", term.year)
            .add("xqm", term.term)
            .add("kc", "")
            .add("kch", "")
            .build()

        return postJson(EXAM_PATH, form, "/jwglxt/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default")
    }

    private fun postJson(path: String, form: FormBody, refererPath: String): String? {
        val req = Request.Builder()
            .url(JWXT_BASE + path)
            .post(form)
            .header("User-Agent", UA)
            .header("Accept", "*/*")
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            .header("Origin", JWXT_BASE)
            .header("Referer", JWXT_BASE + refererPath)
            .build()

        val body = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.body?.string()
            }
        }.getOrNull() ?: return null

        if (body.trimStart().startsWith("<")) return null   // 会话失效
        return body
    }

    /** 成绩响应模型（字段名待真实数据校准）。 */
    data class GradeResponse(
        @SerializedName("items") val items: List<GradeItem>? = null,
        @SerializedName("totalResult") val totalResult: String? = null,
    )

    data class GradeItem(
        @SerializedName("kcmc") val courseName: String? = null,
        @SerializedName("cj") val score: String? = null,
        @SerializedName("xf") val credit: String? = null,
        @SerializedName("jd") val gradePoint: String? = null,
        @SerializedName("xnm") val year: String? = null,
        @SerializedName("xqm") val term: String? = null,
        @SerializedName("kcbj") val courseAttr: String? = null,
    )

    /** 考试响应模型（字段名待真实数据校准）。 */
    data class ExamResponse(
        @SerializedName("items") val items: List<ExamItem>? = null,
    )

    data class ExamItem(
        @SerializedName("kcmc") val courseName: String? = null,
        @SerializedName("kssj") val examTime: String? = null,
        @SerializedName("cdmc") val location: String? = null,
        @SerializedName("ksfsmc") val examType: String? = null,
        @SerializedName("xf") val credit: String? = null,
    )
}
