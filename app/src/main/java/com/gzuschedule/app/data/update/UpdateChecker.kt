package com.gzuschedule.app.data.update

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 检查更新（ADR-107）。
 *
 * ⚠️ 用户需求：
 *    「在设置里加入检查更新的功能 自动访问我的仓库看看软件有没有新版本
 *      可以设置开启或者关闭」
 *
 * 实现：
 *   · 请求 GitHub Releases API（最新的 release）
 *   · 与本机 versionName 比对
 *   · 只读，不需要 token（仓库是 public）
 *
 * ⚠️ 隐私：只发一个 GET，不带任何用户信息。
 */
object UpdateChecker {

    /** 仓库坐标。 */
    private const val OWNER = "xiaoxiao602"
    private const val REPO = "ScheduleApp"

    /** GitHub Releases API（取最新一个非 draft / 非 prerelease）。 */
    private const val API = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** 超时短一点 —— 检查更新不该让用户等。 */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private val gson = Gson()

    /** 检查结果。 */
    data class Result(
        /** 有新版可用。 */
        val hasUpdate: Boolean,
        /** 最新版本号（去掉前缀 v），如 "1.0.2"。 */
        val latestVersion: String?,
        /** Release 页面地址（让用户去下载）。 */
        val releaseUrl: String?,
        /** APK 直链（可能为空）。 */
        val apkUrl: String?,
        /** 更新说明（Release body）。 */
        val notes: String?,
        /** 出错时的信息（无错为 null）。 */
        val error: String? = null,
    )

    /**
     * 检查是否有新版本。
     *
     * @param currentVersion 当前 versionName，如 "1.0.1"
     */
    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(API)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "GzuScheduleApp")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@runCatching Result(
                        false, null, null, null, null,
                        error = "服务器返回 ${resp.code}",
                    )
                }
                val body = resp.body?.string()
                    ?: return@runCatching Result(
                        false, null, null, null, null, error = "响应为空",
                    )

                val rel = gson.fromJson(body, ReleaseJson::class.java)
                    ?: return@runCatching Result(
                        false, null, null, null, null, error = "解析失败",
                    )

                val latest = rel.tagName?.trim()?.removePrefix("v")?.removePrefix("V")
                    ?: return@runCatching Result(
                        false, null, null, null, null, error = "无版本号",
                    )

                val apk = rel.assets
                    ?.firstOrNull { a -> a.name?.endsWith(".apk", ignoreCase = true) == true }
                    ?.browserDownloadUrl

                Result(
                    hasUpdate = isNewer(latest, currentVersion),
                    latestVersion = latest,
                    releaseUrl = rel.htmlUrl,
                    apkUrl = apk,
                    notes = rel.body?.take(600),
                )
            }
        }.getOrElse { e ->
            Result(false, null, null, null, null, error = e.message ?: "网络错误")
        }
    }

    /**
     * 判断 [latest] 是否比 [current] 新。
     *
     * ⚠️ 用**逐段数字比较**，不能直接字符串比（"1.0.10" < "1.0.9" 是错的）。
     *    非数字段按 0 处理（如 "1.0.1-beta" → [1,0,1]）。
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parseVersion(latest)
        val b = parseVersion(current)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false   // 完全相同
    }

    /** "1.0.1" → [1, 0, 1]；取不出数字的段按 0。 */
    private fun parseVersion(v: String): List<Int> =
        v.split(".", "-", "_", "+")
            .map { seg ->
                seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }

    // ---------- GitHub API 响应模型 ----------

    private data class ReleaseJson(
        @SerializedName("tag_name") val tagName: String?,
        @SerializedName("html_url") val htmlUrl: String?,
        @SerializedName("body") val body: String?,
        val assets: List<AssetJson>?,
    )

    private data class AssetJson(
        val name: String?,
        @SerializedName("browser_download_url") val browserDownloadUrl: String?,
    )
}
