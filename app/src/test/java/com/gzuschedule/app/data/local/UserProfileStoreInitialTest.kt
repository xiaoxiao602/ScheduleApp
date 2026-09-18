package com.gzuschedule.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「姓氏头像」取字逻辑测试（ADR-012）。
 *
 * 纯 Kotlin，无需 Android 运行时，故能真跑。
 *
 * ⚠️ 用户要求：无自定义头像时显示【姓氏】。
 * 中文取首字；英文取首字母大写。空值要有安全回退，不能崩也不能空白。
 */
class UserProfileStoreInitialTest {

    @Test
    fun `中文姓名取姓氏首字`() {
        assertEquals("李", UserProfileStore.initial("李萧宇"))
    }

    @Test
    fun `单字姓名取其本身`() {
        assertEquals("张", UserProfileStore.initial("张"))
    }

    @Test
    fun `英文名取首字母并大写`() {
        assertEquals("X", UserProfileStore.initial("xiaoxiao"))
        assertEquals("A", UserProfileStore.initial("alice"))
    }

    @Test
    fun `已有大写保持大写`() {
        assertEquals("X", UserProfileStore.initial("XIAOXIAO"))
    }

    @Test
    fun `带首尾空格也能正确处理`() {
        assertEquals("李", UserProfileStore.initial("  李萧宇  "))
    }

    @Test
    fun `空值回退为问号而非崩溃或空白`() {
        assertEquals("?", UserProfileStore.initial(null))
        assertEquals("?", UserProfileStore.initial(""))
        assertEquals("?", UserProfileStore.initial("   "))
    }

    @Test
    fun `非字母开头回退为问号`() {
        assertEquals("?", UserProfileStore.initial("123abc"))
        assertEquals("?", UserProfileStore.initial("·测试"))
    }

    @Test
    fun `结果始终为单个字符`() {
        val cases = listOf("李萧宇", "张三", "xiaoxiao", "A", " 王 ")
        for (c in cases) {
            assertEquals("「$c」应取单字", 1, UserProfileStore.initial(c).length)
        }
    }
}
