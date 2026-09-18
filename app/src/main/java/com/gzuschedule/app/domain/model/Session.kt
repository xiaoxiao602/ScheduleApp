package com.gzuschedule.app.domain.model

/**
 * 教务会话 —— 登录成功后的凭据载体。
 *
 * 设计意图：后续所有数据获取模块只依赖这个 Cookie 字符串，
 * 不关心它是 CAS 票据还是 JSESSIONID，也不关心认证协议细节。
 */
data class Session(
    val cookies: Map<String, String>,
    val username: String,
) {
    /** 序列化为 Cookie 请求头。 */
    fun cookieHeader(): String =
        cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

    val isBlank: Boolean get() = cookies.isEmpty()
}
