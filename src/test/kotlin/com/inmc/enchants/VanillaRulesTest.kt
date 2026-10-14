package com.inmc.enchants

import com.inmc.enchants.command.EnchantsCommand
import com.inmc.enchants.config.DisabledEnchants
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
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

    @Test
    fun `인자 원문은 루트 리터럴과 이름이 같아도 인자 노드에서 읽는다`() {
        // 2026-10-15 검증기 "관리자 부여" 가 잡은 것 — 루트 `인첸트` 와 인자 `인첸트` 가 같은 이름이라 이름으로만 찾으면 루트가 먼저 잡혀
        // `/인첸트 부여 minecraft:sharpness 2` 의 원문이 "인첸트" 로 읽혔다("'인첸트' 라는 인첸트가 없습니다").
        val dispatcher = CommandDispatcher<Unit>()
        var seen = ""
        dispatcher.register(
            LiteralArgumentBuilder.literal<Unit>("인첸트").then(
                LiteralArgumentBuilder.literal<Unit>("부여").then(
                    RequiredArgumentBuilder.argument<Unit, String>("인첸트", StringArgumentType.greedyString())
                        .executes { ctx -> seen = EnchantsCommand.rawArgument(ctx, "인첸트"); 1 },
                ),
            ),
        )
        dispatcher.execute("인첸트 부여 Minecraft:Sharpness", Unit)
        assertEquals("minecraft:sharpness", seen, "루트 '인첸트' 가 아니라 인자의 글자(소문자)")
        dispatcher.execute("인첸트 부여 drain", Unit)
        assertEquals("drain", seen)
        dispatcher.register(LiteralArgumentBuilder.literal<Unit>("인첸트").then(LiteralArgumentBuilder.literal<Unit>("관리").executes { ctx -> seen = EnchantsCommand.rawArgument(ctx, "인첸트"); 1 }))
        dispatcher.execute("인첸트 관리", Unit)
        assertEquals("", seen, "인자가 없으면 빈 글 — 리터럴을 인자로 읽지 않는다")
    }
}
