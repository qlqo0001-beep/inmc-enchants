package com.inmc.enchants

import com.inmc.enchants.item.EnchantItems
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `books.drag-drop` 를 끄면 빼는 안내줄 판별. */
class DragHintTest {

    @Test
    fun `기본 안내줄을 알아본다`() {
        assertTrue(EnchantItems.isDragHint("<gray>아이템 위에 끌어다 놓으면 붙습니다."))
    }

    @Test
    fun `색이 달라도 알아본다`() {
        assertTrue(EnchantItems.isDragHint("&7아이템 위에 끌어다 놓으면 붙습니다."))
    }

    @Test
    fun `다른 줄은 건드리지 않는다`() {
        assertFalse(EnchantItems.isDragHint("<green>성공률 100%"))
        assertFalse(EnchantItems.isDragHint("<gray>검 인첸트"))
        assertFalse(EnchantItems.isDragHint("<yellow>영혼을 써서 뒤에서 벤다."))
    }
}
