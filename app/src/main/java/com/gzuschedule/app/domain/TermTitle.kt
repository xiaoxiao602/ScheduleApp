package com.gzuschedule.app.domain

/**
 * 学期标题格式化（ADR-016）。
 *
 * 目标格式（对齐同校小程序）：`AY26-27 Term1`
 *
 * ⚠️ 关键：`XQM` 与「第几学期」不是同一个数
 * 抓包实证（2026-09-18，xsxx 字段）：
 *   XNM   = "2026"        学年码
 *   XNMC  = "2026-2027"   学年名称
 *   XQM   = "3"           学期【编码】
 *   XQMMC = "1"           学期【名称】← 这才是「第几学期」
 * 若直接用 XQM 当学期号，会错写成 Term3。故优先用 XQMMC。
 *
 * 编码映射（正方惯例，供 XQMMC 缺失时兜底）：
 *   3 → 1（秋）  12 → 2（春）  16 → 3（夏）
 */
object TermTitle {

    /** XQM 编码 → 学期序号。取不到时返回 null，由调用方决定兜底。 */
    fun termNoFromCode(xqm: String?): Int? = when (xqm?.trim()) {
        "3" -> 1
        "12" -> 2
        "16" -> 3
        else -> null
    }

    /**
     * 学年名称 → `26-27` 形式。
     * `"2026-2027"` → `"26-27"`；异常输入原样返回。
     */
    fun shortYear(academicYearName: String?): String {
        val s = academicYearName?.trim().orEmpty()
        if (s.isBlank()) return ""
        val parts = s.split("-", "–")
        if (parts.size < 2) return s
        val a = parts[0].takeLast(2)
        val b = parts[1].takeLast(2)
        return "$a-$b"
    }

    /**
     * 生成标题：`AY26-27 Term1`。
     *
     * @param yearName   学年名称，如 "2026-2027"（来自 XNMC）
     * @param termName   学期序号文本，如 "1"（来自 XQMMC，优先）
     * @param xqmCode    学期编码，如 "3"（XQMMC 缺失时用于兜底）
     * @return 完整标题；信息不足时退回能用多少用多少
     */
    fun of(yearName: String?, termName: String?, xqmCode: String? = null): String {
        val y = shortYear(yearName)
        val t = termName?.trim()?.takeIf { it.isNotBlank() }
            ?: termNoFromCode(xqmCode)?.toString()

        return when {
            y.isNotBlank() && t != null -> "AY$y Term$t"
            y.isNotBlank() -> "AY$y"
            t != null -> "Term$t"
            else -> "课表"
        }
    }
}
