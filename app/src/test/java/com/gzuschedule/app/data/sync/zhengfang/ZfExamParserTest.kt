package com.gzuschedule.app.data.sync.zhengfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/**
 * 考试解析器测试（ADR-013）。
 *
 * 纯 Kotlin（Gson + java.time），可离线真跑。
 *
 * ⚠️ 本校考试响应结构**尚未用真实数据验证**（学生刚入学无考试），
 * 故测试重点在【容错】：各种字段缺失/格式混搭都不能崩，且能尽量取到信息。
 * 字段名依据正方 jwglxt 通用命名（kssj / cdmc / zwh …）。
 */
class ZfExamParserTest {

    @Test
    fun `标准结构可解析`() {
        val raw = """
            {"items":[{"kcmc":"数据库原理与应用","kssj":"2027-01-05 09:00~11:00",
                       "cdmc":"B202","zwh":"12","ksxs":"闭卷"}],"totalResult":1}
        """.trimIndent()
        val list = ZfExamParser.parse(raw, "2026-2027-1")
        assertEquals(1, list.size)
        val e = list[0]
        assertEquals("数据库原理与应用", e.courseName)
        assertEquals(LocalDate.of(2027, 1, 5), e.date)
        assertEquals(LocalTime.of(9, 0), e.startTime)
        assertEquals(LocalTime.of(11, 0), e.endTime)
        assertEquals("B202", e.location)
        assertEquals("12", e.seat)
        assertEquals("闭卷", e.format)
    }

    @Test
    fun `日期与时间分开给也能解析`() {
        val raw = """
            {"items":[{"kcmc":"高等数学","ksrq":"2027-01-08",
                       "kssjStr":"14:00","jssjStr":"16:00","cdmc":"U201"}]}
        """.trimIndent()
        val e = ZfExamParser.parse(raw, "t").single()
        assertEquals(LocalDate.of(2027, 1, 8), e.date)
        assertEquals(LocalTime.of(14, 0), e.startTime)
        assertEquals(LocalTime.of(16, 0), e.endTime)
    }

    @Test
    fun `斜杠日期格式兼容`() {
        val raw = """{"items":[{"kcmc":"英语","kssj":"2027/01/10 08:30~10:30"}]}"""
        val e = ZfExamParser.parse(raw, "t").single()
        assertEquals(LocalDate.of(2027, 1, 10), e.date)
        assertEquals(LocalTime.of(8, 30), e.startTime)
    }

    @Test
    fun `中文日期格式兼容`() {
        val raw = """{"items":[{"kcmc":"物理","ksrq":"2027年1月12日"}]}"""
        val e = ZfExamParser.parse(raw, "t").single()
        assertEquals(LocalDate.of(2027, 1, 12), e.date)
    }

    @Test
    fun `只有课名时也不崩（其余字段为空）`() {
        val raw = """{"items":[{"kcmc":"形势与政策"}]}"""
        val e = ZfExamParser.parse(raw, "t").single()
        assertEquals("形势与政策", e.courseName)
        assertNull(e.date)
        assertNull(e.startTime)
        assertEquals("", e.format)
    }

    @Test
    fun `空 items 返回空列表`() {
        assertEquals(0, ZfExamParser.parse("""{"items":[]}""", "t").size)
    }

    @Test
    fun `null 或空串返回空列表`() {
        assertEquals(0, ZfExamParser.parse(null, "t").size)
        assertEquals(0, ZfExamParser.parse("", "t").size)
        assertEquals(0, ZfExamParser.parse("   ", "t").size)
    }

    @Test
    fun `HTML 登录页返回空列表而不抛异常`() {
        assertEquals(0, ZfExamParser.parse("<html>统一身份认证</html>", "t").size)
    }

    @Test
    fun `畸形 JSON 不抛异常`() {
        assertEquals(0, ZfExamParser.parse("{[[[,,", "t").size)
        assertEquals(0, ZfExamParser.parse("""{"items": }""", "t").size)
    }

    @Test
    fun `无课名的条目被跳过`() {
        val raw = """{"items":[{"cdmc":"B202"},{"kcmc":"有效课程"}]}"""
        val list = ZfExamParser.parse(raw, "t")
        assertEquals(1, list.size)
        assertEquals("有效课程", list[0].courseName)
    }

    @Test
    fun `校区与考场拼接`() {
        val raw = """{"items":[{"kcmc":"课","xqmc":"广州校区","cdmc":"B202"}]}"""
        assertEquals("广州校区 B202", ZfExamParser.parse(raw, "t").single().location)
    }

    @Test
    fun `只有考场无校区时也能显示`() {
        val raw = """{"items":[{"kcmc":"课","cdmc":"B202"}]}"""
        assertEquals("B202", ZfExamParser.parse(raw, "t").single().location)
    }

    @Test
    fun `多条考试都能解析`() {
        val raw = """
            {"items":[
              {"kcmc":"A","kssj":"2027-01-05 09:00~11:00"},
              {"kcmc":"B","kssj":"2027-01-07 14:00~16:00"},
              {"kcmc":"C","kssj":"2027-01-09 09:00~11:00"}
            ],"totalResult":3}
        """.trimIndent()
        val list = ZfExamParser.parse(raw, "t")
        assertEquals(3, list.size)
        assertTrue(list.map { it.courseName }.containsAll(listOf("A", "B", "C")))
    }
}
