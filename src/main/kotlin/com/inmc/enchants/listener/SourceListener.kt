package com.inmc.enchants.listener

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.item.EnchantStorage
import org.bukkit.Material
import org.bukkit.entity.Enemy
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.VillagerAcquireTradeEvent
import org.bukkit.event.world.LootGenerateEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import java.util.Random

/**
 * 바닐라에서 커스텀 인첸트가 **나오는** 곳. 확률은 `config.yml` 의 `sources`, 0 이면 그 길은 닫힌다.
 *
 * | 길 | 무엇이 |
 * |---|---|
 * | 부여대 | 쓴 레벨에 비례한 확률로, 그 아이템에 붙을 수 있는 커스텀 인첸트 하나가 같이 붙는다 |
 * | 상자 전리품 | 미확인 부여서 한 장 |
 * | 적대 몹 | 플레이어가 처치하면 미확인 부여서 |
 * | 사서 주민 | 새 거래로 커스텀 부여서를 판다 |
 *
 * 등급은 전부 `group-weights` 로 고른다 — 신화 부여서가 흔하게 나오면 인챈터가 무의미해진다.
 */
class SourceListener(private val e: Enchants) : Listener {

    private val random = Random()

    private fun chance(percent: Double): Boolean = percent > 0.0 && random.nextDouble() * 100.0 < percent

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        if (!e.ready || !chance(e.config.tableChancePerLevel * event.expLevelCost)) return
        val material = event.item.type.name
        val group = e.items.randomGroup() ?: return
        val pool = e.registry.all().filter {
            it.group.equals(group.id, ignoreCase = true) && !it.settings.disableInEnchanter && it.settings.requiredEnchants.isEmpty() &&
                !it.settings.customItemsOnly &&
                Applicability.matchesAny(it.applies, material, e.config.appliesGroups)
        }
        if (pool.isEmpty()) return
        val def = pool[random.nextInt(pool.size)]
        val level = e.items.drawLevel(def)
        // 바닐라가 아이템을 바꿔 끼운 **뒤에** 붙인다. 지금 붙이면 바닐라 결과가 덮어쓴다.
        e.support.later(1L) {
            val stack = event.inventory.getItem(0) ?: return@later
            if (stack.type.name != material) return@later
            val enchants = EnchantStorage.read(stack)
            if (enchants.containsKey(def.id)) return@later
            enchants[def.id] = level
            EnchantStorage.write(stack, enchants)
            e.lore.render(stack)
            event.inventory.setItem(0, stack)
            e.messages.send(event.enchanter, "table-bonus", e.ph().enchant(e.display(def, level)))
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLoot(event: LootGenerateEvent) {
        if (!e.ready || event.inventoryHolder == null || !chance(e.config.lootChance)) return
        val group = e.items.randomGroup() ?: return
        event.loot.add(e.items.unopened(group))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTrade(event: VillagerAcquireTradeEvent) {
        val villager = event.entity as? Villager ?: return
        if (!e.ready || villager.profession != Villager.Profession.LIBRARIAN || !chance(e.config.villagerChance)) return
        val group = e.items.randomGroup() ?: return
        val (def, level) = e.items.randomEnchant(group) ?: return
        val recipe = MerchantRecipe(e.items.randomBook(def, level), 0, 3, true, 10, 0.05f)
        recipe.addIngredient(ItemStack(Material.EMERALD, e.items.roll(e.config.villagerPrice).coerceIn(1, 64)))
        recipe.addIngredient(ItemStack(Material.BOOK))
        event.recipe = recipe
    }
}
