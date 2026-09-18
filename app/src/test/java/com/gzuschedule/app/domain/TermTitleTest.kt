package com.gzuschedule.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 学期标题格式化测试（ADR-016）。
 *
 * ⚠️ 核心防错：`XQM`（编码）≠「第几学期」。
 * 抓包实证：
 *   XQM   = "3"   ← 编码，若当学期号会写成 Term3（错）
 *   XQMMC = "1"   ← 名称，正确值
 * 故 TermTitle 优先用 XQMMC，仅在其缺失时按编码映射兜底。
 */
class TermTitleTest {

    // ---------- 学年缩写 ----------

    @Test
    fun `学年 2026-2027 缩写为 26-27`() {
        assertEquals("26-27", TermTitle.shortYear("2026-2027"))
    }

    @Test
    fun `单年或异常输入原样返回`() {
        assertEquals("2026", TermTitle.shortYear("2026"))
        assertEquals("", TermTitle.shortYear(null))
        assertEquals("", TermTitle.shortYear(""))
    }

    // ---------- 编码映射 ----------

    @Test
    fun `XQM 3 映射为第 1 学期`() {
        assertEquals(1, TermTitle.termNoFromCode("3"))
    }

    @Test
    fun `XQM 12 映射为第 2 学期`() {
        assertEquals(2, TermTitle.termNoFromCode("12"))
    }

    @Test
    fun `XQM 16 映射为第 3 学期`() {
        assertEquals(3, TermTitle.termNoFromCode("16"))
    }

    @Test
    fun `未知编码返回 null（不瞎猜）`() {
        assertNull(TermTitle.termNoFromCode("99"))
        assertNull(TermTitle.termNoFromCode(null))
        assertNull(TermTitle.termNoFromCode(""))
    }

    // ---------- 完整标题 ----------

    @Test
    fun `真实数据生成 AY26-27 Term1`() {
        // 抓包实证值：XNMC="2026-2027"，XQMMC="1"
        assertEquals("AY26-27 Term1", TermTitle.of("2026-2027", "1"))
    }

    @Test
    fun `学期名优先于编码（关键防错）`() {
        // 同时给 XQMMC="1" 与 XQM="3"，必须取 1 而不是 3
        val t = TermTitle.of("2026-2027", "1", xqmCode = "3")
        assertEquals("AY26-27 Term1", t)
    }

    @Test
    fun `学期名缺失时才用编码兜底`() {
        assertEquals("AY26-27 Term1", TermTitle.of("2026-2027", null, xqmCode = "3"))
        assertEquals("AY26-27 Term2", TermTitle.of("2026-2027", null, xqmCode = "12"))
    }

    @Test
    fun `第二学期也能正确生成`() {
        assertEquals("AY26-27 Term2", TermTitle.of("2026-2027", "2"))
    }

    @Test
    fun `学年缺失时只显示学期`() {
        assertEquals("Term1", TermTitle.of(null, "1"))
    }

    @Test
    fun `学期缺失时只显示学年`() {
        assertEquals("AY26-27", TermTitle.of("2026-2027", null))
    }

    @Test
    fun `全都缺失时退回课表`() {
        assertEquals("课表", TermTitle.of(null, null))
        assertEquals("课表", TermTitle.of("", ""))
    }

    @Test
    fun `空白字符串按缺失处理`() {
        assertEquals("AY26-27 Term1", TermTitle.of("2026-2027", "  ", xqmCode = "3"))
        assertEquals("课表", TermTitle.of("   ", "  "))
    }

    @Test
    fun `绝不会生成 Term3（回归防护）`() {
        // 曾把 XQM=3 当学期号，标题错成 Term3
        val t = TermTitle.of("2026-2027", "1", xqmCode = "3")
        assertEquals(false, t.contains("Term3"))
    }
}
