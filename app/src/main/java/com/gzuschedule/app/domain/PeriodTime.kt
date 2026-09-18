package com.gzuschedule.app.domain

/**
 * 节次 → 上下课时间（ADR-014）。
 *
 * ⚠️ 数据来源：**同校官方小程序的真实作息**（用户提供的课表截图）。
 * 此前本表是按「通用大学作息」猜的（1-2 节写成 08:00-08:45），
 * 与本校实际完全不符，故整表按截图重写。
 *
 * 本校作息特征：**每两个小节连排成一个大节，共 16 小节 / 8 个大节**，
 * 每个大节 80 分钟（含课间）。
 *
 * 截图实证（2026-09-18）：
 *   第 1-2 节   09:00 - 10:20
 *   第 3-4 节   10:40 - 12:00
 *   第 5-6 节   12:30 - 13:50
 *   第 7-8 节   14:00 - 15:20
 *   第 9-10 节  15:30 - 16:50
 *   第 11-12 节 17:00 - 18:20
 *   第 13-14 节 19:00 - 20:20
 *   第 15-16 节 20:30 - 21:50
 *
 * ⚠️ 注意：教务数据里的 `jcs`/`jcor` 给的是**小节号**（如 "3-4"），
 * 与上表的大节一一对应，故本表以「大节起点」为索引即可正确查找。
 */
object PeriodTime {

    /**
     * 每【大节】的 (开始, 结束)，索引 = 大节序号（1-based）。
     * 第 0 项占位，使下标与大节号一致。
     */
    private val BLOCKS: List<Pair<String, String>> = listOf(
        "" to "",              // 占位
        "09:00" to "10:20",   // 1-2 节
        "10:40" to "12:00",   // 3-4 节
        "12:30" to "13:50",   // 5-6 节
        "14:00" to "15:20",   // 7-8 节
        "15:30" to "16:50",   // 9-10 节
        "17:00" to "18:20",   // 11-12 节
        "19:00" to "20:20",   // 13-14 节
        "20:30" to "21:50",   // 15-16 节
    )

    /**
     * 把教务的小节号映射到大节序号。
     *
     * 教务用 1,2,3,4…16 表示小节；每两小节为一个大节，
     * 故 大节 = ceil(小节 / 2)。例：3-4 节 -> 大节 2。
     */
    private fun blockOf(period: Int): Int =
        if (period <= 0) 0 else (period + 1) / 2

    /** 大节的开始时间；越界返回空串。 */
    fun startOf(period: Int): String =
        BLOCKS.getOrNull(blockOf(period))?.first.orEmpty()

    /** 大节的结束时间；越界返回空串。 */
    fun endOf(period: Int): String =
        BLOCKS.getOrNull(blockOf(period))?.second.orEmpty()

    /**
     * 一次课的时间段，如 "09:00 - 10:20"。
     *
     * @param startPeriod 起始小节课号
     * @param endPeriod   结束小节课号（含）
     */
    fun range(startPeriod: Int, endPeriod: Int): String {
        val s = BLOCKS.getOrNull(blockOf(startPeriod))?.first.orEmpty()
        val e = BLOCKS.getOrNull(blockOf(endPeriod))?.second.orEmpty()
        return if (s.isBlank() || e.isBlank()) "" else "$s - $e"
    }

    /**
     * 节次文案。用大节表述更贴近本校习惯（小程序显示为 "1-2" 连堂）。
     * 例：(1,2) -> "1-2 节"；(3,4) -> "3-4 节"
     */
    fun periodsLabel(startPeriod: Int, endPeriod: Int): String =
        if (startPeriod == endPeriod) "第 $startPeriod 节"
        else "$startPeriod-$endPeriod 节"
}
