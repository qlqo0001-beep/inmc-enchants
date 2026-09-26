package com.inmc.enchants.item

import com.inmc.enchants.Enchants
import com.inmc.enchants.effect.impl.Experience
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 인챈터 가격(`groups.yml` 의 `enchanter-price`). 글자 하나로 적는다.
 *
 * | 적는 것 | 뜻 |
 * |---|---|
 * | `exp:400` | 경험치 400 점 |
 * | `level:10` | 레벨 10 |
 * | `money:500` | 돈 500 (Vault) |
 * | `souls:20` | 들거나 입은 아이템의 영혼 20 |
 * | `item:DIAMOND:5` | 다이아몬드 5 개 |
 * | 비움 | 공짜 |
 */
sealed interface Price {

    fun describe(e: Enchants): String

    fun has(e: Enchants, player: Player): Boolean

    /** 치른다. [has] 가 참일 때만 부른다. */
    fun take(e: Enchants, player: Player)

    data object Free : Price {
        override fun describe(e: Enchants) = "무료"
        override fun has(e: Enchants, player: Player) = true
        override fun take(e: Enchants, player: Player) = Unit
    }

    data class Exp(val points: Int) : Price {
        override fun describe(e: Enchants) = "경험치 $points"
        override fun has(e: Enchants, player: Player) = Experience.total(player) >= points
        override fun take(e: Enchants, player: Player) = Experience.set(player, Experience.total(player) - points)
    }

    data class Levels(val levels: Int) : Price {
        override fun describe(e: Enchants) = "레벨 $levels"
        override fun has(e: Enchants, player: Player) = player.level >= levels
        override fun take(e: Enchants, player: Player) {
            player.level -= levels
        }
    }

    data class Money(val amount: Double) : Price {
        override fun describe(e: Enchants) = e.economy.format(amount)
        override fun has(e: Enchants, player: Player) = e.economy.isEnabled && e.economy.has(player, amount)
        override fun take(e: Enchants, player: Player) {
            e.economy.withdraw(player, amount)
        }
    }

    data class Souls(val souls: Int) : Price {
        override fun describe(e: Enchants) = "영혼 $souls"
        override fun has(e: Enchants, player: Player): Boolean {
            val eq = player.equipment
            return listOf(eq.itemInMainHand, eq.helmet, eq.chestplate, eq.leggings, eq.boots)
                .any { EnchantStorage.flag(it, Keys.SOUL_TRACKER) && e.souls.souls(it) >= souls }
        }
        override fun take(e: Enchants, player: Player) {
            e.souls.take(player, null, souls)
        }
    }

    data class Items(val material: Material, val amount: Int) : Price {
        override fun describe(e: Enchants) = material.name.lowercase().replace('_', ' ') + " " + amount + "개"
        override fun has(e: Enchants, player: Player) = player.inventory.containsAtLeast(ItemStack(material), amount)
        override fun take(e: Enchants, player: Player) {
            player.inventory.removeItem(ItemStack(material, amount))
        }
    }

    companion object {
        fun parse(raw: String?): Price? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return Free
            val parts = text.split(':').map { it.trim() }
            val number = parts.getOrNull(1)?.toDoubleOrNull()
            return when (parts[0].lowercase()) {
                "exp" -> number?.let { Exp(it.toInt().coerceAtLeast(0)) }
                "level", "levels" -> number?.let { Levels(it.toInt().coerceAtLeast(0)) }
                "money" -> number?.let { Money(it.coerceAtLeast(0.0)) }
                "souls" -> number?.let { Souls(it.toInt().coerceAtLeast(0)) }
                "item" -> {
                    val material = Material.matchMaterial(parts.getOrNull(1).orEmpty()) ?: return null
                    Items(material, parts.getOrNull(2)?.toIntOrNull()?.coerceIn(1, 2304) ?: 1)
                }
                else -> null
            }
        }
    }
}
