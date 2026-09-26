package com.inmc.enchants

import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.engine.Trigger
import org.bukkit.configuration.file.YamlConfiguration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefinitionTest {

    private val berserkYaml = """
        berserk:
          display: '%group-color%광전사'
          description: |-
            공격할 때 확률로 힘과
            피로를 함께 얻는다.
          applies-to: 검, 도끼
          type: ATTACK;ATTACK_MOB
          group: UNIQUE
          applies:
            - ALL_SWORD
            - ALL_AXE
          settings:
            not-applyable-with: [rage]
            showActionBar: true
          levels:
            '1':
              chance: 4
              cooldown: 8
              effects:
                - POTION:SLOW_DIGGING:0:60 @Attacker
                - POTION:INCREASE_DAMAGE:0:60 @Attacker
            '2':
              chance: 8
              cooldown: 7
              description: 2 단계 설명
              conditions:
                - '%attacker health% < 5 : %chance%+20'
              effects:
                - POTION:SLOW_DIGGING:0:80 @Attacker
    """.trimIndent()

    private fun load(yaml: String, id: String, problems: MutableList<String> = mutableListOf()): EnchantDefinition? {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        return EnchantDefinition.load(id, config.getConfigurationSection(id)!!, problems)
    }

    @Test
    fun `AE 모양의 인첸트를 읽는다`() {
        val problems = mutableListOf<String>()
        val def = load(berserkYaml, "berserk", problems)
        assertNotNull(def)
        assertTrue(problems.isEmpty(), problems.toString())
        assertEquals(listOf(Trigger.ATTACK, Trigger.ATTACK_MOB), def.triggers)
        assertEquals(2, def.maxLevel)
        assertEquals(listOf("공격할 때 확률로 힘과", "피로를 함께 얻는다."), def.description)
        assertEquals(listOf("2 단계 설명"), def.descriptionFor(2))
        assertEquals(def.description, def.descriptionFor(1), "레벨 설명이 없으면 공통 설명")
        assertEquals(8.0, def.levels[2]!!.chance)
        assertEquals(1, def.levels[2]!!.conditions.size)
        assertTrue(def.settings.showActionBar)
    }

    @Test
    fun `저장한 것을 다시 읽으면 같다`() {
        val def = load(berserkYaml, "berserk")!!
        val out = YamlConfiguration()
        def.save(out.createSection("berserk"))
        val again = load(out.saveToString(), "berserk")!!
        assertEquals(def.copy(levels = emptyMap()), again.copy(levels = emptyMap()))
        for ((n, level) in def.levels) {
            val other = again.levels[n]!!
            assertEquals(level.effects.map { it.raw }, other.effects.map { it.raw })
            assertEquals(level.conditions.map { it.raw }, other.conditions.map { it.raw })
            assertEquals(level.copy(effects = emptyList(), conditions = emptyList()), other.copy(effects = emptyList(), conditions = emptyList()))
        }
    }

    @Test
    fun `틀린 줄만 빼고 이유를 남긴다`() {
        val yaml = """
            bad:
              type: ATTACK;WHAT
              levels:
                '1':
                  effects:
                    - BURN:20 @Victim
                    - NOT_AN_EFFECT
        """.trimIndent()
        val problems = mutableListOf<String>()
        val def = load(yaml, "bad", problems)
        assertNotNull(def, "줄 하나 때문에 인첸트 전체를 버리면 편집 화면에서 찾을 수도 없다")
        assertEquals(1, def.levels[1]!!.effects.size)
        assertTrue(problems.any { it.contains("WHAT") })
        assertTrue(problems.any { it.contains("NOT_AN_EFFECT") })
    }

    @Test
    fun `이름 규칙이나 레벨이 없으면 거부한다`() {
        assertNull(load("Bad-Name:\n  levels: {}", "Bad-Name"))
        assertNull(load("nolevels:\n  type: ATTACK", "nolevels"))
    }

    // --- 붙는 아이템 ---------------------------------------------------------------------

    @Test
    fun `적용 대상 문법`() {
        assertTrue(Applicability.matches("ALL_SWORD", "NETHERITE_SWORD"))
        assertTrue(Applicability.matches("ALL_AXE", "IRON_AXE"))
        assertFalse(Applicability.matches("ALL_AXE", "IRON_PICKAXE"), "곡괭이는 도끼가 아니다")
        assertTrue(Applicability.matches("ALL_SPADE", "DIAMOND_SHOVEL"))
        assertTrue(Applicability.matches("ALL_ARMOR", "TURTLE_HELMET"))
        assertTrue(Applicability.matches("DIAMOND_ARMOR", "DIAMOND_BOOTS"))
        assertFalse(Applicability.matches("DIAMOND_ARMOR", "DIAMOND_SWORD"))
        assertTrue(Applicability.matches("BOW", "bow"))
        assertTrue(Applicability.matches("MISC_HELMETS", "CARVED_PUMPKIN", mapOf("MISC_HELMETS" to listOf("CARVED_PUMPKIN"))))
        assertEquals("CHESTPLATE", Applicability.armorPiece("IRON_CHESTPLATE"))
        assertNull(Applicability.armorPiece("ELYTRA"))
    }
}
