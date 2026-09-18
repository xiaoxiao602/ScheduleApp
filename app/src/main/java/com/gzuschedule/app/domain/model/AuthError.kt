package com.gzuschedule.app.domain.model

/** 登录过程中可能出现的领域级错误。 */
sealed class AuthError(message: String) : Exception(message) {

    /** 账号不存在（服务端返回 NOUSER）。 */
    data object UserNotFound : AuthError("账号不存在，请检查学号")

    /** 验证码错误或已过期（服务端返回 CODEFALSE）。 */
    data object CaptchaError : AuthError("验证码错误或已过期，请重试")

    /** 密码错误。 */
    data object BadCredentials : AuthError("密码错误")

    /** 网络不可达 —— 常见于校外且未连校园网/VPN。 */
    data object NetworkUnreachable : AuthError("无法连接教务系统，请检查网络或是否需连接校园网")

    /** 会话过期，需重新登录。 */
    data object SessionExpired : AuthError("登录已过期，请重新登录")

    /** 服务端返回了未预期的内容（可能系统已升级）。 */
    data class Unexpected(val detail: String) : AuthError("教务系统返回异常：$detail")
}
