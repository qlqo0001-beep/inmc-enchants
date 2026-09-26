package com.inmc.enchants.engine

/**
 * 효과가 누구·어디에 닿는가. `@Victim` · `@Aoe{radius=3,target=mobs}` — AE 와 같은 문법이다.
 *
 * 줄에 대상이 없으면 [TargetKind.SELF] 다(발동시킨 쪽).
 */
data class TargetSpec(val kind: TargetKind, val options: Map<String, String> = emptyMap()) {

    fun int(key: String, default: Int): Int = options[key]?.trim()?.toDoubleOrNull()?.toInt() ?: default

    fun double(key: String, default: Double): Double = options[key]?.trim()?.toDoubleOrNull() ?: default

    fun string(key: String, default: String): String = options[key]?.trim()?.ifEmpty { null } ?: default

    fun bool(key: String, default: Boolean): Boolean =
        options[key]?.trim()?.lowercase()?.let { it == "true" || it == "yes" || it == "1" } ?: default

    /** 설정 파일에 다시 쓸 때의 모양. */
    fun serialize(): String =
        "@" + kind.token + if (options.isEmpty()) "" else options.entries.joinToString(",", "{", "}") { it.key + "=" + it.value }

    companion object {

        private val PATTERN = Regex("^@([A-Za-z]+)(?:\\{([^}]*)\\})?$")

        /** 모르는 대상이면 null. 적재 때 거부해야 관리자가 안다. */
        fun parse(token: String): TargetSpec? {
            val match = PATTERN.matchEntire(token.trim()) ?: return null
            val kind = TargetKind.byToken(match.groupValues[1]) ?: return null
            val options = LinkedHashMap<String, String>()
            match.groupValues[2].takeIf { it.isNotBlank() }?.split(',')?.forEach { pair ->
                val key = pair.substringBefore('=', "").trim().lowercase()
                val value = pair.substringAfter('=', "").trim()
                if (key.isNotEmpty()) options[kind.canonical(key)] = value
            }
            return TargetSpec(kind, options)
        }
    }
}

/**
 * 대상 종류. [token] 은 설정 파일의 이름(AE 와 같다), [label] 은 화면에 보이는 이름.
 *
 * [optionKeys] 가 편집 화면이 보여줄 칸이다. 별칭(`r`·`rc`·…)은 [canonical] 이 정식 이름으로 바꾼다.
 */
enum class TargetKind(val token: String, val label: String, val optionKeys: List<String>) {
    VICTIM("Victim", "피해자", emptyList()),
    ATTACKER("Attacker", "공격자", emptyList()),
    SELF("Self", "자신(발동한 쪽)", emptyList()),
    BLOCK("Block", "상호작용한 블록", emptyList()),
    NEAREST_PLAYER("NearestPlayer", "가장 가까운 플레이어", listOf("radius")),
    EYE_HEIGHT("EyeHeight", "눈높이 위치", emptyList()),
    TRENCH("Trench", "주변 정사각 블록(트렌치) — facing=true 면 캔 면 기준", listOf("radius", "radiuscustom", "ignoretool", "facing")),
    TUNNEL("Tunnel", "터널·계단 블록", listOf("radiuscustom", "mode", "ignoretool")),
    BLOCK_IN_DISTANCE("BlockInDistance", "시선 방향 블록", listOf("distance")),
    VEINMINE("Veinmine", "이어진 광맥", listOf("depth")),
    ADD("Add", "위치 더하기", listOf("x", "y", "z")),
    AOE("Aoe", "범위 안 엔티티", listOf("radius", "target", "limit")),
    ENTITY_IN_SIGHT("EntityInSight", "시야 안 엔티티", listOf("distance", "angle", "limit", "target")),
    ALL_PLAYERS("AllPlayers", "서버의 모든 플레이어", emptyList()),
    PLAYER_FROM_NAME("PlayerFromName", "이름으로 찾은 플레이어", listOf("name")),
    ;

    /** 블록을 대상으로 하는가. 블록 효과(`BREAK_BLOCK` 등)가 이것만 받는다. */
    val isBlockTarget: Boolean get() = this == BLOCK || this == TRENCH || this == TUNNEL || this == BLOCK_IN_DISTANCE || this == VEINMINE

    fun canonical(key: String): String = when (key) {
        "it" -> "ignoretool"
        "r" -> "radius"
        "rc" -> "radiuscustom"
        "t" -> "target"
        "d" -> "distance"
        "dp" -> "depth"
        "a" -> "angle"
        // AE 표에서 p 가 limit 과 points 둘 다다. 대상 종류로 가른다.
        "p" -> if (this == AOE || this == ENTITY_IN_SIGHT) "limit" else "points"
        else -> key
    }

    companion object {
        fun byToken(token: String): TargetKind? = entries.firstOrNull { it.token.equals(token, ignoreCase = true) }
    }
}
