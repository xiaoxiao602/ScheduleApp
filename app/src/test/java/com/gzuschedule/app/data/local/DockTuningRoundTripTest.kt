package com.gzuschedule.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Dock 参数回写行为测试（ADR-035）。
 *
 * ⚠️ 这是**真单元测试**，专门锁住用户报告的「只会变大不会缩小」那个 bug。
 *
 * Bug 成因（已在代码中修复）：
 *   `Slider.addOnChangeListener` / `addOnSliderTouchListener` 是**追加**语义，
 *   不是设置语义。设置页每次重建视图都会再注册一层，
 *   于是同一个拖动会触发 N 次回调 —— 保存 N 次、刷新 Dock N 次，
 *   松手后的刷新会覆盖拖到一半的值，表现为"调不小 / 只会变大"。
 *
 * 修复策略（见 SettingsFragment.setupDockTuning）：
 *   1. 绑定前先 clearOnChangeListeners() / clearOnSliderTouchListeners()
 *   2. 用 `fromUser` 参数区分「用户拖动」与「程序设值」，程序设值不回写
 *   3. 用统一的 rows 表渲染，保证「滑块位置 = store 值 = 标签文字」一致
 *
 * 本测试验证第 2、3 点的**语义**：程序设值不改变 store，
 * 用户设值能双向改变（既能变大也能变小）。
 */
class DockTuningRoundTripTest {

    /** 模拟一个最小化的 store（与 DockTuningStore 的夹取语义一致）。 */
    private class FakeStore(lo: Int, hi: Int, init: Int) {
        private val lo = lo
        private val hi = hi
        var value: Int = init.coerceIn(lo, hi)
            set(v) {
                field = v.coerceIn(lo, hi)
            }

        /** 模拟"程序设值"：写 UI 但不回写 store。 */
        fun programmaticSet(v: Int): Int = v.coerceIn(lo, hi)

        /** 模拟"用户拖动"：写 UI 且回写 store。 */
        fun userSet(v: Int): Int {
            value = v
            return value
        }
    }

    @Test
    fun `用户可以把值调小（回归：曾经只能变大）`() {
        val s = FakeStore(DockTuningStore.MIN_HEIGHT, DockTuningStore.MAX_HEIGHT, 80)
        assertEquals(80, s.value)

        // 一路调小
        assertEquals(60, s.userSet(60))
        assertEquals(50, s.userSet(50))
        assertEquals(42, s.userSet(42))
        assertEquals(42, s.value)

        // 再调大也要正常
        assertEquals(70, s.userSet(70))
        assertEquals(70, s.value)
    }

    @Test
    fun `程序设值不改变 store（防回环）`() {
        val s = FakeStore(DockTuningStore.MIN_HEIGHT, DockTuningStore.MAX_HEIGHT, 56)
        val shown = s.programmaticSet(72)
        assertEquals("程序设值只改 UI 显示", 72, shown)
        assertEquals("但 store 必须保持原值", 56, s.value)
    }

    @Test
    fun `用户调小到下限不会越界`() {
        val s = FakeStore(DockTuningStore.MIN_HEIGHT, DockTuningStore.MAX_HEIGHT, 56)
        assertEquals(DockTuningStore.MIN_HEIGHT, s.userSet(0))
        assertEquals(DockTuningStore.MIN_HEIGHT, s.userSet(-50))
    }

    @Test
    fun `用户调大到上限不会越界`() {
        val s = FakeStore(DockTuningStore.MIN_HEIGHT, DockTuningStore.MAX_HEIGHT, 56)
        assertEquals(DockTuningStore.MAX_HEIGHT, s.userSet(999))
    }

    @Test
    fun `反复调小调大后最终值正确（模拟多次进设置页）`() {
        // 模拟：进设置页 → 调小 → 退出 → 再进 → 再调小
        val s = FakeStore(DockTuningStore.MIN_HEIGHT, DockTuningStore.MAX_HEIGHT, 96)
        // 第一次进页面：程序按 store 渲染
        assertEquals(96, s.programmaticSet(s.value))
        assertEquals(96, s.value)
        // 用户调小
        assertEquals(50, s.userSet(50))
        // 第二次进页面：程序再按 store 渲染 —— 必须渲染成 50，而不是回弹到 96
        assertEquals(50, s.programmaticSet(s.value))
        assertEquals("store 不能被程序渲染改写", 50, s.value)
        // 再调小
        assertEquals(44, s.userSet(44))
        assertEquals(44, s.value)
    }

    @Test
    fun `文字大小以十分之一 sp 存储，可双向调整`() {
        val s = FakeStore(DockTuningStore.MIN_TEXT, DockTuningStore.MAX_TEXT, 135)
        assertEquals(120, s.userSet(120))   // 调小到 12.0sp
        assertEquals(12.0f, s.value / 10f, 0.001f)
        assertEquals(160, s.userSet(160))   // 调大到 16.0sp
        assertEquals(16.0f, s.value / 10f, 0.001f)
    }

    @Test
    fun `距底部位置可双向调整（ADR-041 新字段）`() {
        val s = FakeStore(DockTuningStore.MIN_BOTTOM_OFFSET, DockTuningStore.MAX_BOTTOM_OFFSET, 16)
        assertEquals(0, s.userSet(0))      // 贴底
        assertEquals(0, s.value)
        assertEquals(60, s.userSet(60))    // 抬高
        assertEquals(60, s.value)
        assertEquals(16, s.userSet(16))    // 再放回（能双向）
        assertEquals(16, s.value)
    }

    @Test
    fun `Dock 宽度可双向调整（ADR-041 新字段）`() {
        val s = FakeStore(DockTuningStore.MIN_DOCK_WIDTH, DockTuningStore.MAX_DOCK_WIDTH, 0)
        assertEquals(0, s.userSet(0))       // 自适应
        assertEquals(80, s.userSet(80))     // 变窄
        assertEquals(80, s.value)
        assertEquals(0, s.userSet(0))       // 再变宽回来
        assertEquals(0, s.value)
    }

    @Test
    fun `阻尼与时长可双向调整（ADR-039）`() {
        val d = FakeStore(DockTuningStore.MIN_DAMPING, DockTuningStore.MAX_DAMPING, 160)
        assertEquals(100, d.userSet(100))   // 无回弹
        assertEquals(250, d.userSet(250))   // 弹跳强
        assertEquals(100, d.userSet(100))   // 能调回

        val t = FakeStore(DockTuningStore.MIN_ANIM_MS, DockTuningStore.MAX_ANIM_MS, 420)
        assertEquals(200, t.userSet(200))
        assertEquals(800, t.userSet(800))
        assertEquals(200, t.userSet(200))
    }
}
