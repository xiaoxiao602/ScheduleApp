package com.gzuschedule.app.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 时间格式化的共享实例（ADR-054 性能）。
 *
 * ⚠️ 为什么集中在这里：
 *   `SimpleDateFormat` 的**构造开销很大**（解析 pattern + 构建 Locale 数据），
 *   而它只在**单线程**里用才安全。之前 `TodayViewModel.readLastSync()` 每次调用
 *   都 new 两个实例 —— 而该方法被 30 秒心跳反复触发，纯属浪费。
 *
 * ⚠️ 线程安全说明（重要）：
 *   `SimpleDateFormat` **不是线程安全的**。本类的实例**只允许在主线程使用**
 *   （调用点都在 UI 渲染路径/ViewModel 主协程）。
 *   若将来要在 IO 线程用，请改用 `java.time.DateTimeFormatter`（不可变、线程安全）
 *   或每个线程各自持有实例。
 */
object TimeFormats {

    /** 教务写入的 ISO 本地时间，如 "2026-09-18T15:53:00"。 */
    private val ISO_LOCAL = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

    /** 展示用：月日 + 时分，如 "9月18日 15:53"。 */
    private val MONTH_DAY_TIME = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

    /**
     * 把 ISO 本地时间串格式化成「9月18日 15:53」。
     *
     * @return 解析失败时**原样返回输入**（调用方无需处理异常，也不会显示空）
     */
    @Synchronized
    fun isoToDisplay(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val parsed: Date? = ISO_LOCAL.parse(raw)
            MONTH_DAY_TIME.format(parsed ?: Date())
        }.getOrElse { raw }
    }
}
