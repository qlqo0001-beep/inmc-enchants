package com.inmc.enchants.effect

import com.inmc.enchants.engine.TriggerContext
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.CookingRecipe
import org.bukkit.inventory.ItemStack
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor

/**
 * 드랍을 바꾸는 세 효과(배수·제련·가방으로)를 **한 곳에서** 적용한다.
 *
 * 캔 블록 자체(BlockDropItemEvent), 효과가 부순 블록(트렌치·광맥), 몹 드랍 셋이 모두 여기를 지난다.
 * 따로 두면 "트렌치로 캔 블록은 제련이 안 된다" 같은 어긋남이 생긴다.
 */
class DropService {

    /** 재료 → 화로 결과. 레시피를 매번 훑지 않게 기억한다. 없으면 AIR 로 적어 다시 안 찾는다. */
    private val smelted = ConcurrentHashMap<Material, ItemStack>()

    fun smelt(stack: ItemStack): ItemStack {
        val result = smelted.getOrPut(stack.type) { findSmelt(stack.type) }
        if (result.type.isAir) return stack
        return result.clone().also { it.amount = (result.amount * stack.amount).coerceAtMost(it.maxStackSize * 4) }
    }

    private fun findSmelt(material: Material): ItemStack {
        val iterator = Bukkit.recipeIterator()
        while (iterator.hasNext()) {
            val recipe = iterator.next() as? CookingRecipe<*> ?: continue
            if (recipe !is org.bukkit.inventory.FurnaceRecipe) continue
            if (recipe.inputChoice.test(ItemStack(material))) return recipe.result.clone()
        }
        return ItemStack(Material.AIR)
    }

    /**
     * 드랍 목록을 바꾼다. 배수는 소수를 **확률로** 올린다(1.5 배 = 절반 확률로 두 배) — 버리면
     * 1.5 배 인첸트가 아무 일도 안 한다.
     */
    fun transform(drops: MutableList<ItemStack>, ctx: TriggerContext) {
        if (ctx.smeltDrops) {
            for (i in drops.indices) drops[i] = smelt(drops[i])
        }
        val multiplier = ctx.dropMultiplier
        if (multiplier != 1.0 && multiplier > 0) {
            for (stack in drops) {
                val exact = stack.amount * multiplier
                val whole = floor(exact).toInt()
                val extra = if (Math.random() < exact - whole) 1 else 0
                stack.amount = (whole + extra).coerceAtLeast(1)
            }
        }
    }

    /** 배수로 한 묶음 상한을 넘긴 것을 나눈다. */
    fun split(stack: ItemStack): List<ItemStack> {
        val max = stack.maxStackSize.coerceAtLeast(1)
        if (stack.amount <= max) return listOf(stack)
        val out = ArrayList<ItemStack>()
        var left = stack.amount
        while (left > 0) {
            out += stack.clone().also { it.amount = minOf(max, left) }
            left -= max
        }
        return out
    }

    /** 바꾼 뒤 가방으로(넘치면 발밑에) 또는 그 자리에 떨군다. */
    fun deliver(player: Player?, location: Location, drops: MutableList<ItemStack>, ctx: TriggerContext) {
        transform(drops, ctx)
        for (stack in drops.flatMap(::split)) {
            if (stack.type.isAir || stack.amount <= 0) continue
            if (ctx.teleportDrops && player != null) {
                for (left in player.inventory.addItem(stack).values) player.world.dropItemNaturally(player.location, left)
            } else {
                location.world.dropItemNaturally(location, stack)
            }
        }
    }
}
