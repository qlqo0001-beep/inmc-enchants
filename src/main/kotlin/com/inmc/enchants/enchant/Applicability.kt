package com.inmc.enchants.enchant

/**
 * 인첸트가 붙을 수 있는 아이템. AE 의 `applies` 목록 문법이다.
 *
 * | 적는 것 | 뜻 |
 * |---|---|
 * | `ALL_SWORD` · `ALL_AXE` · `ALL_PICKAXE` · `ALL_SPADE`(=`ALL_SHOVEL`) · `ALL_HOE` | 그 종류 전부 |
 * | `ALL_HELMET` · `ALL_CHESTPLATE` · `ALL_LEGGINGS` · `ALL_BOOTS` · `ALL_ARMOR` | 방어구 |
 * | `ALL_TOOLS` · `ALL_WEAPONS` | 도구(곡괭이·도끼·삽·괭이) · 무기(검·도끼) |
 * | `DIAMOND_ARMOR` | 그 재료의 방어구 네 부위 |
 * | `BOW` · `TRIDENT` · … | 재질 이름 그대로 |
 * | 설정의 `applies-groups` 이름 | 관리자가 묶은 목록 |
 *
 * **재질 이름만 본다.** `Material` 의 분류 메서드는 서버 레지스트리를 타서 테스트에서 못 부르고,
 * 버전마다 답이 달라진다(`ARCHITECTURE.md` "서버 없이 못 부르는 API").
 */
object Applicability {

    private val ARMOR_PIECES = listOf("_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS")

    fun matches(pattern: String, material: String, groups: Map<String, List<String>> = emptyMap()): Boolean {
        val p = pattern.trim().uppercase()
        val m = material.trim().uppercase().removePrefix("MINECRAFT:")
        if (p.isEmpty() || m.isEmpty()) return false
        if (p == m) return true

        groups[p]?.let { members -> return members.any { matches(it, m, emptyMap()) } }

        return when (p) {
            "ALL_SWORD", "ALL_SWORDS" -> m.endsWith("_SWORD")
            "ALL_AXE", "ALL_AXES" -> m.endsWith("_AXE") && !m.endsWith("_PICKAXE")
            "ALL_PICKAXE", "ALL_PICKAXES" -> m.endsWith("_PICKAXE")
            "ALL_SPADE", "ALL_SHOVEL", "ALL_SHOVELS" -> m.endsWith("_SHOVEL")
            "ALL_HOE", "ALL_HOES" -> m.endsWith("_HOE")
            "ALL_HELMET", "ALL_HELMETS" -> m.endsWith("_HELMET")
            "ALL_CHESTPLATE", "ALL_CHESTPLATES" -> m.endsWith("_CHESTPLATE")
            "ALL_LEGGINGS" -> m.endsWith("_LEGGINGS")
            "ALL_BOOTS" -> m.endsWith("_BOOTS")
            "ALL_ARMOR", "ALL_ARMOUR" -> isArmor(m)
            "ALL_TOOLS", "ALL_TOOL" -> m.endsWith("_PICKAXE") || (m.endsWith("_AXE")) || m.endsWith("_SHOVEL") || m.endsWith("_HOE")
            "ALL_WEAPONS", "ALL_WEAPON" -> m.endsWith("_SWORD") || (m.endsWith("_AXE") && !m.endsWith("_PICKAXE"))
            else -> when {
                p.endsWith("_ARMOR") -> {
                    val prefix = p.removeSuffix("_ARMOR") + "_"
                    m.startsWith(prefix) && isArmor(m)
                }
                p.startsWith("ALL_") -> m.endsWith("_" + p.removePrefix("ALL_"))
                else -> false
            }
        }
    }

    fun matchesAny(patterns: List<String>, material: String, groups: Map<String, List<String>> = emptyMap()): Boolean =
        patterns.any { matches(it, material, groups) }

    fun isArmor(material: String): Boolean {
        val m = material.uppercase()
        return ARMOR_PIECES.any { m.endsWith(it) }
    }

    /** 방어구 부위. 방어구가 아니면 null. */
    fun armorPiece(material: String): String? {
        val m = material.uppercase()
        return when {
            m.endsWith("_HELMET") -> "HELMET"
            m.endsWith("_CHESTPLATE") -> "CHESTPLATE"
            m.endsWith("_LEGGINGS") -> "LEGGINGS"
            m.endsWith("_BOOTS") -> "BOOTS"
            else -> null
        }
    }

    /** 편집 화면이 고르게 보여줄 보기. */
    val PRESETS: List<Pair<String, String>> = listOf(
        "ALL_SWORD" to "검", "ALL_AXE" to "도끼", "ALL_PICKAXE" to "곡괭이", "ALL_SPADE" to "삽", "ALL_HOE" to "괭이",
        "ALL_HELMET" to "투구", "ALL_CHESTPLATE" to "흉갑", "ALL_LEGGINGS" to "레깅스", "ALL_BOOTS" to "부츠",
        "ALL_ARMOR" to "방어구 전부", "ALL_TOOLS" to "도구 전부", "ALL_WEAPONS" to "무기(검·도끼)",
        "BOW" to "활", "CROSSBOW" to "쇠뇌", "TRIDENT" to "삼지창", "MACE" to "철퇴", "FISHING_ROD" to "낚싯대",
        "ELYTRA" to "겉날개", "SHIELD" to "방패", "SHEARS" to "가위", "GOAT_HORN" to "염소 뿔나팔",
    )
}
