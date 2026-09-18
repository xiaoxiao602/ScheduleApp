package com.gzuschedule.app.data.auth

import java.math.BigInteger

/**
 * LYUAP 密码加密器 —— 【已由抓包 + 私钥解密数学证明】。
 *
 * 算法（经逐字节验证，密文与抓包值完全一致）：
 * ```
 *   1. 密码倒序      "@Pp20050128" -> "82105002pP@"
 *   2. 字节转大整数
 *   3. 无填充 RSA    c = m^e mod n
 *   4. 密文转小写 hex（256 字符 = 128 字节）
 * ```
 *
 * 证明过程：
 *   - 用 app.js 泄露的 private_exponent 解密抓包密文，得到 "82105002pP@"
 *     = 原密码倒序，且【无任何填充头】(PKCS1 应以 0002 开头)
 *   - 本地以「倒序 + 无填充」加密，输出的 hex 与两次抓包值逐字节相同
 *
 * ⚠️ 关键点（曾因误判导致 PASSERROR 多次）：
 *   - 填充方式: **NoPadding**，不是 PKCS1Padding
 *   - 明文处理: **必须先倒序**
 *   - 这两点无法从压缩 JS 直接读出，必须靠抓包+解密才能确定
 */
object RsaPasswordEncoder {

    /** LYUAP 硬编码公钥（app.js module 18）。 */
    private const val MODULUS_HEX =
        "00b5eeb166e069920e80bebd1fea4829d3d1f3216f2aabe79b6c47a3c18dcee5" +
        "fd22c2e7ac519cab59198ece036dcf289ea8201e2a0b9ded307f8fb704136eae" +
        "b670286f5ad44e691005ba9ea5af04ada5367cd724b5a26fdb5120cc95b64316" +
        "04bd219c6b7d83a6f8f24b43918ea988a76f93c333aa5a20991493d4eb1117e7b1"

    private const val EXPONENT_HEX = "010001"

    private val modulus: BigInteger by lazy { BigInteger(MODULUS_HEX, 16) }
    private val exponent: BigInteger by lazy { BigInteger(EXPONENT_HEX, 16) }

    /** 密钥字节长度（1024 bit -> 128 字节）。 */
    val KEY_BYTES: Int get() = (modulus.bitLength() + 7) / 8

    /** 密文编码方式。抓包证实为小写 hex。 */
    enum class Encoding { HEX, BASE64 }

    var encoding: Encoding = Encoding.HEX

    /** 是否对明文做倒序。抓包证实【必须倒序】。 */
    var reversePlaintext: Boolean = true

    /**
     * 按 LYUAP 算法加密密码。
     *
     * @throws IllegalArgumentException 明文过长（> 密钥字节数）
     */
    fun encode(password: String): String {
        val raw = if (reversePlaintext) password.reversed() else password
        val bytes = raw.toByteArray(Charsets.UTF_8)

        require(bytes.size <= KEY_BYTES) {
            "密码过长: ${bytes.size} 字节 > 密钥 ${KEY_BYTES} 字节"
        }

        // 无填充 RSA: c = m^e mod n
        val m = BigInteger(1, bytes)
        val c = m.modPow(exponent, modulus)

        // 定长输出到 KEY_BYTES
        val out = c.toByteArray().let { signed ->
            // BigInteger.toByteArray 可能带前导 0x00 或不足长度
            val stripped = if (signed.size > 1 && signed[0] == 0.toByte()) {
                signed.copyOfRange(1, signed.size)
            } else signed
            if (stripped.size < KEY_BYTES) {
                ByteArray(KEY_BYTES - stripped.size) + stripped
            } else stripped
        }

        return when (encoding) {
            Encoding.HEX -> out.joinToString("") { "%02x".format(it) }
            Encoding.BASE64 -> java.util.Base64.getEncoder().encodeToString(out)
        }
    }

    /** 供测试与排查：暴露模数位长。 */
    fun keyBits(): Int = modulus.bitLength()
}
