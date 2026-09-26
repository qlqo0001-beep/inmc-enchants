package com.inmc.enchants

import com.inmc.enchants.enchant.EnchantSettings
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 배포 인첸트가 **설명대로** 동작하는지 — 사용자 결정(2026-09-25): 설명대로 작동하게, 조건 없이 말하는 것은 100%,
 * 같은 계열은 확률 → 확정 → 상위로 진화.
 *
 * 설명과 수치가 어긋나도 오류는 안 난다. "언제나 제련" 이 73% 였고 아무도 몰랐다.
 */
class DescriptionContractTest {

    private val yaml: YamlConfiguration by lazy {
        val stream = javaClass.classLoader.getResourceAsStream("enchantments.yml")
        assertNotNull(stream)
        stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
    }

    private fun enchants(): List<Pair<String, ConfigurationSection>> =
        yaml.getKeys(false).mapNotNull { id -> yaml.getConfigurationSection(id)?.let { id to it } }

    private fun levels(section: ConfigurationSection): List<Pair<Int, ConfigurationSection>> {
        val node = section.getConfigurationSection("levels") ?: return emptyList()
        return node.getKeys(false).mapNotNull { key -> node.getConfigurationSection(key)?.let { key.toInt() to it } }.sortedBy { it.first }
    }

    /** 확률과 상관없는 연출 줄. 연출의 확률은 설명이 말하지 않아도 된다. */
    private val cosmetic = setOf("PARTICLE", "PLAY_SOUND", "PLAY_SOUND_OUTLOUD", "MESSAGE", "ACTION_BAR", "TITLE", "SUBTITLE", "FIREWORK", "WAIT", "BLOOD", "CACTUS")

    private fun effectName(line: String) = line.trim().substringBefore(':').substringBefore(' ').uppercase()

    @Test
    fun `설명에 확률이 없으면 언제나 발동한다`() {
        val bad = enchants().filter { (_, section) -> "확률" !in section.getString("description").orEmpty() }.mapNotNull { (id, section) ->
            val broken = levels(section).firstOrNull { (_, level) ->
                level.getDouble("chance", 100.0) < 100.0 ||
                    level.getStringList("effects").any { "<chance>" in it && effectName(it) !in cosmetic }
            }
            broken?.let { id + " " + it.first + "레벨" }
        }
        assertTrue(bad.isEmpty(), "설명은 조건 없이 말하는데 확률이 걸렸다: $bad")
    }

    @Test
    fun `언제나·매번이라고 하면 100퍼센트다`() {
        val words = Regex("언제나|항상|매번|마다")
        val bad = enchants().filter { (_, section) -> words.containsMatchIn(section.getString("description").orEmpty()) }
            .filter { (_, section) -> levels(section).any { (_, level) -> level.getDouble("chance", 100.0) < 100.0 } }
            .map { it.first }
        assertTrue(bad.isEmpty(), "언제나라고 하는데 확률이 걸렸다: $bad")
    }

    @Test
    fun `진화 사슬 — 아래 단계의 최대 레벨이 있어야 붙고, 붙으면 아래 단계는 지워지며, 설명이 그걸 말한다`() {
        val roman = listOf("", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X")
        val particle = mapOf("I" to "이", "II" to "가", "III" to "이", "IV" to "가", "V" to "가", "VI" to "이", "VII" to "이", "VIII" to "이", "IX" to "가", "X" to "이")
        val problems = ArrayList<String>()
        for ((id, section) in enchants()) {
            val settings = section.getConfigurationSection("settings") ?: continue
            for (need in settings.getStringList("required-enchants")) {
                val lower = need.substringBefore(':')
                val level = need.substringAfter(':', "1").toInt()
                val target = yaml.getConfigurationSection(lower)
                if (target == null) {
                    problems += "$id → 없는 인첸트 $lower"
                    continue
                }
                val max = levels(target).maxOf { it.first }
                if (level != max) problems += "$id 는 $lower $level 을 요구하는데 최대가 $max"
                if (lower !in settings.getStringList("removed-enchants")) problems += "$id 가 붙을 때 $lower 를 지우지 않는다"
                val name = target.getString("display").orEmpty().replace("%group-color%", "")
                val numeral = roman[level]
                val phrase = "$name $numeral ${particle.getValue(numeral)} 있어야 붙는다"
                if (phrase !in section.getString("description").orEmpty()) problems += "$id 설명에 '$phrase' 가 없다"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `폭파 계열 — 커스텀아이템 곡괭이 전용, 뽑기는 1레벨만, 크기는 캔 면 기준 3x3x1부터`() {
        for (id in listOf("detonate", "atomicdetonate")) {
            val section = yaml.getConfigurationSection(id)!!
            val settings = EnchantSettings.load(section.getConfigurationSection("settings"))
            assertTrue(settings.customItemsOnly, "$id 는 커스텀아이템 곡괭이 전용이어야 한다")
            assertEquals(1, settings.drawMaxLevel, id)
            assertNotNull(settings.bookSuccess, id)
            assertEquals("HEROIC", section.getString("group"), id)
            assertEquals(3, levels(section).size, id)
        }
        val sizes = levels(yaml.getConfigurationSection("detonate")!!).map { (_, level) ->
            Regex("radiuscustom=(\\d+x\\d+x\\d+),facing=true").find(level.getStringList("effects").first())?.groupValues?.get(1)
        }
        assertEquals(listOf("3x3x1", "3x3x2", "3x3x3"), sizes)
        assertEquals(listOf("blastmining:3"), yaml.getStringList("detonate.settings.required-enchants"))
        assertTrue(!yaml.getBoolean("blastmining.settings.custom-items-only", false), "발파 채굴은 일반 곡괭이에도 붙는다")
    }

    @Test
    fun `새 설정(커스텀아이템 전용·뽑기 최대 레벨·부여서 성공률)이 저장해도 그대로 읽힌다`() {
        val original = EnchantSettings(customItemsOnly = true, drawMaxLevel = 1, bookSuccess = 5..15, requiredEnchants = listOf("blastmining:3"))
        val written = YamlConfiguration()
        original.save(written)
        val reread = YamlConfiguration().apply { loadFromString(written.saveToString()) }
        assertEquals(original, EnchantSettings.load(reread.getConfigurationSection("settings")))
    }
}
