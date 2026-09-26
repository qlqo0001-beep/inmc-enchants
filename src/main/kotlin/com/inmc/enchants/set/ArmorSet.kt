package com.inmc.enchants.set

import com.inmc.enchants.enchant.EnchantLevel
import com.inmc.enchants.engine.Trigger
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.EquipmentSlot

// 옛 `sets.yml`(인첸트가 세트를 직접 관리하던 때)의 모양. 세트는 커스텀아이템으로 옮겼고(사용자 결정 2026-09-25),
// 여기 남은 것은 그 파일을 한 번 읽어 옮기는 데([SetMigration]) 필요한 만큼이다.

/** 세트의 네 부위. */
enum class Piece(val id: String, val label: String, val slot: EquipmentSlot, val suffix: String) {
    HELMET("helmet", "투구", EquipmentSlot.HEAD, "_HELMET"),
    CHESTPLATE("chestplate", "흉갑", EquipmentSlot.CHEST, "_CHESTPLATE"),
    LEGGINGS("leggings", "각반", EquipmentSlot.LEGS, "_LEGGINGS"),
    BOOTS("boots", "장화", EquipmentSlot.FEET, "_BOOTS"),
}

/** 세트 방어구의 재료. 재질 이름은 `재료 + 부위 접미사` 다(`DIAMOND_HELMET`). */
enum class ArmorMaterial(val label: String) {
    LEATHER("가죽"), CHAINMAIL("사슬"), IRON("철"), GOLDEN("금"), COPPER("구리"), DIAMOND("다이아몬드"), NETHERITE("네더라이트");

    companion object {
        /** AE 는 `GOLD`·`CHAIN` 이라고 적는다. 그 파일을 붙여 넣어도 읽히게 받는다. */
        fun of(raw: String?): ArmorMaterial? = when (val name = raw?.trim()?.uppercase()) {
            null, "" -> null
            "GOLD" -> GOLDEN
            "CHAIN" -> CHAINMAIL
            else -> entries.firstOrNull { it.name == name }
        }
    }
}

/**
 * 세트 부위나 세트 무기 하나의 겉모습과 처음 붙는 인첸트.
 *
 * @param enchants `id:레벨` 또는 `id:최소-최대`. 바닐라·커스텀 인첸트 둘 다. AE 의 `id:%1-3%` 도 받는다.
 */
data class Gear(
    val name: String = "",
    val lore: List<String> = emptyList(),
    val customModelData: Int = 0,
    val flags: List<String> = emptyList(),
    val enchants: List<String> = emptyList(),
) {
    companion object {
        fun load(section: ConfigurationSection?): Gear {
            if (section == null) return Gear()
            return Gear(
                name = section.getString("name").orEmpty(),
                lore = section.getStringList("lore"),
                customModelData = section.getInt("custom-model-data", section.getInt("customModelData", 0)).coerceAtLeast(0),
                flags = (section.getStringList("flags").ifEmpty { section.getStringList("itemFlags") }).map { it.uppercase() },
                enchants = section.getStringList("enchants"),
            )
        }

        /**
         * 같은 이름의 커스텀 인첸트가 있어도 **바닐라**를 뜻할 때 붙이는 앞머리. 이름이 같으면 커스텀이 먼저라서
         * (`protection` 은 AE 의 "가호"다) 바닐라 보호 IV 를 원하면 `minecraft:protection:4` 로 적는다.
         */
        const val VANILLA = "minecraft:"

        /** `id:3` · `id:1-3` · `id:%1-3%` · `minecraft:id:3` → (id, 범위). 못 읽으면 null. */
        fun roll(raw: String): Pair<String, IntRange>? {
            val text = raw.trim()
            val namespace = if (text.startsWith(VANILLA, ignoreCase = true)) VANILLA else ""
            val body = text.substring(namespace.length)
            val id = body.substringBefore(':').trim().lowercase().ifEmpty { return null }
            val level = body.substringAfter(':', "1").trim().removePrefix("%").removeSuffix("%")
            val low = level.substringBefore('-').trim().toIntOrNull() ?: return null
            val high = if ('-' in level) level.substringAfter('-').trim().toIntOrNull() ?: return null else low
            if (low < 1 || high < low) return null
            return namespace + id to low..high
        }
    }
}

/** 옛 방어구 세트. 네 부위를 **모두** 입으면 [events] 가 돌았다. */
data class ArmorSet(
    val id: String,
    val name: String,
    val material: ArmorMaterial,
    /** 가죽일 때만. `#RRGGBB`. */
    val color: String = "",
    val equipped: List<String> = emptyList(),
    val unequipped: List<String> = emptyList(),
    val disabledWorlds: List<String> = emptyList(),
    val pieces: Map<Piece, Gear> = emptyMap(),
    val events: Map<Trigger, EnchantLevel> = emptyMap(),
) {
    fun gear(piece: Piece): Gear = pieces[piece] ?: Gear()

    companion object {
        fun load(id: String, section: ConfigurationSection, problem: (String) -> Unit): ArmorSet? {
            val material = ArmorMaterial.of(section.getString("material")) ?: run {
                problem("$id: 재료를 읽을 수 없습니다(${section.getString("material")})")
                return null
            }
            val node = section.getConfigurationSection("pieces") ?: section.getConfigurationSection("items")
            return ArmorSet(
                id = id,
                name = section.getString("name") ?: id,
                material = material,
                color = section.getString("color").orEmpty(),
                equipped = section.getStringList("equipped").ifEmpty { section.getStringList("settings.equipped") },
                unequipped = section.getStringList("unequipped").ifEmpty { section.getStringList("settings.unequipped") },
                disabledWorlds = section.getStringList("disabled-worlds"),
                pieces = Piece.entries.associateWith { Gear.load(node?.getConfigurationSection(it.id)) },
                events = loadEvents(id, section, problem),
            )
        }
    }
}

/** 옛 세트 무기. 손에 들고 있으면 [events] 가 돌았다. [requiredSet] 이 있으면 그 세트를 다 입었을 때만. */
data class SetWeapon(
    val id: String,
    val material: String,
    val requiredSet: String = "",
    val gear: Gear = Gear(),
    val events: Map<Trigger, EnchantLevel> = emptyMap(),
) {
    companion object {
        fun load(id: String, section: ConfigurationSection, problem: (String) -> Unit): SetWeapon? {
            val material = section.getString("material")?.trim()?.uppercase().orEmpty()
            if (material.isEmpty()) {
                problem("$id: 재질이 없습니다")
                return null
            }
            return SetWeapon(
                id = id,
                material = material,
                requiredSet = (section.getString("require-set") ?: section.getString("requireSet")).orEmpty().trim().lowercase(),
                gear = Gear.load(section),
                events = loadEvents(id, section, problem),
            )
        }
    }
}
