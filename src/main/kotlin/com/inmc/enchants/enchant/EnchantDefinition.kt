package com.inmc.enchants.enchant

import com.inmc.enchants.engine.Condition
import com.inmc.enchants.engine.EffectLine
import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.engine.Trigger
import org.bukkit.configuration.ConfigurationSection

/**
 * 인첸트 하나. **모양은 AE `enchantments.yml` 과 같다** — AE 설정을 그대로 읽을 수 있고,
 * 편집 화면이 고친 것도 같은 모양으로 저장된다.
 *
 * ```yaml
 * lifesteal:
 *   display: '%group-color%흡혈'
 *   description: 공격할 때 확률로 체력을 빼앗는다.
 *   applies-to: 검
 *   type: ATTACK;ATTACK_MOB
 *   group: LEGENDARY
 *   applies: [ALL_SWORD]
 *   settings: { not-applyable-with: [vampire] }
 *   levels:
 *     '1': { chance: 5, cooldown: 4, effects: ['STEAL_HEALTH:2 @Victim'] }
 * ```
 */
data class EnchantDefinition(
    /** 소문자 라틴 문자·숫자·밑줄. 명령어와 아이템 PDC 가 이 값을 쓴다. */
    val id: String,
    val display: String,
    val description: List<String>,
    /** 화면에만 쓰는 "무엇에 붙나" 설명. 실제 제한은 [applies]. */
    val appliesTo: String,
    val triggers: List<Trigger>,
    val group: String,
    val applies: List<String>,
    val settings: EnchantSettings = EnchantSettings(),
    val levels: Map<Int, EnchantLevel>,
) {

    val maxLevel: Int get() = levels.keys.maxOrNull() ?: 0

    fun level(level: Int): EnchantLevel? = levels[level] ?: levels.entries.filter { it.key <= level }.maxByOrNull { it.key }?.value

    /** 레벨별 설명이 있으면 그것, 없으면 공통 설명. */
    fun descriptionFor(level: Int): List<String> = levels[level]?.description?.takeIf { it.isNotEmpty() } ?: description

    fun save(section: ConfigurationSection) {
        section.set("display", display)
        section.set("description", description.joinToString("\n"))
        section.set("applies-to", appliesTo)
        section.set("type", triggers.joinToString(";") { it.name })
        section.set("group", group)
        section.set("applies", applies)
        settings.save(section)
        val node = section.createSection("levels")
        for ((number, level) in levels.toSortedMap()) level.save(node.createSection(number.toString()))
    }

    companion object {

        val ID = Regex("^[a-z0-9_]{1,40}$")

        /**
         * 읽는다. 문제는 [problems] 에 모으고, **인첸트를 쓸 수 없을 만큼 망가졌을 때만** null 이다.
         * 효과 줄 하나가 틀렸다고 인첸트 전체를 버리면, 관리자가 그 인첸트를 편집 화면에서 찾을
         * 수조차 없게 된다. 틀린 줄만 빼고 이유를 남긴다.
         */
        fun load(id: String, section: ConfigurationSection, problems: MutableList<String>): EnchantDefinition? {
            if (!ID.matches(id)) {
                problems += "$id: 이름은 소문자 영문·숫자·밑줄만 됩니다"
                return null
            }
            val unknown = mutableListOf<String>()
            val triggers = Trigger.parseList(section.getString("type").orEmpty(), unknown)
            for (name in unknown) problems += "$id: 모르는 발동 조건 $name"

            val levels = sortedMapOf<Int, EnchantLevel>()
            val levelNode = section.getConfigurationSection("levels")
            if (levelNode == null) {
                problems += "$id: levels 가 없습니다"
                return null
            }
            for (key in levelNode.getKeys(false)) {
                val number = key.toIntOrNull()
                val node = levelNode.getConfigurationSection(key)
                if (number == null || number < 1 || node == null) {
                    problems += "$id: 레벨 '$key' 을(를) 읽을 수 없습니다"
                    continue
                }
                levels[number] = EnchantLevel.load(node) { problems += "$id $number: $it" }
            }
            if (levels.isEmpty()) {
                problems += "$id: 쓸 수 있는 레벨이 없습니다"
                return null
            }

            return EnchantDefinition(
                id = id,
                display = section.getString("display") ?: id,
                description = lines(section, "description"),
                appliesTo = section.getString("applies-to").orEmpty(),
                triggers = triggers,
                group = section.getString("group")?.uppercase() ?: "SIMPLE",
                applies = section.getStringList("applies").map { it.trim().uppercase() }.filter { it.isNotEmpty() },
                settings = EnchantSettings.load(section.getConfigurationSection("settings")),
                levels = levels,
            )
        }

        /** 한 줄 문자열(`\n` 으로 줄바꿈)과 목록 둘 다 받는다. AE 가 둘을 섞어 쓴다. */
        fun lines(section: ConfigurationSection, key: String): List<String> {
            if (section.isList(key)) return section.getStringList(key)
            val text = section.getString(key) ?: return emptyList()
            return text.replace("\\n", "\n").split('\n').map { it.trimEnd() }.filter { it.isNotEmpty() }
        }
    }
}

/** 인첸트 전체에 걸린 규칙. AE `settings` 와 같다. */
data class EnchantSettings(
    /** 이미 있어야 붙는다. `이름` 또는 `이름:레벨`. */
    val requiredEnchants: List<String> = emptyList(),
    /** 이것이 있으면 못 붙는다. */
    val notApplyableWith: List<String> = emptyList(),
    /** 붙는 순간 지워진다. required 와 짝지어 상위 인첸트를 만든다. */
    val removedEnchants: List<String> = emptyList(),
    /** false 면 블랙 스크롤로 못 뗀다. */
    val removeable: Boolean = true,
    /** true 면 인챈터에서 안 나온다. */
    val disableInEnchanter: Boolean = false,
    /** 발동하지 않는 월드(대소문자 구분). */
    val disabledWorlds: List<String> = emptyList(),
    /** 발동할 때 액션바에 이름을 띄운다(1초 쿨다운). */
    val showActionBar: Boolean = false,
    /**
     * 커스텀아이템(`inmc:…` 등 커스텀 아이템 플러그인의 아이템)에만 붙는다. 같은 재질의 평범한 곡괭이에는 부여서로 못 붙인다 —
     * 상위 곡괭이 전용 옵션(폭파)을 위한 것이다(사용자 결정 2026-09-25). 커스텀아이템 정의에 직접 넣는 것은 막지 않는다.
     */
    val customItemsOnly: Boolean = false,
    /** 뽑기(인챈터·상자·사서·부여대)에서 나오는 가장 높은 레벨. 0 이면 제한 없음. 그 위는 연금술사에서 합쳐 올린다. */
    val drawMaxLevel: Int = 0,
    /** 뽑기로 나온 부여서의 성공률 범위(설정의 기본 범위 대신). null 이면 기본. */
    val bookSuccess: IntRange? = null,
) {

    fun save(section: ConfigurationSection) {
        val node = section.createSection("settings")
        if (requiredEnchants.isNotEmpty()) node.set("required-enchants", requiredEnchants)
        if (notApplyableWith.isNotEmpty()) node.set("not-applyable-with", notApplyableWith)
        if (removedEnchants.isNotEmpty()) node.set("removed-enchants", removedEnchants)
        if (!removeable) node.set("removeable", false)
        if (disableInEnchanter) node.set("disable-in-enchanter", true)
        if (disabledWorlds.isNotEmpty()) node.set("disabled-worlds", disabledWorlds)
        if (showActionBar) node.set("showActionBar", true)
        if (customItemsOnly) node.set("custom-items-only", true)
        if (drawMaxLevel > 0) node.set("draw-max-level", drawMaxLevel)
        bookSuccess?.let { node.set("book-success", "${it.first}-${it.last}") }
        if (node.getKeys(false).isEmpty()) section.set("settings", null)
    }

    companion object {
        fun load(section: ConfigurationSection?): EnchantSettings {
            if (section == null) return EnchantSettings()
            return EnchantSettings(
                requiredEnchants = section.getStringList("required-enchants").map { it.lowercase().trim() },
                notApplyableWith = section.getStringList("not-applyable-with").map { it.lowercase().trim() },
                removedEnchants = section.getStringList("removed-enchants").map { it.lowercase().trim() },
                removeable = section.getBoolean("removeable", true),
                disableInEnchanter = section.getBoolean("disable-in-enchanter", false),
                disabledWorlds = section.getStringList("disabled-worlds"),
                showActionBar = section.getBoolean("showActionBar", false),
                customItemsOnly = section.getBoolean("custom-items-only", false),
                drawMaxLevel = section.getInt("draw-max-level", 0).coerceAtLeast(0),
                bookSuccess = section.getString("book-success")?.let(::percentRange),
            )
        }
    }
}

/** 레벨 하나. */
data class EnchantLevel(
    /** 0~100. */
    val chance: Double = 100.0,
    /** 초. */
    val cooldown: Double = 0.0,
    /** 발동마다 드는 영혼. */
    val souls: Int = 0,
    /** REPEATING 의 주기(초). */
    val time: Int = 0,
    /** REPEATING 이 착용 즉시 한 번 도는가. */
    val instantApply: Boolean = true,
    /** COMMAND 발동 조건이 기다리는 명령어. */
    val command: String? = null,
    val description: List<String> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val effects: List<EffectLine> = emptyList(),
    val settings: LevelSettings = LevelSettings(),
    /** 레벨별 부여서 모양. 비운 칸은 기본값을 쓴다. */
    val book: BookOverride? = null,
    /**
     * **다른 플러그인이 읽는 값.** 이 플러그인은 뜻을 모른다 — 커스텀아이템의 `data` 층과 같은 자리다.
     * `fishing.reel-power: 5` 를 적으면 inmc-fishing 이 이 인첸트가 붙은 낚싯대에 5 를 더한다.
     * 한 아이템의 여러 인첸트가 같은 열쇠를 주면 **숫자는 더해진다**(core `CustomEnchantHook.data`).
     */
    val data: Map<String, String> = emptyMap(),
) {

    fun save(section: ConfigurationSection) {
        if (data.isNotEmpty()) {
            val node = section.createSection("data")
            for ((key, value) in data) node.set(key, value)
        }
        if (chance != 100.0) section.set("chance", chance)
        if (cooldown != 0.0) section.set("cooldown", cooldown)
        if (souls > 0) section.set("souls", souls)
        if (time > 0) section.set("time", time)
        if (!instantApply) section.set("instantApply", false)
        command?.let { section.set("command", it) }
        if (description.isNotEmpty()) section.set("description", description.joinToString("\n"))
        if (conditions.isNotEmpty()) section.set("conditions", conditions.map { it.raw })
        section.set("effects", effects.map { it.raw })
        settings.save(section)
        book?.save(section)
    }

    companion object {
        fun load(section: ConfigurationSection, problem: (String) -> Unit): EnchantLevel {
            val conditions = section.getStringList("conditions").mapNotNull { raw ->
                Condition.parse(raw).also { if (it == null) problem("조건을 읽을 수 없습니다: $raw") }
            }
            val effects = section.getStringList("effects").mapNotNull { raw ->
                val parsed = EffectLine.parse(raw, EffectSpecs::shape)
                if (parsed.error != null) problem(parsed.error + " ← " + raw)
                parsed.line
            }
            return EnchantLevel(
                chance = section.getDouble("chance", 100.0).coerceIn(0.0, 100.0),
                cooldown = section.getDouble("cooldown", 0.0).coerceAtLeast(0.0),
                souls = section.getInt("souls", 0).coerceAtLeast(0),
                time = section.getInt("time", 0).coerceAtLeast(0),
                instantApply = section.getBoolean("instantApply", true),
                command = section.getString("command"),
                description = EnchantDefinition.lines(section, "description"),
                conditions = conditions,
                effects = effects,
                settings = LevelSettings.load(section.getConfigurationSection("settings")),
                book = BookOverride.load(section.getConfigurationSection("item")),
                data = readData(section.getConfigurationSection("data")),
            )
        }

        /**
         * `fishing.reel-power` 처럼 점이 든 열쇠는 YAML 이 **경로로** 읽는다(지뢰 1번). 잎까지 내려가
         * 전체 경로를 열쇠로 되살린다.
         */
        private fun readData(section: ConfigurationSection?): Map<String, String> {
            if (section == null) return emptyMap()
            val out = LinkedHashMap<String, String>()
            for (path in section.getKeys(true)) {
                if (section.isConfigurationSection(path)) continue
                out[path] = section.getString(path).orEmpty()
            }
            return out
        }
    }
}

/** 레벨마다의 효과 설정. AE 는 이것을 **레벨 아래에** 둔다. */
data class LevelSettings(
    val whitelist: List<String> = emptyList(),
    val blacklist: List<String> = emptyList(),
    val worldBlacklist: List<String> = emptyList(),
) {
    fun allowsMaterial(material: String): Boolean {
        val m = material.uppercase()
        if (whitelist.isNotEmpty() && whitelist.none { it.equals(m, ignoreCase = true) }) return false
        return blacklist.none { it.equals(m, ignoreCase = true) }
    }

    fun save(section: ConfigurationSection) {
        if (whitelist.isEmpty() && blacklist.isEmpty() && worldBlacklist.isEmpty()) return
        val node = section.createSection("settings")
        if (whitelist.isNotEmpty()) node.set("whitelist", whitelist)
        if (blacklist.isNotEmpty()) node.set("blacklist", blacklist)
        if (worldBlacklist.isNotEmpty()) node.set("worldBlacklist", worldBlacklist)
    }

    companion object {
        fun load(section: ConfigurationSection?): LevelSettings {
            if (section == null) return LevelSettings()
            return LevelSettings(
                whitelist = section.getStringList("whitelist").map { it.uppercase() },
                blacklist = section.getStringList("blacklist").map { it.uppercase() },
                worldBlacklist = section.getStringList("worldBlacklist"),
            )
        }
    }
}

/** 레벨별 부여서 모양 덮어쓰기(AE per-level books). */
data class BookOverride(
    val material: String? = null,
    val customModelData: Int? = null,
    val name: String? = null,
    val lore: List<String> = emptyList(),
) {
    fun save(section: ConfigurationSection) {
        val node = section.createSection("item")
        material?.let { node.set("type", it) }
        customModelData?.let { node.set("custom-model-data", it) }
        name?.let { node.set("name", it) }
        if (lore.isNotEmpty()) node.set("lore", lore.joinToString("\n"))
    }

    companion object {
        fun load(section: ConfigurationSection?): BookOverride? {
            if (section == null) return null
            return BookOverride(
                material = section.getString("type"),
                customModelData = if (section.contains("custom-model-data")) section.getInt("custom-model-data") else null,
                name = section.getString("name"),
                lore = EnchantDefinition.lines(section, "lore"),
            )
        }
    }
}

/** `5-15` · `10` → 범위(0~100). 못 읽으면 null. 규칙 화면의 입력도 이걸로 읽는다. */
fun percentRange(raw: String): IntRange? {
    val text = raw.trim()
    val low = text.substringBefore('-').trim().toIntOrNull() ?: return null
    val high = if ('-' in text) text.substringAfter('-').trim().toIntOrNull() ?: return null else low
    return low.coerceIn(0, 100)..high.coerceIn(low.coerceIn(0, 100), 100)
}
