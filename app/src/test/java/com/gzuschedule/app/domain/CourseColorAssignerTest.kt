package com.gzuschedule.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CourseColorAssigner] 单测（ADR-050）。
 *
 * ⚠️ 用户反馈的 bug：「英语卡片和高数课的卡片颜色一致」——
 *    这属于**数据正确性**（配色分配算法），值得测。
 */
class CourseColorAssignerTest {

    /** 用户实际遇到的那两门课，必须不同色。 */
    @Test
    fun `英语与高数不同色（用户报的 bug）`() {
        val courses = listOf(
            "大学英语", "高等数学II(理)", "数据库原理",
            "操作系统基础", "程序设计基础", "马克思主义基本原理",
        )
        val map = CourseColorAssigner.assign(courses, 8)

        assertEquals(courses.size, map.size)
        assertNotEquals(map["大学英语"], map["高等数学II(理)"])
    }

    @Test
    fun `课程数不超过色位数时绝不撞色`() {
        val courses = (1..8).map { "课程$it" }
        val map = CourseColorAssigner.assign(courses, 8)
        // 8 门课 8 个色位 → 颜色应两两不同
        assertEquals(8, map.values.toSet().size)
    }

    @Test
    fun `同一门课永远同色（与输入顺序无关）`() {
        val a = CourseColorAssigner.assign(listOf("高数", "英语", "Python"), 8)
        val b = CourseColorAssigner.assign(listOf("Python", "高数", "英语"), 8)
        assertEquals(a, b)
    }

    @Test
    fun `重复课名只占一个色位`() {
        val map = CourseColorAssigner.assign(listOf("高数", "高数", "英语"), 8)
        assertEquals(2, map.size)
    }

    @Test
    fun `课程数超过色位数时按序复用`() {
        val courses = (1..10).map { "课程$it" }
        val map = CourseColorAssigner.assign(courses, 8)
        // ⚠️ assign() 内部会**按名称排序**，所以"课程1"~"课程10"的字典序是
        //    课程1, 课程10, 课程2, ... —— 不能假设输入顺序即下标顺序。
        //    这里只断言不变量：10 门课只用到 8 个色位，且色值在合法范围内。
        assertEquals(10, map.size)
        assertTrue(map.values.all { it in 0..7 })
        assertTrue("应复用色位（只用 8 个）", map.values.toSet().size <= 8)
    }

    @Test
    fun `相邻两门课必然不同色（排序后）`() {
        val courses = (1..6).map { "课程$it" }
        val map = CourseColorAssigner.assign(courses, 8)
        // 依次取色（0,1,2,...），排序后相邻的课必不同色
        val sorted = courses.sorted()
        sorted.zipWithNext { a, b -> assertNotEquals(map[a], map[b]) }
    }

    @Test
    fun `空输入返回空映射`() {
        assertTrue(CourseColorAssigner.assign(emptyList(), 8).isEmpty())
    }

    @Test
    fun `空白课名被忽略`() {
        val map = CourseColorAssigner.assign(listOf("", "  ", "高数"), 8)
        assertEquals(1, map.size)
        assertTrue(map.containsKey("高数"))
    }

    @Test
    fun `调色板大小为 0 时安全返回`() {
        assertTrue(CourseColorAssigner.assign(listOf("高数"), 0).isEmpty())
        assertEquals(0, CourseColorAssigner.assignFallback("高数", 0))
    }

    @Test
    fun `兜底散列 —— 同一课名稳定、下标在范围内`() {
        val a = CourseColorAssigner.assignFallback("高等数学II(理)", 8)
        val b = CourseColorAssigner.assignFallback("高等数学II(理)", 8)
        assertEquals(a, b)
        assertTrue(a in 0..7)
    }

    @Test
    fun `兜底散列比 hashCode 更少碰撞（FNV-1a 改进）`() {
        // 用户那组课名，用 FNV-1a 兜底也不该全撞在一起
        val names = listOf(
            "大学英语", "高等数学II(理)", "数据库原理",
            "操作系统基础", "程序设计基础", "马克思主义基本原理",
        )
        val idx = names.map { CourseColorAssigner.assignFallback(it, 8) }
        // 6 门课至少应落在 4 个不同色位（兜底不保证不撞，但不应太差）
        assertTrue("实际落入 ${idx.toSet().size} 个色位", idx.toSet().size >= 4)
    }
}
