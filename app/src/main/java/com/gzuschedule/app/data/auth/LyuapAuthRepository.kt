package com.gzuschedule.app.data.auth

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.gzuschedule.app.data.auth.model.LoginFields
import com.gzuschedule.app.domain.model.AuthError
import com.gzuschedule.app.domain.model.Captcha
import com.gzuschedule.app.domain.model.Session
import com.gzuschedule.app.domain.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * LYUAP（办事大厅统一身份认证）适配器。
 *
 * 协议来源：cas.gzus.edu.cn 前端 redux action，已还原为：
 *   POST {CAS}/v1/tickets
 *   body: username, password, service, loginType, id(验证码uid), code(验证码答案)
 *
 * 已知未定项见 [LoginFields] 与 ADR-003：
 *   - 编码方式（form / json）存在矛盾 -> 两种都实现，运行时可切
 *   - 密码是否需 RSA 加密 -> 依源码 loginType 有值时传明文
 */
class LyuapAuthRepository(
    private val baseUrl: String = DEFAULT_BASE,
    private val client: OkHttpClient = defaultClient(),
) : AuthRepository {

    companion object {
        const val DEFAULT_BASE = "https://cas.gzus.edu.cn"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val gson = Gson()

    // ---------- 响应模型 ----------

    private data class Meta(val success: Boolean?, val statusCode: Int?, val message: String?)
    private data class LoginData(val code: String?, val message: String?)
    private data class LoginResponse(
        val meta: Meta?, val data: LoginData?, val tgt: String?,
    )
    private data class KaptchaResponse(
        val kaptchaType: String?, val uid: String?, val content: String?,
    )

    // ---------- 验证码 ----------

    override suspend fun fetchCaptcha(): Result<Captcha> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl${LoginFields.CAPTCHA_PATH}")
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Referer", "$baseUrl/lyuapServer/login")
                .get().build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string()
                    ?: throw AuthError.Unexpected("验证码响应为空")
                val parsed = gson.fromJson(body, KaptchaResponse::class.java)
                val captcha = Captcha(
                    uid = parsed.uid.orEmpty(),
                    dataUrl = parsed.content.orEmpty(),
                )
                if (!captcha.isValid) {
                    throw AuthError.Unexpected("验证码数据无效 (uid=${parsed.uid})")
                }
                captcha
            }
        }.recoverCatching { throw mapError(it) }
    }

    // ---------- 登录 ----------

    override suspend fun login(
        username: String,
        rawPassword: String,
        captchaCode: String,
        captchaUid: String,
    ): Result<Session> = withContext(Dispatchers.IO) {
        runCatching {
            // ⚠️ 已由抓包证实：必须先 RSA 加密再提交（256 字符 hex 密文）
            val passwordToSend = if (LoginFields.ENCRYPT_PASSWORD) {
                RsaPasswordEncoder.encoding = LoginFields.RSA_ENCODING
                val enc = RsaPasswordEncoder.encode(rawPassword)
                // 诊断：只记录密文长度（明文绝不出现在日志中）
                LoginDiagnostics.log("密码 RSA/${LoginFields.RSA_ENCODING} -> ${enc.length} 字符")
                enc
            } else {
                LoginDiagnostics.log("密码 明文 -> ${rawPassword.length} 字符")
                rawPassword
            }

            val fields = linkedMapOf(
                LoginFields.USERNAME to username,
                LoginFields.PASSWORD to passwordToSend,
                LoginFields.SERVICE to LoginFields.DEFAULT_SERVICE,
                LoginFields.LOGIN_TYPE to LoginFields.LOGIN_TYPE_ACCOUNT,
                LoginFields.CAPTCHA_ID to captchaUid,
                LoginFields.CAPTCHA_CODE to captchaCode.trim(),
            )

            val requestBody = when (LoginFields.encoding) {
                LoginFields.Encoding.FORM -> {
                    val fb = FormBody.Builder()
                    fields.forEach { (k, v) -> fb.add(k, v) }
                    fb.build()
                }
                LoginFields.Encoding.JSON -> {
                    gson.toJson(fields)
                        .toRequestBody("application/json; charset=utf-8".toMediaType())
                }
            }

            val url = "$baseUrl${LoginFields.TICKETS_PATH}"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("Origin", baseUrl)
                .header("Referer", "$baseUrl/lyuapServer/login")
                .post(requestBody)
                .build()

            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val parsed = runCatching {
                    gson.fromJson(body, LoginResponse::class.java)
                }.getOrNull()

                val code = parsed?.data?.code.orEmpty()

                // 诊断：把服务端原始反馈记下来，供界面展示
                LoginDiagnostics.log("POST $url")
                LoginDiagnostics.log("编码=${LoginFields.encoding} HTTP=${resp.code}")
                LoginDiagnostics.log("响应=${body.take(300)}")

                when {
                    // 成功：HTTP 200/201 且无业务错误码（或带 tgt）
                    code.isEmpty() && resp.isSuccessful -> {
                        val tgt = parsed?.tgt
                        val cookies = extractCookies(resp.headers("Set-Cookie"))
                        if (cookies.isEmpty() && tgt.isNullOrBlank()) {
                            throw AuthError.Unexpected(
                                "服务端未返回会话凭据（HTTP ${resp.code}）"
                            )
                        }
                        LoginDiagnostics.log("登录成功，Cookie=${cookies.keys}")
                        Session(cookies = cookies, username = username)
                    }
                    code.equals("NOUSER", true) -> throw AuthError.UserNotFound
                    code.equals("CODEFALSE", true) -> throw AuthError.CaptchaError
                    code.equals("PASSERROR", true) ||
                        code.equals("PASSWORDERROR", true) -> throw AuthError.BadCredentials
                    code.isEmpty() -> throw AuthError.Unexpected(
                        parsed?.meta?.message
                            ?: "登录失败 HTTP ${resp.code}: ${body.take(120)}"
                    )
                    else -> throw AuthError.Unexpected("未识别的错误码: $code")
                }
            }
        }.recoverCatching { throw mapError(it) }
    }

    private fun extractCookies(setCookies: List<String>): Map<String, String> =
        setCookies.mapNotNull { raw ->
            val pair = raw.substringBefore(";")
            val name = pair.substringBefore("=").trim()
            val value = pair.substringAfter("=", "").trim()
            if (name.isNotEmpty()) name to value else null
        }.toMap()

    private fun mapError(t: Throwable): Throwable = when (t) {
        is AuthError -> t
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.SocketTimeoutException -> AuthError.NetworkUnreachable
        else -> AuthError.Unexpected(t.message ?: t::class.java.simpleName)
    }
}
