package com.gzuschedule.app.data.sync.zhengfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * `ZfDateRangeParser` 测试。
 *
 * ⚠️ 背景：首页「第 N 周」长期错误（显示第 1 周，实际第 3 周），
 * 根因是拿不到学期起始日。用户指出「官网课表上就有日期」，
 * 才定位到课表页面另调的 cxRsd 日程接口。
 *
 * 由于 cxRsd 响应结构尚未真机确认，解析器设计为多级容错：
 *   ① 已知候选字段名精确匹配
 *   ② 退化扫描任意日期取最早
 * 这里覆盖两种路径，并锁定「结果必须归一到周一」。
 */
class ZfDateRangeParserTest {

    // ---------- ① 精确字段名 ----------

    @Test
    fun `能解析 xqkssj 字段`() {
        val raw = """{"xqkssj":"2026-08-31", "xqjssj":"2027-01-17"}"""
        assertEquals(LocalDate.of(2026, 8, 31), ZfDateRangeParser.firstMonday(raw))
    }

    @Test
    fun `能解析 kkqssj 字段`() {
        val raw = """{"kkqssj":"2026-08-31"}"""
        assertEquals(LocalDate.of(2026, 8, 31), ZfDateRangeParser.firstMonday(raw))
    }

    @Test
    fun `能解析中文日期格式`() {
        val raw = """{"xqkssj":"2026年8月31日"}"""
        assertEquals(LocalDate.of(2026, 8, 31), ZfDateRangeParser.firstMonday(raw))
    }

    // ---------- ② 兜底扫描 ----------

    @Test
    fun `未知字段名时取最早的日期`() {
        val raw = """{"someUnknownKey":"2026-09-07","another":"2026-08-31"}"""
        assertEquals(LocalDate.of(2026, 8, 31), ZfDateRangeParser.firstMonday(raw))
    }

    @Test
    fun `嵌套对象里的日期也能找到`() {
        val raw = """
            {"data":{"rows":[{"begin":"2026-08-31","end":"2027-01-17"}]}}
        """.trimIndent()
        assertEquals(LocalDate.of(2026, 8, 31), ZfDateRangeParser.firstMonday(raw))
    }

    // ---------- ③ 归一化到周一 ----------

    @Test
    fun `起始日不是周一时归到本周一`() {
        // 2026-09-02 是周三 -> 应归到 2026-08-31（周一）
        val raw = """{"xqkssj":"2026-09-02"}"""
        assertEquals(LocalDate.of(2026, 8, 31), ZfDateRangeParser.firstMonday(raw))
    }

    @Test
    fun `周日应归到当周周一而非跨周`() {
        // 2026-09-06 是周日 -> 归到 2026-08-31
        val raw = """{"xqkssj":"2026-09-06"}"""
        assertEquals(LocalDate.of(2026, 8, 31), ZfDateRangeParser.firstMonday(raw))
    }

    @Test
    fun `结果始终是周一`() {
        val cases = listOf(
            """{"xqkssj":"2026-08-31"}""",
            """{"xqkssj":"2026-09-02"}""",
            """{"xqkssj":"2026-09-06"}""",
            """{"k":"2027-02-10"}""",
        )
        for (c in cases) {
            val d = ZfDateRangeParser.firstMonday(c)
            assertEquals("$c 的结果应为周一", 1, d?.dayOfWeek?.value)
        }
    }

    // ---------- ④ 边界与容错 ----------

    @Test
    fun `null 或空返回 null`() {
        assertNull(ZfDateRangeParser.firstMonday(null))
        assertNull(ZfDateRangeParser.firstMonday(""))
        assertNull(ZfDateRangeParser.firstMonday("   "))
    }

    @Test
    fun `无日期返回 null`() {
        assertNull(ZfDateRangeParser.firstMonday("""{"a":"b"}"""))
    }

    @Test
    fun `HTML 登录页返回 null 而不抛异常`() {
        // 会话失效时服务端可能返回 HTML
        val html = "<html><body>统一身份认证</body></html>"
        assertNull(ZfDateRangeParser.firstMonday(html))
    }

    @Test
    fun `畸形 JSON 不抛异常`() {
        assertNull(ZfDateRangeParser.firstMonday("""{"xqkssj": }"""))
        assertNull(ZfDateRangeParser.firstMonday("{[[[,,,"))
    }

    @Test
    fun `非法月份日期被跳过而不崩溃`() {
        // 2026-13-45 非法，应跳过，取合法的那条
        val raw = """{"a":"2026-13-45","b":"2026-08-31"}"""
        assertEquals(LocalDate.of(2026, 8, 31), ZfDateRangeParser.firstMonday(raw))
    }

    // ---------- ⑤ 与周次计算联动（端到端语义） ----------

    @Test
    fun `解析出的起始日可直接用于周次计算`() {
        val raw = """{"xqkssj":"2026-08-31"}"""
        val fm = ZfDateRangeParser.firstMonday(raw)!!
        // 2026-09-18 应为第 3 周
        assertEquals(
            3,
            com.gzuschedule.app.domain.WeekCalculator.weekOf(
                fm, LocalDate.of(2026, 9, 18),
            ),
        )
    }
}
