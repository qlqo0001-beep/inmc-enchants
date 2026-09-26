package com.inmc.enchants.engine

/**
 * 서버 없이도 확인할 수 있는 이름 목록.
 *
 * 물약 효과는 레지스트리에서 오므로 서버가 있어야 찾을 수 있다(`ARCHITECTURE.md` "서버 없이 못
 * 부르는 API"). 그런데 적재 검사와 편집 화면은 서버 없이 도는 테스트로 지켜야 하므로, 이름만
 * 여기 따로 둔다. 실제 효과 객체는 실행할 때 레지스트리에서 찾는다.
 */
object Names {

    /** 바닐라 물약 효과 키(소문자). */
    val POTIONS: List<String> = listOf(
        "speed", "slowness", "haste", "mining_fatigue", "strength", "instant_health",
        "instant_damage", "jump_boost", "nausea", "regeneration", "resistance", "fire_resistance",
        "water_breathing", "invisibility", "blindness", "night_vision", "hunger", "weakness",
        "poison", "wither", "health_boost", "absorption", "saturation", "glowing", "levitation",
        "luck", "unluck", "slow_falling", "conduit_power", "dolphins_grace", "bad_omen",
        "hero_of_the_village", "darkness", "trial_omen", "raid_omen", "wind_charged", "weaving",
        "oozing", "infested",
    )

    /** AE 인첸트 파일이 아직 쓰는 옛 Bukkit 이름 → 지금 키. */
    private val LEGACY_POTIONS = mapOf(
        "slow" to "slowness",
        "fast_digging" to "haste",
        "slow_digging" to "mining_fatigue",
        "increase_damage" to "strength",
        "heal" to "instant_health",
        "harm" to "instant_damage",
        "jump" to "jump_boost",
        "confusion" to "nausea",
        "damage_resistance" to "resistance",
    )

    /** 물약 이름을 지금 키로. 모르면 null. */
    fun potion(name: String): String? {
        val key = name.trim().lowercase().removePrefix("minecraft:")
        return LEGACY_POTIONS[key] ?: key.takeIf { it in POTIONS }
    }

    /** 물약 효과의 한국어 이름. 화면에만 쓴다. */
    val POTION_LABELS: Map<String, String> = mapOf(
        "speed" to "신속", "slowness" to "구속", "haste" to "성급함", "mining_fatigue" to "피로",
        "strength" to "힘", "instant_health" to "즉시 치유", "instant_damage" to "즉시 피해",
        "jump_boost" to "점프 강화", "nausea" to "멀미", "regeneration" to "재생", "resistance" to "저항",
        "fire_resistance" to "화염 저항", "water_breathing" to "수중 호흡", "invisibility" to "투명화",
        "blindness" to "실명", "night_vision" to "야간 투시", "hunger" to "허기", "weakness" to "나약함",
        "poison" to "독", "wither" to "시듦", "health_boost" to "생명력 강화", "absorption" to "흡수",
        "saturation" to "포화", "glowing" to "발광", "levitation" to "공중 부양", "luck" to "행운",
        "unluck" to "불운", "slow_falling" to "느린 낙하", "conduit_power" to "전달체의 힘",
        "dolphins_grace" to "돌고래의 우아함", "bad_omen" to "흉조", "hero_of_the_village" to "마을의 영웅",
        "darkness" to "어둠", "trial_omen" to "시련의 징조", "raid_omen" to "습격의 징조",
        "wind_charged" to "돌풍", "weaving" to "거미줄", "oozing" to "점액", "infested" to "벌레",
    )

    /** 이롭지 않은 효과. `%has bad effect%` 류와 정화 효과가 쓴다. */
    val HARMFUL_POTIONS: Set<String> = setOf(
        "slowness", "mining_fatigue", "instant_damage", "nausea", "blindness", "hunger", "weakness",
        "poison", "wither", "levitation", "unluck", "bad_omen", "darkness", "infested", "oozing", "weaving",
    )

    val DIRECTIONS = listOf("FORWARD", "BACKWARD", "UP", "DOWN")
    val ARMOR_SLOTS = listOf("HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS")
    val SEEDS = listOf("AUTO", "SEEDS", "POTATO", "CARROT", "BEETROOT", "MELON", "PUMPKIN", "NETHER_WART")
    val FIREWORK_TYPES = listOf("BALL", "BALL_LARGE", "STAR", "BURST", "CREEPER")
    val TUNNEL_MODES = listOf("AUTO", "UP", "DOWN", "STRAIGHT")
    val AOE_TARGETS = listOf("ALL", "MOBS", "PLAYERS", "DAMAGEABLE", "UNDAMAGEABLE")
    val COLORS = listOf(
        "WHITE", "SILVER", "GRAY", "BLACK", "RED", "MAROON", "YELLOW", "OLIVE", "LIME", "GREEN",
        "AQUA", "TEAL", "BLUE", "NAVY", "FUCHSIA", "PURPLE", "ORANGE",
    )
}
