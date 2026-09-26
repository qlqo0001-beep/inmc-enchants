package com.inmc.enchants

import com.inmc.enchants.enchant.EnchantLevel
import com.inmc.enchants.engine.EffectLine
import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.set.ArmorMaterial
import com.inmc.enchants.set.ArmorSet
import com.inmc.enchants.set.BonusEffects
import com.inmc.enchants.set.Gear
import com.inmc.enchants.set.Piece
import com.inmc.enchants.set.SetMigration
import com.inmc.enchants.set.SetWeapon
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 옛 방어구 세트·세트 무기(`sets.yml`)와 그것을 커스텀아이템 세트로 옮기는 계획, 그리고 커스텀아이템 세트가 들고 다니는
 * 인첸트 효과 트리([BonusEffects]).
 */
class SetTest {

    private fun resource(path: String): YamlConfiguration {
        val stream = javaClass.classLoader.getResourceAsStream(path)
        assertNotNull(stream, "리소스를 찾을 수 없습니다: $path")
        return stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
    }

    private fun deployedSets(problems: MutableList<String>): List<ArmorSet> {
        val node = resource("sets.yml").getConfigurationSection("sets")!!
        return node.getKeys(false).mapNotNull { ArmorSet.load(it, node.getConfigurationSection(it)!!) { p -> problems += p } }
    }

    private fun deployedWeapons(problems: MutableList<String>): List<SetWeapon> {
        val node = resource("sets.yml").getConfigurationSection("weapons")!!
        return node.getKeys(false).mapNotNull { SetWeapon.load(it, node.getConfigurationSection(it)!!) { p -> problems += p } }
    }

    @Test
    fun `인첸트 줄의 레벨은 하나이거나 범위다`() {
        assertEquals("lifesteal" to 3..3, Gear.roll("lifesteal:3"))
        assertEquals("lifesteal" to 1..3, Gear.roll("lifesteal:1-3"))
        assertEquals("lifesteal" to 1..3, Gear.roll("Lifesteal:%1-3%"), "AE 모양도 받는다")
        assertEquals("lifesteal" to 1..1, Gear.roll("lifesteal"), "레벨을 안 적으면 1")
        assertNull(Gear.roll("lifesteal:0"))
        assertNull(Gear.roll("lifesteal:3-1"))
        assertNull(Gear.roll("lifesteal:셋"))
        assertEquals("minecraft:protection" to 4..4, Gear.roll("minecraft:protection:4"), "바닐라를 분명히 적은 것")
        assertEquals("minecraft:protection" to 1..1, Gear.roll("MINECRAFT:protection"))
    }

    @Test
    fun `배포 세트가 문제 없이 전부 읽힌다`() {
        val problems = ArrayList<String>()
        val sets = deployedSets(problems)
        val weapons = deployedWeapons(problems)
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
        assertEquals(6, sets.size)
        assertEquals(4, weapons.size)
        for (set in sets) {
            assertTrue(set.events.isNotEmpty(), set.id + " 에 효과가 없다")
            for (piece in Piece.entries) assertTrue(set.gear(piece).name.isNotBlank(), set.id + " 의 " + piece.label + " 에 이름이 없다")
        }
    }

    @Test
    fun `배포 세트의 인첸트 줄은 있는 인첸트를 가리킨다`() {
        // 없는 인첸트는 조용히 안 붙는다. 바닐라는 서버 없이 레지스트리를 못 보므로 쓰는 것만 적어 둔다.
        val vanilla = setOf("protection", "unbreaking", "sharpness", "fire_aspect", "lure", "luck_of_the_sea", "respiration", "depth_strider", "feather_falling")
        val custom = resource("enchantments.yml").getKeys(false)
        val problems = ArrayList<String>()
        val entries = deployedSets(problems).flatMap { s -> Piece.entries.flatMap { s.gear(it).enchants } } +
            deployedWeapons(problems).flatMap { it.gear.enchants }
        val bad = entries.filter { raw -> Gear.roll(raw)?.first.let { it == null || (it !in custom && it.removePrefix(Gear.VANILLA) !in vanilla) } }
        // 커스텀과 이름이 같은 바닐라는 앞머리 없이 적으면 커스텀이 붙는다(protection → "가호").
        val shadowed = entries.mapNotNull { Gear.roll(it)?.first }.filter { it in custom && it in vanilla }
        assertTrue(shadowed.isEmpty(), "커스텀에 가려지는 바닐라 인첸트(minecraft: 를 붙이세요): $shadowed")
        assertTrue(bad.isEmpty(), "읽을 수 없거나 없는 인첸트: $bad")
    }

    @Test
    fun `배포 무기가 요구하는 세트는 있다`() {
        val problems = ArrayList<String>()
        val ids = deployedSets(problems).map { it.id }.toSet()
        val bad = deployedWeapons(problems).filter { it.requiredSet.isNotEmpty() && it.requiredSet !in ids }.map { it.id }
        assertTrue(bad.isEmpty(), "없는 세트를 요구하는 무기: $bad")
    }

    @Test
    fun `인첸트 효과 트리가 커스텀아이템 파일을 거쳐도 그대로다`() {
        val line = EffectLine.parse("POTION:SPEED:0", EffectSpecs::shape).line!!
        val original = BonusEffects(
            events = mapOf(Trigger.EFFECT_STATIC to EnchantLevel(effects = listOf(line)), Trigger.ATTACK to EnchantLevel(chance = 30.0, cooldown = 2.0, effects = listOf(line))),
            equipped = listOf("입었다"), unequipped = listOf("벗었다"), disabledWorlds = listOf("world_nether"),
        )
        // 커스텀아이템이 하는 그대로: 트리를 섹션으로 적고, 파일로 저장했다 다시 읽고, 섹션을 트리로.
        val written = YamlConfiguration()
        written.createSection("enchant-effects", original.toTree())
        val reread = YamlConfiguration().apply { loadFromString(written.saveToString()) }
        fun tree(section: org.bukkit.configuration.ConfigurationSection): Map<String, Any?> =
            section.getKeys(false).associateWith { key -> section.get(key).let { if (it is org.bukkit.configuration.ConfigurationSection) tree(it) else it } }
        assertEquals(original, BonusEffects.of(tree(reread.getConfigurationSection("enchant-effects")!!)) { error(it) })
    }

    @Test
    fun `빈 트리는 빈 효과이고 모르는 발동 조건은 알리고 건너뛴다`() {
        assertTrue(BonusEffects.of(emptyMap()).isEmpty)
        val problems = ArrayList<String>()
        val effects = BonusEffects.of(mapOf("events" to mapOf("ATTACK" to mapOf("effects" to listOf("INCREASE_DAMAGE:5")), "NOPE" to mapOf("effects" to listOf("x"))))) { problems += it }
        assertEquals(setOf(Trigger.ATTACK), effects.events.keys)
        assertEquals(1, problems.size)
    }

    @Test
    fun `AE 세트 파일 모양도 읽힌다`() {
        val yaml = YamlConfiguration().apply {
            loadFromString(
                """
                name: '&b&lYeti'
                material: GOLD
                settings:
                  equipped: ['on']
                  unequipped: ['off']
                items:
                  helmet:
                    name: 'Mask'
                    customModelData: 3
                    itemFlags: [HIDE_ENCHANTS]
                    enchants: ['protection:4', 'lifesteal:%1-3%']
                events:
                  ATTACK:
                    chance: 100
                    effects: ['INCREASE_DAMAGE:10']
                """.trimIndent(),
            )
        }
        val set = ArmorSet.load("yeti", yaml) { error(it) }!!
        assertEquals(ArmorMaterial.GOLDEN, set.material)
        assertEquals(listOf("on"), set.equipped)
        assertEquals(3, set.gear(Piece.HELMET).customModelData)
        assertEquals(listOf("HIDE_ENCHANTS"), set.gear(Piece.HELMET).flags)
        assertEquals(setOf(Trigger.ATTACK), set.events.keys)
    }

    @Test
    fun `발동 조건마다 따로 대기가 잡히는 정의가 된다`() {
        val effects = BonusEffects(events = mapOf(Trigger.ATTACK to EnchantLevel(), Trigger.DEFENSE to EnchantLevel()), disabledWorlds = listOf("w"))
        val definitions = effects.definitions("set:frost:4", "서리")
        assertEquals(listOf("set:frost:4:attack", "set:frost:4:defense"), definitions.map { it.id })
        assertTrue(definitions.all { it.triggers.size == 1 && it.level(1) != null && it.settings.disabledWorlds == listOf("w") })
    }

    @Test
    fun `옮기기 - 세트 효과는 4벌, 그 세트가 필요한 무기는 5벌에`() {
        val attack = mapOf(Trigger.ATTACK to EnchantLevel())
        val set = ArmorSet("frost", "서리", ArmorMaterial.DIAMOND, equipped = listOf("on"), events = mapOf(Trigger.DEFENSE to EnchantLevel()))
        val axe = SetWeapon("axe", "DIAMOND_AXE", requiredSet = "frost", events = attack)
        val plan = SetMigration.plan(listOf(set), listOf(axe)).single()
        assertEquals("frost", plan.id)
        assertEquals(listOf(axe), plan.weapons)
        assertEquals(setOf(4, 5), plan.effects.keys)
        assertEquals(setOf(Trigger.DEFENSE), plan.effects.getValue(4).events.keys)
        assertEquals(listOf("on"), plan.effects.getValue(4).equipped)
        assertEquals(attack, plan.effects.getValue(5).events)
    }

    @Test
    fun `옮기기 - 세트가 필요 없는 무기와 같은 세트의 두 번째 무기는 제 세트의 1벌로`() {
        val attack = mapOf(Trigger.ATTACK to EnchantLevel())
        val set = ArmorSet("frost", "서리", ArmorMaterial.DIAMOND)
        val first = SetWeapon("axe", "DIAMOND_AXE", requiredSet = "frost", events = attack)
        val second = SetWeapon("frost", "DIAMOND_SWORD", requiredSet = "frost", events = attack)
        val free = SetWeapon("wand", "STICK", events = attack)
        val plans = SetMigration.plan(listOf(set), listOf(first, second, free)).associateBy { it.id }
        assertEquals(setOf("frost", "frost_weapon", "wand"), plans.keys, "세트와 이름이 같은 무기는 _weapon 을 붙인다")
        assertEquals(setOf(1), plans.getValue("wand").effects.keys)
        assertTrue(plans.getValue("frost_weapon").lostRequirement)
        assertTrue(!plans.getValue("wand").lostRequirement)
        assertTrue(plans.getValue("frost").effects.keys.none { it == 4 }, "효과 없는 세트 단계는 만들지 않는다")
    }

    @Test
    fun `옮기기 - 배포 세트는 여섯 세트가 되고 무기 넷은 제 세트의 5벌에 든다`() {
        val problems = ArrayList<String>()
        val plans = SetMigration.plan(deployedSets(problems), deployedWeapons(problems))
        assertEquals(6, plans.size)
        assertEquals(4, plans.count { 5 in it.effects })
        assertTrue(plans.all { 4 in it.effects && it.armor != null })
    }

    @Test
    fun `재료가 없으면 읽지 않는다`() {
        val problems = ArrayList<String>()
        val yaml = YamlConfiguration().apply { set("name", "x"); set("material", "WOOD") }
        assertNull(ArmorSet.load("x", yaml) { problems += it })
        assertEquals(1, problems.size)
    }
}
