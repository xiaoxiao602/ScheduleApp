package com.gzuschedule.app.data.auth.model

import com.gzuschedule.app.data.auth.RsaPasswordEncoder

/**
 * LYUAP 登录参数 —— 与办事大厅（cas.gzus.edu.cn）对接。
 *
 * 依据：从 cas.gzus.edu.cn 前端 redux action 还原的真实请求
 * ```javascript
 * post(CAS + "/v1/tickets", JSON.stringify({
 *     username: e, password: i ? t : w,
 *     service: a, loginType: i, id: g, code: _
 * }))
 * ```
 * 其中 CAS = "/lyuapServer"
 *
 * ⚠️ 已知未定项（见 ADR-003）：
 *   - 编码方式：axios 全局默认 form，但代码显式 JSON.stringify —— 两者矛盾
 *   - 故本实现两种都提供，由 [ENCODING] 切换，运行时可试
 *   - 验证码端点为 /validateLoginCode 时返回 404，可能该版本不使用独立校验
 */
object LoginFields {

    /** 登录端点（相对 CAS base）。 */
    const val TICKETS_PATH = "/lyuapServer/v1/tickets"

    /** 验证码端点。 */
    const val CAPTCHA_PATH = "/lyuapServer/kaptcha"

    // ---- 请求字段名（已从源码确认）----
    const val USERNAME = "username"
    const val PASSWORD = "password"
    const val SERVICE = "service"
    const val LOGIN_TYPE = "loginType"
    /** 验证码 uid。 */
    const val CAPTCHA_ID = "id"
    /** 验证码答案（用户填的计算结果）。 */
    const val CAPTCHA_CODE = "code"

    /**
     * loginType 取值。
     *
     * ⚠️ 已由真机抓包证实为【空字符串】，不是 "1"。
     * 抓包 Payload 显示: `loginType: (空)`
     *
     * 这也解释了矩阵测试的现象:
     *   loginType="1" -> PASSERROR（走错分支）
     *   loginType=""  -> CODEFALSE（正确路径，因当时验证码是假值）
     */
    const val LOGIN_TYPE_ACCOUNT = ""

    /** 登录成功后要跳转的目标系统。 */
    const val DEFAULT_SERVICE = "https://ehall.gzus.edu.cn"

    /**
     * 请求编码方式。
     *
     * 前端代码存在矛盾（axios 默认 form vs 显式 JSON.stringify），
     * 因此两种都实现，默认 FORM（axios 全局默认更可能是实际生效的）。
     * 若登录失败，切换为 JSON 重试即可。
     */
    enum class Encoding { FORM, JSON }

    var encoding: Encoding = Encoding.FORM

    /**
     * 是否对密码做 RSA 加密。
     *
     * ⚠️ 已由【真机抓包证实】:必须加密。
     * 抓包到的 password 字段为 256 个 hex 字符 = 128 字节 = 1024bit RSA 密文，
     * 与本地 `RSA/ECB/PKCS1Padding + hex` 的输出长度完全一致。
     *
     * 此前误判为明文（把前端 `password: i ? t : w` 的取值方向读反了），
     * 导致服务端返回 PASSERROR。见 ADR-004。
     */
    const val ENCRYPT_PASSWORD = true

    /** RSA 密文的编码方式（抓包证实为小写 hex）。 */
    val RSA_ENCODING = RsaPasswordEncoder.Encoding.HEX
}
