package com.gzuschedule.app.data.auth

import com.gzuschedule.app.data.auth.model.LoginFields

/**
 * 登录诊断 —— 在真机上把「服务端到底返回了什么」显示出来。
 *
 * 设计意图：登录协议还有未定项（编码方式、字段名）。
 * 用户装到手机上试的时候，如果只显示「登录失败」，无法定位原因。
 * 因此把原始响应暴露在界面上，用户截图即可反馈。
 *
 * ⚠️ 安全：绝不记录密码。只记录响应码、响应体（已截断）、以及实际发送的字段名。
 */
object LoginDiagnostics {

    private const val MAX_LOG = 40
    private val logs = ArrayDeque<String>()

    fun log(message: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        logs.addLast("[$ts] $message")
        while (logs.size > MAX_LOG) logs.removeFirst()
    }

    fun snapshot(): String = logs.joinToString("\n")

    fun clear() = logs.clear()

    /** 当前生效的协议配置（便于确认用的是哪套假设）。
     *
     *  注意：这里打印的是【字段名常量】（如字面量 "password"），
     *  用于确认发给服务端的字段拼写是否正确；**不含任何凭据值**。
     */
    fun currentConfig(): String = buildString {
        appendLine("端点: POST ${LoginFields.TICKETS_PATH}")
        appendLine("编码: ${LoginFields.encoding}")
        appendLine("字段: ${LoginFields.USERNAME}/${LoginFields.PASSWORD}/" +
                   "${LoginFields.SERVICE}/${LoginFields.LOGIN_TYPE}/" +
                   "${LoginFields.CAPTCHA_ID}/${LoginFields.CAPTCHA_CODE}")
        append("密码加密: ${if (LoginFields.ENCRYPT_PASSWORD) "RSA" else "明文"}")
    }
}
