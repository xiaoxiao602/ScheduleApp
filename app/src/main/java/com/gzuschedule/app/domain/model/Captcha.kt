package com.gzuschedule.app.domain.model

/**
 * 图形验证码 —— 领域模型，与具体认证系统无关。
 *
 * 只描述「用户需要识别一张图并回传一个标识」这一业务事实。
 * 具体接口路径、字段名、响应包装属实现细节，见 data/auth/。
 */
data class Captcha(
    /** 服务端下发的标识，登录时必须回传。 */
    val uid: String,
    /** data URL 形式的图片（形如 `data:image/png;base64,...`）。 */
    val dataUrl: String,
) {
    /** 去掉 data URL 前缀，得到纯 base64。 */
    val base64: String
        get() = dataUrl.substringAfter(",", dataUrl)

    /** 是否可用于登录（服务端以 uid="-1" 表示本次无需验证码）。 */
    val isValid: Boolean
        get() = uid.isNotBlank() && uid != "-1" && base64.isNotBlank()
}
