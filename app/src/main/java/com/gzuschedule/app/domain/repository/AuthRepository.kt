package com.gzuschedule.app.domain.repository

import com.gzuschedule.app.domain.model.Captcha
import com.gzuschedule.app.domain.model.Session

/**
 * 认证端口 —— App 的其余部分只依赖这个接口。
 *
 * 协议细节（LYUAP 的字段名、RSA 编码格式、CAS 票据）全部封装在实现里，
 * 不泄漏到 domain 或 ui 层。这样当协议细节被确认为不同形式时，
 * 只需替换实现，调用方零改动。
 */
interface AuthRepository {

    /**
     * 获取一张新的图形验证码。
     * 返回的 [Captcha.uid] 必须在 [login] 时回传。
     */
    suspend fun fetchCaptcha(): Result<Captcha>

    /**
     * 提交登录。
     *
     * @param username 学号
     * @param rawPassword 明文密码 —— 实现内部负责加密，调用方不接触加密逻辑
     * @param captchaCode 用户识别的验证码
     * @param captchaUid [fetchCaptcha] 返回的 uid
     */
    suspend fun login(
        username: String,
        rawPassword: String,
        captchaCode: String,
        captchaUid: String,
    ): Result<Session>
}
