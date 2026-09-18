package com.gzuschedule.app.domain.usecase

import com.gzuschedule.app.data.auth.LoginDiagnostics
import com.gzuschedule.app.data.local.AppDatabase
import com.gzuschedule.app.data.local.entity.MetaEntity
import com.gzuschedule.app.data.sync.zhengfang.ZfCalendarParser
import com.gzuschedule.app.data.sync.zhengfang.ZfExamParser
import com.gzuschedule.app.data.sync.zhengfang.ZfDateRangeParser
import com.gzuschedule.app.data.sync.zhengfang.ParseResult
import com.gzuschedule.app.data.sync.zhengfang.ZhengfangDataClient
import com.gzuschedule.app.data.sync.zhengfang.ZhengfangSessionClient
import com.gzuschedule.app.domain.HolidayCalendar
import com.gzuschedule.app.domain.WeekCalculator
import java.time.LocalDate
import com.gzuschedule.app.domain.repository.ExamRepository
import com.gzuschedule.app.domain.repository.GradeRepository
import com.gzuschedule.app.domain.repository.ScheduleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步用例 —— 登录、拉数据、写库、登出（ADR-005 + ADR-008）。
 *
 * ⚠️ 核心安全约定（ADR-005）：
 *  1. 账密只作为函数参数传入，**不保存到任何字段或存储**
 *  2. 会话 Cookie 只存在**内存 CookieJar**，同步结束即丢弃
 *  3. 无论成功失败，**都必须清理凭据引用**
 */
class SyncUseCase(
    private val db: AppDatabase,
    private val scheduleRepo: ScheduleRepository,
    private val gradeRepo: GradeRepository,
    private val examRepo: ExamRepository,
) {

    /** 内存 CookieJar —— 不持久化，随对象销毁。 */
    private class MemoryCookieJar : CookieJar {
        private val store = mutableMapOf<String, MutableList<Cookie>>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val key = url.host
            val list = store.getOrPut(key) { mutableListOf() }
            cookies.forEach { new ->
                list.removeAll { it.name == new.name }
                list.add(new)
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            return store[url.host]
                ?.filter { it.expiresAt > now }
                .orEmpty()
        }

        /** 是否已存在指定域名下的某个 Cookie（用于判断会话是否建立）。 */
        @Synchronized
        fun has(host: String, name: String): Boolean =
            store[host]?.any { it.name == name } == true

        @Synchronized
        fun clear() = store.clear()
    }

    /** 同步进度，供 UI 显示。 */
    sealed interface Progress {
        data object LoggingIn : Progress
        data object BootstrapSession : Progress
        data object FetchingSchedule : Progress
        data object FetchingGrades : Progress
        data object FetchingExams : Progress
        data object Saving : Progress
        data object LoggingOut : Progress
    }

    /** 同步结果。 */
    data class Result(
        val success: Boolean,
        val courseCount: Int = 0,
        val message: String? = null,
        val serverCode: String? = null,
    )

    /**
     * 执行同步。
     *
     * @param username 学号（明文，仅本次调用使用）
     * @param encryptedPassword 已加密的密码（倒序+无填充RSA+hex）
     * @param captchaId 验证码 uid
     * @param captchaCode 验证码答案
     * @param term 学期（默认当前）
     * @param onProgress 进度回调
     */
    suspend fun execute(
        username: String,
        encryptedPassword: String,
        captchaId: String?,
        captchaCode: String?,
        term: ZhengfangDataClient.Term = ZhengfangDataClient.Term.CURRENT,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {

        val cookieJar = MemoryCookieJar()
        val http = ZhengfangSessionClient.defaultClient(cookieJar)

        try {
            // ---- ① 登录 + 建立会话 ----
            onProgress(Progress.LoggingIn)
            val session = ZhengfangSessionClient(http).apply {
                // 会话是否建立，看 CookieJar 里有没有正方的 JSESSIONID
                sessionCookieProbe = { cookieJar.has("jwxt.gzus.edu.cn", "JSESSIONID") }
            }
            val loginResult = session.establish(
                username = username,
                encryptedPassword = encryptedPassword,
                captchaId = captchaId,
                captchaCode = captchaCode,
            )

            when (loginResult) {
                is ZhengfangSessionClient.Result.Failure ->
                    return@withContext Result(
                        success = false,
                        message = loginResult.reason,
                        serverCode = loginResult.serverCode,
                    )
                ZhengfangSessionClient.Result.Success -> Unit
            }

            val data = ZhengfangDataClient(http)

            // ---- ①b 日程配置：取学期日期区间（ADR-011）----
            // ⚠️ 课表数据接口(cxXsgrkb)未必含学期起始日；
            //    课表页面会另调 cxRsd 拿日期 —— 这里复现该调用。
            val dateRangeRaw = runCatching {
                data.fetchScheduleDateRangeRaw(term)
            }.getOrNull()

            // ---- ①c 校历：取假日区间（ADR-060）----
            // ⚠️ 纯增量：失败**绝不影响**同步结果（runCatching 全包）。
            //    必须在会话有效期内调 —— 会话在 finally 里就销毁了。
            val calendarRaw = runCatching { data.fetchCalendarRaw() }.getOrNull()

            // ---- ② 拉课表 ----
            onProgress(Progress.FetchingSchedule)
            val schedule: ParseResult =
                data.fetchSchedule(term)
                    ?: return@withContext Result(
                        success = false,
                        message = "获取课表失败（会话可能已失效）",
                    )

            val courses = schedule.courses
            if (courses.isEmpty()) {
                return@withContext Result(
                    success = false,
                    message = "未获取到课程数据（可能是学期参数不正确或本学期无课）",
                )
            }

            // ---- ③ 拉成绩（可能为空，不视为失败）----
            onProgress(Progress.FetchingGrades)
            val gradesRaw = runCatching { data.fetchGradesRaw(term) }.getOrNull()

            // ---- ④ 拉考试（可能为空）----
            onProgress(Progress.FetchingExams)
            val examsRaw = runCatching { data.fetchExamsRaw(term) }.getOrNull()

            // ---- ⑤ 写入本地数据库 ----
            onProgress(Progress.Saving)
            val termLabel = "${term.year}-${term.year.toInt() + 1}学年第${termPrefix(term.term)}学期"
            scheduleRepo.saveCourses(termLabel, courses)
            db.metaDao().put(MetaEntity(AppDatabase.MetaKeys.CURRENT_TERM, termLabel))

            // 成绩/考试解析器待数据到位后补充；此处仅在能解析时写入
            runCatching { gradesRaw?.let { parseAndSaveGrades(it, termLabel) } }
            runCatching { examsRaw?.let { parseAndSaveExams(it, termLabel) } }

            // 记录本次同步时间
            db.metaDao().put(
                MetaEntity(
                    AppDatabase.MetaKeys.LAST_SYNC_AT,
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
                )
            )
            // ---- 学生信息（公开信息，非凭据）----
            schedule.student?.let { st ->
                st.studentId?.let {
                    db.metaDao().put(MetaEntity(AppDatabase.MetaKeys.STUDENT_NO, it))
                }
                st.name?.let {
                    db.metaDao().put(MetaEntity(AppDatabase.MetaKeys.STUDENT_NAME, it))
                }
                st.major?.takeIf { it.isNotBlank() }?.let {
                    db.metaDao().put(MetaEntity(AppDatabase.MetaKeys.STUDENT_MAJOR, it))
                }
                st.className?.takeIf { it.isNotBlank() }?.let {
                    db.metaDao().put(MetaEntity(AppDatabase.MetaKeys.STUDENT_CLASS, it))
                }
                // 学年名称与学期名称（ADR-016）：用于标题「AY26-27 Term1」。
                // ⚠️ 学期必须用 XQMMC（=「1」），不能用 XQM（=「3」）——
                //    后者是编码，直接显示会错写成 Term3。
                st.academicYear?.takeIf { it.isNotBlank() }?.let {
                    db.metaDao().put(MetaEntity(AppDatabase.MetaKeys.ACADEMIC_YEAR_NAME, it))
                }
                st.termName?.takeIf { it.isNotBlank() }?.let {
                    db.metaDao().put(MetaEntity(AppDatabase.MetaKeys.TERM_NAME, it))
                }
            }

            // ---- 学期第一周星期一（ADR-011：用于精确算「第 N 周」）----
            // 优先级：
            //   ① 课表响应里带的（若教务确实返回）—— ZfDateRangeParser
            //   ② cxRsd 日程接口解析出的
            //   ③ 兜底：本周一（保证不退化成「永远第 1 周」）
            val firstMonday =
                ZfDateRangeParser.firstMonday(dateRangeRaw)
                    ?: schedule.firstMonday
                    ?: WeekCalculator.mondayOf(LocalDate.now())
            LoginDiagnostics.log("第一周星期一 -> $firstMonday")
            db.metaDao().put(
                MetaEntity(AppDatabase.MetaKeys.FIRST_MONDAY, firstMonday.toString()),
            )

            // ---- 假日区间（ADR-060）----
            // ⚠️ 解析不出时**不覆盖**已有数据 —— 否则一次网络抖动
            //    就把上次同步到的假期抹掉了。只有解析到内容才写。
            val holidays = ZfCalendarParser.parse(calendarRaw)
            LoginDiagnostics.log("假日解析 -> ${holidays.size} 天")
            if (holidays.isNotEmpty()) {
                db.metaDao().put(
                    MetaEntity(
                        AppDatabase.MetaKeys.HOLIDAYS,
                        HolidayCalendar.toJson(holidays),
                    ),
                )
            }

            Result(success = true, courseCount = courses.size)

        } catch (e: Exception) {
            Result(success = false, message = "同步出错：${e.message ?: e.javaClass.simpleName}")
        } finally {
            // ---- ⑥ 清理：登出 + 丢弃凭据 ----
            onProgress(Progress.LoggingOut)
            runCatching { logout(http) }
            cookieJar.clear()
            runCatching { http.dispatcher.executorService.shutdown() }
            runCatching { http.connectionPool.evictAll() }
        }
    }

    /** 主动登出（best-effort，失败不影响结果）。 */
    private fun logout(http: okhttp3.OkHttpClient) {
        val req = okhttp3.Request.Builder()
            .url("${ZhengfangSessionClient.JWXT_BASE}/jwglxt/xtgl/login_logout.html")
            .get()
            .header("User-Agent", ZhengfangSessionClient.UA)
            .build()
        runCatching { http.newCall(req).execute().close() }
    }

    /** 学期编码 -> 中文。`3`=第一学期（抓包确认）。 */
    private fun termPrefix(code: String): String = when (code) {
        "3" -> "1"
        "12" -> "2"
        else -> code
    }

    private fun parseAndSaveGrades(json: String, termLabel: String) {
        // 解析器待有真实数据时实现；这里先保证不崩溃。
        // ⚠️ 学生刚入学无成绩，且用户明确要求「先不写成绩」，故暂不实现。
    }

    /**
     * 解析并保存考试（ADR-013）。
     *
     * ⚠️ 与成绩不同，考试是**当下就有用**的信息（要提前知道何时去哪考），
     * 故本轮补上解析器。字段名依据正方通用命名，响应结构待真实数据验证。
     */
    private suspend fun parseAndSaveExams(json: String, termLabel: String) {
        val exams = ZfExamParser.parse(json, termLabel)
        LoginDiagnostics.log("考试解析 -> ${exams.size} 条")
        if (exams.isNotEmpty()) {
            examRepo.saveExams(exams)
        }
    }
}
