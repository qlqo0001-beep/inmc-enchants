package com.inmc.enchants.set

import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.enchant.EnchantLevel
import com.inmc.enchants.enchant.EnchantSettings
import com.inmc.enchants.engine.Trigger
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * 커스텀아이템 세트 한 단계(몇 벌)에 붙은 **인첸트 효과**. 사용자 결정(2026-09-25): 세트는 커스텀아이템이 관리하고, 효과는 인첸트
 * 엔진이 돌린다.
 *
 * 커스텀아이템은 이걸 트리([toTree])로 들고만 있고 뜻을 모른다. 세트를 세어 core 로 넘기면
 * ([kr.inmc.core.integration.CustomItemHook.SetEffects]) [SetService] 가 여기서 정의를 만들어 인첸트와 같은 길(조건·대기·확률)로
 * 태운다. 모양은 옛 `sets.yml` 의 세트와 같다 — `events`(발동 조건 → 인첸트 레벨) · `equipped` · `unequipped` · `disabled-worlds`.
 */
data class BonusEffects(
    val events: Map<Trigger, EnchantLevel> = emptyMap(),
    /** 이 단계가 붙을 때·떨어질 때 그 사람에게 보내는 글. */
    val equipped: List<String> = emptyList(),
    val unequipped: List<String> = emptyList(),
    val disabledWorlds: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = events.isEmpty() && equipped.isEmpty() && unequipped.isEmpty() && disabledWorlds.isEmpty()

    fun toTree(): Map<String, Any?> {
        val yaml = YamlConfiguration()
        if (equipped.isNotEmpty()) yaml.set(EQUIPPED, equipped)
        if (unequipped.isNotEmpty()) yaml.set(UNEQUIPPED, unequipped)
        if (disabledWorlds.isNotEmpty()) yaml.set(WORLDS, disabledWorlds)
        if (events.isNotEmpty()) {
            val node = yaml.createSection(EVENTS)
            for ((trigger, level) in events) level.save(node.createSection(trigger.name))
        }
        return tree(yaml)
    }

    /**
     * 엔진이 도는 모양 — 발동 조건마다 인첸트 정의 하나(확률·대기가 발동 조건마다 따로이기 때문이다). id 는 `<prefix>:<발동조건>` 이라
     * 재사용 대기가 이 id 로 잡힌다. 레지스트리에 넣지 않는다(도감·부여서에 나오면 안 된다).
     */
    fun definitions(prefix: String, name: String): List<EnchantDefinition> =
        events.map { (trigger, level) ->
            EnchantDefinition(
                id = prefix + ":" + trigger.name.lowercase(),
                display = name,
                description = emptyList(),
                appliesTo = "",
                triggers = listOf(trigger),
                group = "",
                applies = emptyList(),
                settings = EnchantSettings(disabledWorlds = disabledWorlds),
                levels = mapOf(1 to level),
            )
        }

    /** 로어·목록 한 줄 — 발동 조건 이름들. 효과가 없으면 빈 글자. */
    fun describe(): String = if (events.isEmpty()) "" else "<light_purple>" + events.keys.joinToString(" · ") { it.label } + "</light_purple>"

    companion object {
        const val EVENTS = "events"
        const val EQUIPPED = "equipped"
        const val UNEQUIPPED = "unequipped"
        const val WORLDS = "disabled-worlds"

        /** 트리를 읽는다. 못 읽은 발동 조건은 [problem] 으로 알리고 건너뛴다. */
        fun of(tree: Map<String, Any?>, problem: (String) -> Unit = {}): BonusEffects {
            if (tree.isEmpty()) return BonusEffects()
            val section = YamlConfiguration().createSection("effects", tree)
            return BonusEffects(
                events = loadEvents("세트 효과", section, problem),
                equipped = section.getStringList(EQUIPPED),
                unequipped = section.getStringList(UNEQUIPPED),
                disabledWorlds = section.getStringList(WORLDS),
            )
        }

        private fun tree(section: ConfigurationSection): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for (key in section.getKeys(false)) {
                val value = section.get(key)
                out[key] = if (value is ConfigurationSection) tree(value) else value
            }
            return out
        }
    }
}

/** `events:` 아래 발동 조건 → 인첸트 레벨. 모르는 발동 조건은 [problem] 으로 알리고 건너뛴다. */
internal fun loadEvents(owner: String, section: ConfigurationSection, problem: (String) -> Unit): Map<Trigger, EnchantLevel> {
    val node = section.getConfigurationSection(BonusEffects.EVENTS) ?: return emptyMap()
    val out = LinkedHashMap<Trigger, EnchantLevel>()
    for (key in node.getKeys(false)) {
        val trigger = Trigger.entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
        val entry = node.getConfigurationSection(key)
        if (trigger == null || entry == null) {
            problem("$owner: 발동 조건을 읽을 수 없습니다($key)")
            continue
        }
        out[trigger] = EnchantLevel.load(entry) { problem("$owner/$key: $it") }
    }
    return out
}
