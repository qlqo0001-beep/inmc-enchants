package com.inmc.enchants.listener

import com.inmc.enchants.Enchants
import org.bukkit.Material
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.entity.Item
import org.bukkit.event.block.BlockDispenseLootEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.enchantment.PrepareItemEnchantEvent
import org.bukkit.event.entity.PiglinBarterEvent
import org.bukkit.event.entity.VillagerAcquireTradeEvent
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.world.LootGenerateEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.MerchantRecipe
import org.bukkit.inventory.meta.EnchantmentStorageMeta

/**
 * 서버 금지 인첸트([com.inmc.enchants.config.DisabledEnchants])가 **새로 나오는 길**을 막는다(2026-10-10, CMI `/cmi disableenchant` 대체).
 * 이미 가진 아이템은 건드리지 않는다(사용자 결정). 커스텀아이템 아이템은 예외 — 정의의 인첸트를 커스텀아이템이 도로 붙인다.
 *
 * | 길 | 어떻게 |
 * |---|---|
 * | 부여대 보기 | 금지 인첸트를 보여 주는 칸을 비운다(누를 수 없다) |
 * | 부여대 결과 | 붙을 목록에서 뺀다. 남는 것이 없으면 취소(경험치·청금석을 쓰지 않는다). 우리 MONITOR 보너스보다 앞(LOW) |
 * | 모루 | 결과에서 뺀다 — 왼쪽 아이템이 **이미 가진 레벨까지는** 둔다. 뺀 인첸트 몫의 경험치 비용도 뺀다. 빈 책이 되거나 오른쪽 책이 얹을 것이 금지 인첸트뿐이면 결과 없음 |
 * | 주민 거래 | 새 거래의 결과에서 뺀다. 금지 인첸트만 담긴 책이면 그 거래를 받지 않는다 |
 * | 전리품 | 상자(`LootGenerateEvent`)·금고와 시련 생성기(`BlockDispenseLootEvent`)·피글린 물물교환·낚시 보물에서 뺀다. 금지 인첸트만 담긴 책은 빈 책으로 |
 *
 * 몹이 태어날 때 입은 장비는 막지 않는다 — 죽을 때 떨군 것을 지우면 좀비가 주운 플레이어의 아이템까지 지운다.
 *
 * 우리 부여서·강화 스크롤·뽑기는 [com.inmc.enchants.item.ItemUses]·`EnchantItems.randomEnchant`·[SourceListener] 가 따로 막는다.
 * 부여대·주민·전리품 칸은 모두 다른 플러그인이 다 고친 뒤를 보도록 늦게(HIGHEST) 둔다 — 부여대 결과만 예외(위 표).
 */
class DisabledEnchantListener(private val e: Enchants) : Listener {

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPrepareEnchant(event: PrepareItemEnchantEvent) {
        if (!e.ready || e.disabled.exempt(event.item)) return
        e.disabled.filterOffers(event.offers)
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        if (!e.ready || e.disabled.exempt(event.item)) return
        val adding = event.enchantsToAdd
        val banned = adding.keys.filter(e.disabled::isDisabled)
        if (banned.isEmpty()) return
        banned.forEach(adding::remove)
        if (adding.isEmpty()) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    fun onAnvil(event: PrepareAnvilEvent) {
        if (!e.ready) return
        val result = event.result?.takeIf { !it.type.isAir } ?: return
        val left = event.inventory.firstItem
        val stripped = e.disabled.strip(result, left) ?: return
        if (e.disabled.exempt(result)) return
        // 책이 얹을 것이 금지 인첸트뿐이었으면 결과가 왼쪽과 같아진다 — 경험치만 쓰는 거래를 보여 주지 않는다.
        val right = event.inventory.secondItem
        val bookOnly = right?.type == Material.ENCHANTED_BOOK && left != null && sameEnchants(stripped, left)
        if (e.disabled.emptyBook(stripped) || bookOnly) {
            event.result = null
            return
        }
        event.result = stripped
        // 바닐라 비용에는 뺀 인첸트 몫(인첸트 비용 × 결과 레벨, 책이면 절반)이 들어 있다 — 받지 않는 것에 경험치를 내지 않게 뺀다.
        val removed = strippedCost(result, stripped, right?.itemMeta is EnchantmentStorageMeta)
        if (removed > 0) event.view.repairCost = (event.view.repairCost - removed).coerceAtLeast(1)
    }

    /** [before] 에서 [after] 로 줄어든 인첸트의 모루 비용 — 바닐라 `AnvilMenu` 와 같은 셈(오른쪽이 책이면 비용 절반, 최소 1). */
    private fun strippedCost(before: ItemStack, after: ItemStack, rightIsBook: Boolean): Int {
        val stored = (before.itemMeta as? EnchantmentStorageMeta)?.storedEnchants.orEmpty()
        val storedAfter = (after.itemMeta as? EnchantmentStorageMeta)?.storedEnchants.orEmpty()
        var cost = 0
        for ((enchantment, level) in before.enchantments + stored) {
            val now = maxOf(after.getEnchantmentLevel(enchantment), storedAfter[enchantment] ?: 0)
            if (now >= level) continue
            val unit = if (rightIsBook) maxOf(1, enchantment.anvilCost / 2) else enchantment.anvilCost
            cost += unit * level
        }
        return cost
    }

    private fun sameEnchants(a: ItemStack, b: ItemStack): Boolean =
        a.enchantments == b.enchantments &&
            (a.itemMeta as? EnchantmentStorageMeta)?.storedEnchants == (b.itemMeta as? EnchantmentStorageMeta)?.storedEnchants

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTrade(event: VillagerAcquireTradeEvent) {
        if (!e.ready) return
        val recipe = event.recipe
        val stripped = e.disabled.strip(recipe.result) ?: return
        if (e.disabled.exempt(recipe.result)) return
        if (e.disabled.emptyBook(stripped)) {
            event.isCancelled = true
            return
        }
        event.recipe = MerchantRecipe(
            stripped, recipe.uses, recipe.maxUses, recipe.hasExperienceReward(), recipe.villagerExperience,
            recipe.priceMultiplier, recipe.demand, recipe.specialPrice, recipe.shouldIgnoreDiscounts(),
        ).also { it.ingredients = recipe.ingredients }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onLoot(event: LootGenerateEvent) {
        if (!e.ready) return
        cleaned(event.loot)?.let(event::setLoot)
    }

    /** 금고·시련 생성기가 뱉는 전리품. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDispenseLoot(event: BlockDispenseLootEvent) {
        if (!e.ready) return
        cleaned(event.dispensedLoot)?.let(event::setDispensedLoot)
    }

    /** 피글린 물물교환(영혼 가속 책·장화). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onBarter(event: PiglinBarterEvent) {
        if (!e.ready) return
        val outcome = event.outcome
        val clean = cleaned(outcome) ?: return
        outcome.clear()
        outcome.addAll(clean)
    }

    /** 낚시 보물. 다른 플러그인(inmc-fishing)이 잡은 것을 바꿨으면 그 뒤를 본다. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (!e.ready || event.state != PlayerFishEvent.State.CAUGHT_FISH) return
        val caught = event.caught as? Item ?: return
        cleaned(listOf(caught.itemStack))?.single()?.let { caught.itemStack = it }
    }

    /** 전리품 목록에서 금지 인첸트를 뺀 새 목록. 금지 인첸트만 담긴 책은 빈 책으로. 바뀐 것이 없으면 null. */
    private fun cleaned(loot: List<ItemStack?>): List<ItemStack>? {
        var changed = false
        val result = loot.filterNotNull().map { stack ->
            val stripped = e.disabled.strip(stack)
            if (stripped == null || e.disabled.exempt(stack)) return@map stack
            changed = true
            if (e.disabled.emptyBook(stripped)) ItemStack(Material.BOOK, stripped.amount) else stripped
        }
        return if (changed) result else null
    }
}
