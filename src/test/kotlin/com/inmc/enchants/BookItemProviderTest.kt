package com.inmc.enchants

import com.inmc.enchants.hook.BookItemProvider
import com.inmc.enchants.hook.BookItemProvider.Spec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 만들기 전용 공급처 `inmcenchant:` 의 id 문법. */
class BookItemProviderTest {

    @Test
    fun `레벨만 적으면 확률은 설정대로`() {
        assertEquals(Spec("lifesteal", 3, null, null), BookItemProvider.parse("lifesteal:3"))
        assertEquals(Spec("lifesteal", 3, null, null), BookItemProvider.parse("LifeSteal:3"))
    }

    @Test
    fun `성공과 파괴를 둘 다 적는다`() {
        assertEquals(Spec("lifesteal", 2, 75, 10), BookItemProvider.parse("lifesteal:2:75:10"))
        assertEquals(Spec("lifesteal", 2, 0, 100), BookItemProvider.parse("lifesteal:2:0:100"))
    }

    @Test
    fun `제안은 인첸트마다 최대 레벨 한 줄`() {
        assertEquals("lifesteal:5", BookItemProvider.suggestionId("lifesteal", 5))
        assertEquals("lifesteal:1", BookItemProvider.suggestionId("lifesteal", 0))
    }

    @Test
    fun `틀린 꼴은 만들지 않는다`() {
        assertNull(BookItemProvider.parse("lifesteal"))
        assertNull(BookItemProvider.parse("lifesteal:0"))
        assertNull(BookItemProvider.parse("lifesteal:x"))
        assertNull(BookItemProvider.parse("lifesteal:2:75"))
        assertNull(BookItemProvider.parse("lifesteal:2:101:0"))
        assertNull(BookItemProvider.parse("lifesteal:2:50:-1"))
        assertNull(BookItemProvider.parse(":2"))
    }
}
