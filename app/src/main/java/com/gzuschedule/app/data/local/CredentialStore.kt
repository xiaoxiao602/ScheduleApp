package com.gzuschedule.app.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 凭据存储（ADR-011）。
 *
 * ⚠️ 取代 ADR-005 的「纯内存、用完即弃」：
 * 用户明确要求「记住账密」，以便下次同步免手输。
 *
 * 安全措施（不存明文）：
 *  1. 密钥由 **Android Keystore** 生成并保管（硬件级，若设备支持）
 *  2. 用该密钥做 AES256-GCM 加密，密文落在 EncryptedSharedPreferences
 *  3. 密钥本身不出 Keystore，App 也读不到原始密钥
 *
 * 局限（如实记录）：
 *  - 设备已 root 且攻击者拿到解锁状态时，理论上仍可解密
 *  - 这是「便利 vs 安全」的权衡，用户已知悉并选择便利
 *
 * 密码默认保存；用户可在设置页关闭（清除）。
 */
class CredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /** 学号（非敏感，但仍一并加密存放，避免双份存储）。 */
    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(v) = prefs.edit().putString(KEY_USERNAME, v).apply()

    /**
     * 密码。
     * ⚠️ 仅用于自动填充登录表单与自动同步，绝不上传、绝不写日志。
     */
    var password: String?
        get() = prefs.getString(KEY_PASSWORD, null)
        set(v) = prefs.edit().putString(KEY_PASSWORD, v).apply()

    /** 是否已记住凭据。 */
    val hasCredentials: Boolean
        get() = !username.isNullOrBlank() && !password.isNullOrBlank()

    /** 保存凭据。传 null 表示清除。 */
    fun save(username: String?, password: String?) {
        prefs.edit().apply {
            if (username.isNullOrBlank()) remove(KEY_USERNAME) else putString(KEY_USERNAME, username)
            if (password.isNullOrBlank()) remove(KEY_PASSWORD) else putString(KEY_PASSWORD, password)
        }.apply()
    }

    /** 清除全部凭据。 */
    fun clear() {
        prefs.edit().remove(KEY_USERNAME).remove(KEY_PASSWORD).apply()
    }

    private companion object {
        const val PREFS_NAME = "gzus_credentials"
        const val KEY_USERNAME = "username"
        const val KEY_PASSWORD = "password"
    }
}
