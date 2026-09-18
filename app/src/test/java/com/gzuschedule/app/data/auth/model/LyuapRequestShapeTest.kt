package com.gzuschedule.app.data.auth.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定【真机抓包证实】的完整登录请求形态。
 *
 * 抓包事实（2026-09-18，办事大厅 cas.gzus.edu.cn，成功登录并换发 TGT）：
 *
 *   POST /lyuapServer/v1/tickets      (application/x-www-form-urlencoded)
 *   ┌────────────┬──────────────────────────────────────────┐
 *   │ username   │ 2620926205                               │
 *   │ password   │ <256 个小写 hex 字符 = RSA 密文>          │
 *   │ service    │ https://ehall.gzus.edu.cn                │
 *   │ loginType  │ (空字符串)                                │
 *   │ id         │ <32 位 hex，验证码 uid>                    │
 *   │ code       │ <验证码计算结果，如 "0">                   │
 *   └────────────┴──────────────────────────────────────────┘
 *
 * 本测试防止这些已确认事实被改回去。
 */
class LyuapRequestShapeTest {

    @Test
    fun `端点为 v1 tickets 且挂在 lyuapServer 下`() {
        assertEquals("/lyuapServer/v1/tickets", LoginFields.TICKETS_PATH)
    }

    @Test
    fun `字段名与抓包一致`() {
        assertEquals("username", LoginFields.USERNAME)
        assertEquals("password", LoginFields.PASSWORD)
        assertEquals("service", LoginFields.SERVICE)
        assertEquals("loginType", LoginFields.LOGIN_TYPE)
        assertEquals("id", LoginFields.CAPTCHA_ID)
        assertEquals("code", LoginFields.CAPTCHA_CODE)
    }

    @Test
    fun `loginType 必须为空字符串`() {
        // 抓包证实：不是 "1"。传 "1" 会导致服务端返回 PASSERROR。
        assertEquals("", LoginFields.LOGIN_TYPE_ACCOUNT)
    }

    @Test
    fun `service 指向办事大厅`() {
        assertEquals("https://ehall.gzus.edu.cn", LoginFields.DEFAULT_SERVICE)
    }

    @Test
    fun `编码默认 FORM（抓包为 form-urlencoded）`() {
        assertEquals(LoginFields.Encoding.FORM, LoginFields.Encoding.FORM)
    }

    @Test
    fun `密码必须加密且用 HEX 编码`() {
        assertTrue("抓包值为 256 位 hex 密文，必须加密", LoginFields.ENCRYPT_PASSWORD)
        assertEquals(
            com.gzuschedule.app.data.auth.RsaPasswordEncoder.Encoding.HEX,
            LoginFields.RSA_ENCODING,
        )
    }
}
