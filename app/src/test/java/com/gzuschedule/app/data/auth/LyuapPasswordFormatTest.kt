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
    private val captured = "5e999a91423092306c40501640d05f5ae8c6fbb063eb857b88865b38475ea1e0" +
        "72dc636cb4c73bde33edb7ee1d98671cf6363e4b56354ebcff9e602a78cab88" +
        "c4b35a7a9a4f239e7ad84054dd788305f7fd52319873faf4b0707826a6b7fed2" +
        "0f9b773a3abcd6187f0ec2c4c62261e1708ca3830631c7617300f15a71b0a1e1b"

    private val password = "@Pp20050128"

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
        assertEquals("82105002pP@", password.reversed())
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
