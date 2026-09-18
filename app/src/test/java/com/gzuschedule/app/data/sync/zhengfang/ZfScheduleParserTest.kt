package com.gzuschedule.app.data.sync.zhengfang

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 正方课表解析器测试 —— 全部基于【真实抓包数据】。
 *
 * fixture: src/test/resources/zhengfang_schedule_sample.json
 * 来源: jwxt.gzus.edu.cn/jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151
 *
 * 这个测试的意义：解析器的正确性由真实数据保证，
 * 而不是我凭想象构造的理想输入。
 */
class ZfScheduleParserTest {

    private fun loadFixture(): String =
        javaClass.classLoader!!
            .getResourceAsStream("zhengfang_schedule_sample.json")!!
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

    // ---------- 整体解析 ----------

    @Test
    fun `能解析出全部课程`() {
        val result = ZfScheduleParser.parse(loadFixture())
        // fixture 中有 7 条课程记录
        assertEquals(7, result.courses.size)
    }

    @Test
    fun `解析出学生信息`() {
        val s = ZfScheduleParser.parse(loadFixture()).student
        assertNotNull("应解析出学生信息", s)
        assertEquals("2026000001", s!!.studentId)
        assertEquals("张同学", s.name)
        assertEquals("2026-2027", s.academicYear)
    }

    // ---------- 课程字段 ----------

    @Test
    fun `课程名与教师正确`() {
        val courses = ZfScheduleParser.parse(loadFixture()).courses
        val db = courses.first { it.name == "数据库原理" }
        assertEquals("张老师", db.teacher)
        assertEquals("3.0", db.credit)
    }

    @Test
    fun `星期几正确`() {
        val courses = ZfScheduleParser.parse(loadFixture()).courses
        // 数据库原理 - 星期二
        assertEquals(
            2,
            courses.first { it.name == "数据库原理" }.dayOfWeek
        )
        // 形势与政策 - 星期四
        assertEquals(
            4,
            courses.first { it.name == "形势与政策" }.dayOfWeek
        )
        // 马克思主义基本原理 - 星期五
        assertEquals(
            5,
            courses.first { it.name == "马克思主义基本原理" }.dayOfWeek
        )
    }

    @Test
    fun `教室正确`() {
        val courses = ZfScheduleParser.parse(loadFixture()).courses
        assertEquals("A101", courses.first { it.name == "数据库原理" }.location)
        assertEquals("A104", courses.first { it.name == "形势与政策" }.location)
    }

    @Test
    fun `一门课多条记录不被去重`() {
        // 数据库原理 在 fixture 中出现 1 次；
        // 操作系统基础 出现 2 次（单周 + 双周，不同教师）
        val courses = ZfScheduleParser.parse(loadFixture()).courses
        assertEquals(
            "Linux 的单双周应各保留一条",
            2,
            courses.count { it.name == "操作系统基础" },
        )
    }

    // ---------- 节次解析 ----------

    @Test
    fun `节次解析 - 范围`() {
        assertEquals(3 to 4, ZfScheduleParser.parsePeriods("3-4"))
        assertEquals(13 to 14, ZfScheduleParser.parsePeriods("13-14"))
        assertEquals(15 to 16, ZfScheduleParser.parsePeriods("15-16"))
    }

    @Test
    fun `节次解析 - 单节`() {
        assertEquals(9 to 9, ZfScheduleParser.parsePeriods("9"))
    }

    @Test
    fun `节次解析 - 容忍带单位`() {
        assertEquals(3 to 4, ZfScheduleParser.parsePeriods("3-4节"))
    }

    @Test
    fun `节次解析 - 非法输入返回 null`() {
        assertNull(ZfScheduleParser.parsePeriods(null))
        assertNull(ZfScheduleParser.parsePeriods(""))
        assertNull(ZfScheduleParser.parsePeriods("abc"))
        assertNull(ZfScheduleParser.parsePeriods("4-3"))  // 倒序非法
    }

    // ---------- 周次解析（含单双周，最关键） ----------

    @Test
    fun `周次解析 - 普通范围`() {
        val w = ZfScheduleParser.parseWeeks("1-18周")
        assertEquals(1, w.start)
        assertEquals(18, w.end)
        assertEquals(0, w.parity)
    }

    @Test
    fun `周次解析 - 单周`() {
        val w = ZfScheduleParser.parseWeeks("1-17周(单)")
        assertEquals(1, w.start)
        assertEquals(17, w.end)
        assertEquals("单周应标记 parity=1", 1, w.parity)
    }

    @Test
    fun `周次解析 - 双周`() {
        val w = ZfScheduleParser.parseWeeks("2-18周(双)")
        assertEquals(2, w.start)
        assertEquals(18, w.end)
        assertEquals("双周应标记 parity=2", 2, w.parity)
    }

    @Test
    fun `周次解析 - 短范围`() {
        val w = ZfScheduleParser.parseWeeks("6-9周")
        assertEquals(6, w.start)
        assertEquals(9, w.end)
        assertEquals(0, w.parity)
    }

    @Test
    fun `周次解析 - 空值回退为全学期`() {
        val w = ZfScheduleParser.parseWeeks(null)
        assertEquals(1, w.start)
        assertEquals(20, w.end)
        assertEquals("解析失败不应丢课", 0, w.parity)
    }

    // ---------- 端到端：单双周在实际课程上生效 ----------

    @Test
    fun `单周课程在第2周不上课`() {
        val courses = ZfScheduleParser.parse(loadFixture()).courses
        val odd = courses.first { it.note.contains("必修") && it.startWeek == 1 && it.endWeek == 17 }
        // 操作系统基础（单周，1-17周）
        assertTrue("第1周应上课", odd.occursInWeek(1))
        assertTrue("第3周应上课", odd.occursInWeek(3))
        assertTrue("第2周不应上课（单周课）", !odd.occursInWeek(2))
        assertTrue("第4周不应上课（单周课）", !odd.occursInWeek(4))
    }

    @Test
    fun `双周课程在第1周不上课`() {
        val courses = ZfScheduleParser.parse(loadFixture()).courses
        // 操作系统基础（双周，2-18周）
        val even = courses.filter { it.name == "操作系统基础" }
            .first { it.startWeek == 2 }
        assertTrue("第2周应上课", even.occursInWeek(2))
        assertTrue("第4周应上课", even.occursInWeek(4))
        assertTrue("第1周不应上课（双周课）", !even.occursInWeek(1))
        assertTrue("第3周不应上课（双周课）", !even.occursInWeek(3))
    }

    @Test
    fun `超出周次范围不上课`() {
        val courses = ZfScheduleParser.parse(loadFixture()).courses
        // 形势与政策：6-9周
        val c = courses.first { it.name == "形势与政策" }
        assertTrue("第5周不应上课", !c.occursInWeek(5))
        assertTrue("第6周应上课", c.occursInWeek(6))
        assertTrue("第9周应上课", c.occursInWeek(9))
        assertTrue("第10周不应上课", !c.occursInWeek(10))
    }

    // ---------- 健壮性 ----------

    @Test
    fun `空响应不崩溃`() {
        val r = ZfScheduleParser.parse("""{"kbList":[]}""")
        assertEquals(0, r.courses.size)
        assertNull(r.student)
    }

    @Test
    fun `无效 JSON 不崩溃`() {
        val r = ZfScheduleParser.parse("not json at all")
        assertEquals(0, r.courses.size)
    }

    @Test
    fun `空字符串不崩溃`() {
        assertEquals(0, ZfScheduleParser.parse("").courses.size)
    }

    @Test
    fun `会话失效返回 HTML 登录页时不崩溃`() {
        // 真实故障场景：Cookie 过期后，正方返回登录页 HTML 而非 JSON。
        // 若不捕获异常，App 会崩溃 —— 这是最容易在生产中踩到的坑。
        val html = """
            <!DOCTYPE html>
            <html><head><title>统一身份认证平台</title></head>
            <body><form id="login"><input name="yhm"></form></body></html>
        """.trimIndent()
        val r = ZfScheduleParser.parse(html)
        assertEquals("HTML 应被安全忽略", 0, r.courses.size)
        assertNull(r.student)
    }

    @Test
    fun `缺少必需字段的条目被跳过而非崩溃`() {
        // 缺少 kcmc 或 xqj 的条目应被丢弃
        val json = """
            {"kbList":[
              {"kcmc":"有效课","xqj":"1","jcor":"1-2","zcd":"1-18周"},
              {"xqj":"2","jcor":"3-4"},
              {"kcmc":"缺星期","jcor":"5-6"},
              {"kcmc":"星期越界","xqj":"9","jcor":"7-8"}
            ]}
        """.trimIndent()
        val r = ZfScheduleParser.parse(json)
        assertEquals("只应保留 1 条有效记录", 1, r.courses.size)
        assertEquals("有效课", r.courses[0].name)
    }
}
