package com.gzuschedule.app.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 决定性测试：Kotlin 实现必须能【精确复现】抓包得到的密文。
 *
 * 抓包值由真机浏览器获得，并经 app.js 泄露的私钥解密验证为
 * 「密码倒序 + 无填充 RSA」。若本测试通过，则 App 的加密与前端等价。
 */
class LyuapPasswordFormatTest {

    /** 两次抓包完全相同（证明是确定性加密 = 无填充）。 */
        private val captured = 
        "475c33b04048213a7eb378f4d1da9c67ca3fe0af2783c8aa61ac650062eb" +
        "fa823535f8cd439f77f50d0d6c5e2edaf7793ffed7f68f25f506104a6edb" +
        "05b4b751bc75ed8dc614ff6bc4c4bac63de51f88615c3fc4ecc9d0e8d188" +
        "985e158d6efb80ce514259dfd0ab3f0320deabd274ab1e179e8f878c6bda" +
        "2c921f67fc3464e3"

    private val password = "Example@Pass123"

    @Test
    fun `精确复现抓包密文`() {
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.HEX
        RsaPasswordEncoder.reversePlaintext = true
        val out = RsaPasswordEncoder.encode(password)
        assertEquals("必须与抓包值逐字节一致", captured, out)
    }

    @Test
    fun `输出为 256 个小写 hex 字符`() {
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.HEX
        val out = RsaPasswordEncoder.encode(password)
        assertEquals(256, out.length)
        assertTrue(out.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `确定性加密 - 同一输入两次结果相同`() {
        // 无填充 RSA 是确定性的；这与其相反（PKCS1 随机）是本次修复的核心
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.HEX
        val a = RsaPasswordEncoder.encode(password)
        val b = RsaPasswordEncoder.encode(password)
        assertEquals("无填充 RSA 应产生相同密文", a, b)
    }

    @Test
    fun `不倒序时结果不同 - 证明倒序是必需的`() {
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.HEX

        RsaPasswordEncoder.reversePlaintext = true
        val reversed = RsaPasswordEncoder.encode(password)

        RsaPasswordEncoder.reversePlaintext = false
        val plain = RsaPasswordEncoder.encode(password)

        assertNotEquals("倒序与不倒序必须产生不同密文", reversed, plain)
        assertEquals("倒序版本才与抓包一致", captured, reversed)
    }

    @Test
    fun `倒序后的明文形态正确`() {
        assertEquals("321ssaP@elpmaxE", password.reversed())
    }

    @Test
    fun `BASE64 编码输出解码后为 128 字节`() {
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.BASE64
        RsaPasswordEncoder.reversePlaintext = true
        val out = RsaPasswordEncoder.encode(password)
        assertEquals(128, java.util.Base64.getDecoder().decode(out).size)
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.HEX
    }
}
