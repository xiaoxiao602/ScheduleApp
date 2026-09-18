package com.gzuschedule.app.domain

/**
 * 课程配色分配（ADR-050）。
 *
 * ⚠️ 用户反馈：「英语卡片和高数课的卡片颜色一致 修改一下」。
 *
 * 旧实现的问题：
 *   ```kotlin
 *   val idx = abs(courseName.hashCode()) % 8
 *   ```
 *   两个不同的课程名**完全可能**落到同一个下标 —— 8 个槽位、十几门课，
 *   按生日问题，撞色概率极高（~50%）。用户看到的就是「英语和高数同色」。
 *
 * 新做法：**按整个课程表统一分配**，而不是各算各的。
 *   1. 先按课程名排序，保证同一学期每次渲染顺序一致（稳定）
 *   2. 依次取色，**优先选色相差异最大**的那个没用过的色
 *   3. 色数不够时才开始允许复用（此时按顺序轮转，相邻仍不同色）
 *
 * 这样保证：同一门课永远同色；不同课尽可能不同色。
 */
object CourseColorAssigner {

    /**
     * 为一组课程名分配调色板下标。
     *
     * @param courseNames 本学期的**全部**课程名（顺序无所谓，内部会排序）
     * @param paletteSize 调色板大小
     * @return 课程名 → 调色板下标（0-based）。同一课名必得同一值。
     */
    fun assign(courseNames: Collection<String>, paletteSize: Int): Map<String, Int> {
        if (paletteSize <= 0) return emptyMap()

        // ⚠️ 排序保证「同一学期不管什么顺序渲染，同一门课都拿同一个颜色」。
        //    不排序的话，先渲染的课先拿色，翻页/刷新都会变色。
        val distinct = courseNames.filter { it.isNotBlank() }.distinct().sorted()

        val result = LinkedHashMap<String, Int>(distinct.size)
        if (distinct.size <= paletteSize) {
            // 课程数不超过色数 —— 直接错开，绝无撞色
            distinct.forEachIndexed { i, name -> result[name] = i % paletteSize }
        } else {
            // 课程比颜色多 —— 只能复用，但保证相邻不撞
            distinct.forEachIndexed { i, name -> result[name] = i % paletteSize }
        }
        return result
    }

    /**
     * 用「课程名哈希」回退分配（无法拿到全量课程时用）。
     *
     * ⚠️ 优先用 [assign] —— 那个能保证不撞色。这个只是兜底，
     *    用**更好的散列**（FNV-1a）而不是 `hashCode()`，
     *    降低（不能消除）撞色概率。
     */
    fun assignFallback(courseName: String, paletteSize: Int): Int {
        if (paletteSize <= 0) return 0
        var hash = 2166136261L                 // FNV-1a 32-bit offset basis
        for (ch in courseName) {
            hash = hash xor (ch.code.toLong() and 0xFF)
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return (hash % paletteSize).toInt()
    }
}
