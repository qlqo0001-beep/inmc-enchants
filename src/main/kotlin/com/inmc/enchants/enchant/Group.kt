package com.inmc.enchants.enchant

import org.bukkit.configuration.ConfigurationSection

/**
 * 인첸트 등급(AE `groups.yml`). 색·순서·부여서 모양·가루와 확장기의 수치를 정한다.
 *
 * [order] 가 로어 정렬과 인챈터 배치 순서다 — 높을수록 귀하다.
 */
data class Group(
    val id: String,
    val name: String,
    /** MiniMessage 또는 `&` 색코드. `%group-color%` 가 이 값이 된다. */
    val color: String,
    val order: Int,
    /** 가루·확장기 같은 그룹 아이템의 폭죽별 색. `r,g,b`. */
    val rgb: String = "200,200,200",
    val bookMaterial: String? = null,
    val bookModelData: Int = 0,
    /** 이 그룹 확장기가 늘리는 칸. */
    val slotIncreaser: Int = 1,
    /** 마법 가루 성공률 증가 범위. */
    val dustMin: Int = 1,
    val dustMax: Int = 15,
    /** 비밀 가루를 열었을 때 마법 가루가 나올 확률(%). 아니면 미스터리 가루. */
    val secretDustChance: Double = 22.0,
    /** 인챈터가 파는 가격. `exp:1000` · `money:500` · `level:10` · `souls:20` · `item:DIAMOND:5`. */
    val enchanterPrice: String = "",
) {

    fun save(section: ConfigurationSection) {
        section.set("name", name)
        section.set("color", color)
        section.set("order", order)
        section.set("rgb", rgb)
        bookMaterial?.let { section.set("book-item", it) }
        if (bookModelData > 0) section.set("custom-model-data", bookModelData)
        section.set("slot-increaser", slotIncreaser)
        section.set("magic-dust.success", "$dustMin-$dustMax")
        section.set("magic-dust.chance", secretDustChance)
        if (enchanterPrice.isNotBlank()) section.set("enchanter-price", enchanterPrice)
    }

    companion object {
        fun load(id: String, section: ConfigurationSection, order: Int): Group {
            val range = (section.getString("magic-dust.success") ?: "1-15").split('-').mapNotNull { it.trim().toIntOrNull() }
            return Group(
                id = id.uppercase(),
                name = section.getString("name") ?: id,
                color = section.getString("color") ?: "&7",
                order = section.getInt("order", order),
                rgb = section.getString("rgb") ?: "200,200,200",
                bookMaterial = section.getString("book-item"),
                bookModelData = section.getInt("custom-model-data", 0),
                slotIncreaser = section.getInt("slot-increaser", 1).coerceAtLeast(1),
                dustMin = range.getOrElse(0) { 1 }.coerceIn(0, 100),
                dustMax = range.getOrElse(1) { range.getOrElse(0) { 15 } }.coerceIn(0, 100),
                secretDustChance = section.getDouble("magic-dust.chance", 22.0).coerceIn(0.0, 100.0),
                enchanterPrice = section.getString("enchanter-price").orEmpty(),
            )
        }

        /** 그룹이 지워졌거나 오타일 때. AE 의 fallback-group 과 같은 자리. */
        val FALLBACK = Group("SIMPLE", "일반", "&7", 1)
    }
}
