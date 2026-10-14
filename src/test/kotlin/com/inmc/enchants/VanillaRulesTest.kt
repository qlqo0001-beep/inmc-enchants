package com.inmc.enchants

import com.inmc.enchants.command.EnchantsCommand
import com.inmc.enchants.config.DisabledEnchants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 바닐라 인첸트 관리(2026-10-10, CMI `/cmi enchant`·`disableenchant` 대체)의 글자 규칙 — 권한 레벨 한도와 CMI 이름 읽기. */
class VanillaRulesTest {

    @Test
    fun `레벨 권한 중 가장 큰 것과 기본 한도 중 큰 것`() {
        val granted = listOf("inmcenchant.vanilla.sharpness.10", "inmcenchant.vanilla.sharpness.7", "inmcenchant.use")
        assertEquals(10, EnchantsCommand.vanillaCap(5, "sharpness", granted))
        assertEquals(12, EnchantsCommand.vanillaCap(12, "sharpness", granted))
    }

    @Test
    fun `숫자가 아닌 레벨·다른 인첸트·bypass 는 세지 않는다`() {
        val granted = listOf(
            "inmcenchant.vanilla.sharpness.abc",
            "inmcenchant.vanilla.sharpness",
            "inmcenchant.vanilla.bypass",
            "inmcenchant.vanilla.smite.20",
            "inmcenchant.vanilla.sharpness_extra.30",
        )
        assertEquals(5, EnchantsCommand.vanillaCap(5, "sharpness", granted))
    }

    @Test
    fun `한도는 255 를 넘지 않는다`() {
        assertEquals(EnchantsCommand.MAX_LEVEL, EnchantsCommand.vanillaCap(5, "sharpness", listOf("inmcenchant.vanilla.sharpness.1000")))
    }

    @Test
    fun `CMI 옛 이름과 지금 이름을 minecraft 열쇠로`() {
        assertEquals("minecraft:sharpness", DisabledEnchants.cmiId("DAMAGE_ALL"))
        assertEquals("minecraft:sharpness", DisabledEnchants.cmiId("damage all"))
        assertEquals("minecraft:sharpness", DisabledEnchants.cmiId(" sharpness "))
        assertEquals("minecraft:mending", DisabledEnchants.cmiId("MENDING"))
        assertEquals("minecraft:sweeping_edge", DisabledEnchants.cmiId("SWEEPING"))
        assertEquals("foo:bar", DisabledEnchants.cmiId("foo:bar"))
        assertNull(DisabledEnchants.cmiId("   "))
    }
}
