package com.gzuschedule.app.data.sync.zhengfang

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import android.util.Log
import com.gzuschedule.app.data.auth.LoginDiagnostics
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 正方会话建立器（ADR-008）。
 *
 * 完整流程：
 *   ① CAS 登录 -> 拿 TGT（含 Set-Cookie: CASTGC）
 *   ② 用 TGT 换 ST（service=jwxt）
 *   ③ 带 ST 访问 jwxt，跟随 302 链建立正方会话
 *   ④ 会话就绪，可调数据接口
 *
 * ⚠️ 三个必须遵守的约束：
 *  1. **保持 Cookie**：OkHttp 的 CookieJar 由调用方提供，必须跨请求累积
 *  2. **自动跟随 302**：`verify` 参数由服务端生成，不能自己算
 *  3. **只在内存中**：会话与凭据都不落盘（ADR-005）
 */
class ZhengfangSessionClient(
    private val client: OkHttpClient,
    private val gson: Gson = Gson(),
) {

    companion object {
        private const val TAG = "ZfSession"

        const val CAS_BASE = "https://cas.gzus.edu.cn"
        const val TICKETS_PATH = "/lyuapServer/v1/tickets"
        const val EHALL_SERVICE = "https://ehall.gzus.edu.cn"
        const val JWXT_SERVICE = "https://jwxt.gzus.edu.cn/sso/lyiotlogin"
        const val JWXT_BASE = "https://jwxt.gzus.edu.cn"
        const val JWXT_HOME = "/jwglxt/xtgl/index_initMenu.html"

        /** 常见浏览器 UA —— 与真实用户一致，避免被风控误判 */
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36"

        fun defaultClient(cookieJar: okhttp3.CookieJar): OkHttpClient =
            OkHttpClient.Builder()
                .cookieJar(cookieJar)
                .followRedirects(true)      // ⚠️ 必须跟随 302
                .followSslRedirects(true)   // ⚠️ 允许 https->http 降级跳转
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
    }

    /** 会话建立结果。 */
    sealed interface Result {
        data object Success : Result
        data class Failure(val reason: String, val serverCode: String? = null) : Result
    }

    /**
     * 完成整个会话建立流程。
     *
     * @param username 学号
     * @param encryptedPassword 已加密的密码（倒序+无填充RSA+hex）
     * @param captchaId 验证码 uid
     * @param captchaCode 验证码答案（用户填的算术结果）
     */
    fun establish(
        username: String,
        encryptedPassword: String,
        captchaId: String?,
        captchaCode: String?,
    ): Result {
        // ---- ① CAS 登录，拿 TGT（服务端可能同时返回 ST）----
        val ticketJson = postTickets(username, encryptedPassword, captchaId, captchaCode)
            ?: return Result.Failure("登录请求无响应")

        val parsed = parseLoginResponse(ticketJson)
        val tgt: String

        when (parsed) {
            is LoginParse.Failed -> return Result.Failure(humanize(parsed.code), parsed.code)
            is LoginParse.Ok -> {
                tgt = parsed.tgt
                // 注意：parsed.st 是【ehall 专属】的 ST，不能用于 jwxt，
                // 因此此处刻意不使用它（见下方 ② 的说明）。
            }
        }

        // ---- ② 取 ST：必须用 TGT 换成【jwxt 专属】的 ST ----
        //
        // ⚠️ 关键教训（真机实测 404 定位）：
        //   登录响应里的 ticket 是【ehall 专属】的 ST —— 因为登录时
        //   传的 service=https://ehall.gzus.edu.cn。
        //   拿它去访问 jwxt 会被服务端拒绝（HTTP 404）。
        //
        //   必须先 POST /v1/tickets/{TGT} 且 service 指向 jwxt，
        //   换回一个属于 jwxt 的 ST，再用它跳转。
        val st = requestServiceTicket(tgt)
            ?: return Result.Failure(
                "换取正方服务票据失败（TGT 可能已过期）",
                "ST_FAILED",
            )

        // ---- ③ 带 ST 访问 jwxt，跟随 302 建立会话 ----
        if (!bootstrapJwxtSession(st)) {
            return Result.Failure("正方会话建立失败（票据可能已失效或 IP 不匹配）", "BOOTSTRAP_FAILED")
        }

        return Result.Success
    }

    /** ① POST /lyuapServer/v1/tickets */
    private fun postTickets(
        username: String,
        encryptedPassword: String,
        captchaId: String?,
        captchaCode: String?,
    ): String? {
        val form = FormBody.Builder()
            .add("username", username)
            .add("password", encryptedPassword)
            .add("service", EHALL_SERVICE)
            .add("loginType", "")                    // ⚠️ 必须为空字符串
            .apply {
                captchaId?.let { add("id", it) }
                captchaCode?.let { add("code", it) }
            }
            .build()

        val req = Request.Builder()
            .url(CAS_BASE + TICKETS_PATH)
            .post(form)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", CAS_BASE)
            .header("Referer", "$CAS_BASE/lyuapServer/login?service=$EHALL_SERVICE")
            .build()

        return runCatching {
            client.newCall(req).execute().use { it.body?.string().orEmpty() }
        }.getOrNull()
    }

    /**
     * 解析登录响应，取出 TGT。
     *
     * ⚠️ 真机实测格式：`{"tgt":"TGT-...","ticket":"ST-..."}`
     * 兼容 `{data:...}` 与把 TGT 直接作为字符串返回的情况。
     *
     * 同时把响应中**已附带的 ST** 缓存起来（服务端一次给了两个），
     * 避免多余的换票请求。
     */
    private fun parseLoginResponse(json: String): LoginParse {
        val obj = runCatching { gson.fromJson(json, TicketResponse::class.java) }.getOrNull()

        // 失败码检测：data 可能是对象 {"code":"PASSERROR"} 或纯字符串
        val failCode = extractFailureCode(obj?.data, obj?.code ?: obj?.meta?.code)
        if (failCode != null) return LoginParse.Failed(failCode)

        // TGT：优先顶层 tgt 字段，其次从任意文本里抓
        val tgt = obj?.tgt?.takeIf { it.contains("TGT-") }
            ?: Regex("""TGT-[\dA-Za-z-]+""").find(json)?.value
            ?: return LoginParse.Failed("NO_TGT")

        // ST：服务端有时一并返回
        val st = obj?.ticket?.takeIf { it.contains("ST-") }
            ?: Regex("""ST-[\dA-Za-z-]+""").find(json)?.value

        return LoginParse.Ok(tgt, st)
    }

    private sealed interface LoginParse {
        data class Ok(val tgt: String, val st: String?) : LoginParse
        data class Failed(val code: String) : LoginParse
    }

    /** ② POST /lyuapServer/v1/tickets/{TGT} -> jwxt 专属 ST */
    private fun requestServiceTicket(tgt: String): String? {
        val form = FormBody.Builder()
            .add("loginToken", "loginToken")
            .add("service", JWXT_SERVICE)
            .build()

        val req = Request.Builder()
            .url("$CAS_BASE$TICKETS_PATH/$tgt")
            .post(form)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", CAS_BASE)
            .header("Referer", "$CAS_BASE/lyuapServer/login?service=$JWXT_SERVICE")
            .build()

        val body = runCatching {
            client.newCall(req).execute().use { it.body?.string().orEmpty() }
        }.getOrNull() ?: run {
            LoginDiagnostics.log("换票请求无响应")
            return null
        }

        LoginDiagnostics.log("换票响应: ${body.take(200)}")

        // 换票响应同样是 {tgt, ticket} 形态（或 data 里带票据），
        // 统一走同一套解析：先看顶层 ticket，再从整段文本里抓 ST-。
        val parsed = runCatching { gson.fromJson(body, TicketResponse::class.java) }.getOrNull()
        val st = parsed?.ticket?.takeIf { it.contains("ST-") }
            ?: Regex("""ST-[\dA-Za-z-]+""").find(body)?.value

        if (st != null) {
            LoginDiagnostics.log("取得 jwxt ST: ${st.take(40)}...")
        } else {
            LoginDiagnostics.log("未从换票响应中取得 ST")
        }
        return st
    }

    /**
     * ③ 带 ST 访问正方，跟随 302 链，建立会话。
     *
     * ⚠️ 抓包证实的完整序列（两步，缺一不可）：
     *
     *   ① GET /sso/lyiotlogin                （【不带】 ticket，预热）
     *      -> 302 https://cas.gzus.edu.cn/lyuapServer/login?service=...jwxt.../sso/lyiotlogin
     *      —— 这一步让服务端建立 CAS 上下文；
     *         真机实测：跳过它直接带 ticket 访问会返回 **404**。
     *
     *   ② GET /sso/lyiotlogin?ticket=ST-..   （带 ticket）
     *      -> 302 /jwglxt/ticketlogin?uid=..&timestamp=..&verify=..
     *      -> 302 /jwglxt/xtgl/login_slogin.html
     *      -> 302 /jwglxt/xtgl/index_initMenu.html?jsdm=xs
     *
     * 成功判据放宽为「拿到了正方会话 Cookie」——不同入口落地页可能不同，
     * 用 URL 精确匹配容易误判。
     */
    private fun bootstrapJwxtSession(st: String): Boolean {
        // ---- ① 预热：不带 ticket 访问，跟随到 CAS 建立上下文 ----
        val warmupUrl = "$JWXT_BASE/sso/lyiotlogin"
        runCatching {
            val warmReq = Request.Builder()
                .url(warmupUrl)
                .get()
                .header("User-Agent", UA)
                .header(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                )
                .build()
            client.newCall(warmReq).execute().use { r ->
                Log.d(TAG, "warmup: HTTP=${r.code} final=${r.request.url}")
                LoginDiagnostics.log("预热跳转 -> HTTP ${r.code}")
            }
        }.onFailure { e ->
            Log.w(TAG, "warmup 失败（忽略，继续）", e)
            LoginDiagnostics.log("预热失败（忽略）: ${e.message}")
        }

        // ---- ② 带 ticket 访问，跟随 302 链 ----
        val url = "$JWXT_BASE/sso/lyiotlogin?ticket=$st"
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", UA)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            .header("Referer", "$CAS_BASE/lyuapServer/login?service=$JWXT_SERVICE")
            .build()

        return runCatching {
            client.newCall(req).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val code = resp.code
                val body = resp.body?.string().orEmpty()

                Log.d(TAG, "bootstrap: HTTP=$code")
                Log.d(TAG, "bootstrap: finalUrl=$finalUrl")
                LoginDiagnostics.log("会话跳转 -> HTTP $code")
                LoginDiagnostics.log("落地页: ${finalUrl.take(160)}")

                val landedInJwglxt = finalUrl.contains("/jwglxt/")
                val isLoginPage = finalUrl.contains("login_slogin") ||
                    body.contains("统一身份认证") ||
                    (body.contains("id=\"yhm\"") && body.contains("id=\"mm\""))

                val hasSession = cookieHasJsessionId()
                Log.d(TAG, "bootstrap: landed=$landedInJwglxt login=$isLoginPage session=$hasSession")
                LoginDiagnostics.log(
                    "落在 jwglxt=$landedInJwglxt 登录页=$isLoginPage 有会话=$hasSession",
                )

                // 只要进了 jwglxt 且不在登录页，或已持有会话 Cookie，即视为成功
                (landedInJwglxt && !isLoginPage) || hasSession
            }
        }.getOrElse { e ->
            Log.e(TAG, "bootstrap 异常", e)
            LoginDiagnostics.log("会话跳转异常: ${e.message}")
            false
        }
    }

    /**
     * 检查 CookieJar 里是否已有正方的 JSESSIONID。
     *
     * 这是比 URL 匹配更可靠的成功判据 —— 会话建立了，Cookie 一定在。
     * 需由调用方通过 [sessionCookieProbe] 注入（避免本类耦合具体 CookieJar）。
     */
    var sessionCookieProbe: (() -> Boolean)? = null

    private fun cookieHasJsessionId(): Boolean =
        runCatching { sessionCookieProbe?.invoke() == true }.getOrDefault(false)

    // ---------- 解析辅助 ----------

    /**
     * 从响应里找失败码。
     *
     * ⚠️ `data` 的类型不固定：
     *  - 对象：`{"code":"PASSERROR","data":"PASSERROR"}`
     *  - 字符串：`"PASSERROR"`
     * 故这里把 JsonElement 序列化成文本再匹配，两种都能覆盖。
     */
    private fun extractFailureCode(data: JsonElement?, metaCode: String?): String? {
        val candidates = buildList {
            if (data != null && !data.isJsonNull) add(data.toString())
            metaCode?.let { add(it) }
        }
        val known = listOf(
            "PASSERROR", "NOUSER", "CODEFALSE", "USERLOCKED",
            "LOCKED", "FAIL", "ERROR", "NO_TGT",
        )
        for (c in candidates) {
            for (k in known) {
                if (c.contains(k, ignoreCase = true)) return k
            }
        }
        return null
    }

    /** 把服务端错误码翻译成用户能看懂的话。 */
    private fun humanize(code: String): String = when (code.uppercase()) {
        "PASSERROR" -> "密码错误"
        "NOUSER" -> "账号不存在"
        "CODEFALSE" -> "验证码错误"
        "USERLOCKED", "LOCKED" -> "账号已被锁定，请稍后再试或联系教务处"
        else -> "登录失败（$code）"
    }

    // ---------- 响应模型 ----------

    /**
     * CAS 登录响应。
     *
     * ⚠️ 真实格式（真机实测确认，2026-09-18）：
     * ```json
     * { "tgt": "TGT-032626-...", "ticket": "ST-032626-..." }
     * ```
     * 注意：服务端**一次同时返回 TGT 与 ST**（ticket 字段就是 ST）。
     *
     * 之前误以为是 `{meta:{...}, data:"..."}`，导致解析失败。
     * 为兼容不同版本，两种结构都保留。
     */
    internal data class TicketResponse(
        // —— 实测格式 ——
        @SerializedName("tgt") val tgt: String? = null,
        @SerializedName("ticket") val ticket: String? = null,
        // —— 兼容旧假设 ——
        @SerializedName("meta") val meta: Meta? = null,
        /**
         * ⚠️ `data` 字段的类型【因成功/失败而不同】：
         *  - 成功：无此字段（票据在顶层 tgt/ticket）
         *  - 失败：是【对象】`{"code":"PASSERROR","data":"PASSERROR"}`
         *
         * 因此这里用 JsonElement 承接，避免 Gson 类型不匹配抛异常 ——
         * 否则登录失败时会变成「无响应」，把真实原因（密码错/验证码错）吞掉。
         */
        @SerializedName("data") val data: JsonElement? = null,
        @SerializedName("code") val code: String? = null,
        @SerializedName("message") val message: String? = null,
    ) {
        data class Meta(
            @SerializedName("success") val success: Boolean? = null,
            @SerializedName("code") val code: String? = null,
            @SerializedName("message") val message: String? = null,
            @SerializedName("statusCode") val statusCode: Int? = null,
        )

        data class TicketData(
            @SerializedName("ticket") val ticket: String? = null,
            @SerializedName("tgt") val tgt: String? = null,
        )
    }
}
