package com.gzuschedule.app.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

/**
 * RSA 加密器基础校验（无填充算法）。
 *
 * 算法已由抓包 + 私钥解密证明：
 *   c = (reverse(password) as bigint)^e mod n
 */
class RsaPasswordEncoderTest {

    @Test
    fun `公钥模数为 1024 bit`() {
        assertEquals(1024, RsaPasswordEncoder.keyBits())
    }

    @Test
    fun `密钥字节数为 128`() {
        assertEquals(128, RsaPasswordEncoder.KEY_BYTES)
    }

    @Test
    fun `HEX 输出为 256 字符`() {
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.HEX
        val out = RsaPasswordEncoder.encode("test_password_123")
        assertEquals(256, out.length)
        assertTrue(out.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `BASE64 输出解码后为 128 字节`() {
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.BASE64
        val out = RsaPasswordEncoder.encode("test_password_123")
        assertEquals(128, java.util.Base64.getDecoder().decode(out).size)
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.HEX
    }

    @Test
    fun `无填充加密是确定性的`() {
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.HEX
        val a = RsaPasswordEncoder.encode("deterministic_check")
        val b = RsaPasswordEncoder.encode("deterministic_check")
        assertEquals("无填充 RSA 同一明文必得同一密文", a, b)
    }

    @Test
    fun `可加密接近密钥上限的输入`() {
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.HEX
        val out = RsaPasswordEncoder.encode("a".repeat(128))
        assertEquals(256, out.length)
    }

    @Test
    fun `超长输入应抛出异常`() {
        RsaPasswordEncoder.encoding = RsaPasswordEncoder.Encoding.HEX
        val threw = try {
            RsaPasswordEncoder.encode("a".repeat(129))
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("超过 128 字节应拒绝而非静默截断", threw)
    }

    @Test
    fun `公钥模数位长为 1024`() {
        val modulus = BigInteger(
            "00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5" +
            "fd22c2e7ac519cab59198ece036dcf289ea8201e2a0b9ded307f8fb704136eae" +
            "b670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5120cc95b64316" +
            "04bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1", 16
        )
        assertEquals(1024, modulus.bitLength())
    }

    @Test
    fun `公钥指数为 65537`() {
        assertEquals(65537, BigInteger("010001", 16).toInt())
    }
}
