package com.inmc.enchants.listener

import com.inmc.enchants.Enchants
import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.item.ItemKind
import com.inmc.enchants.item.ItemUses
import com.inmc.enchants.item.Keys
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.PrepareGrindstoneEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.AnvilInventory
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/**
 * 인첸트 아이템을 **쓰는** 길. 규칙은 [ItemUses] 가 갖고 여기는 아이템을 칸에 넣고 빼기만 한다.
 *
 * | 길 | 무엇 |
 * |---|---|
 * | 끌어다 놓기 | 커서의 아이템을 가방의 아이템 위에 좌·우클릭 |
 * | 우클릭 | 미확인 부여서 열기 · 비밀 가루 열기 |
 * | 모루 | 왼쪽 아이템 + 오른쪽 부여서 |
 * | 숫돌 | 커스텀 인첸트도 지운다(떼어낼 수 없는 저주는 남긴다) |
 */
class ItemListener(private val e: Enchants) : Listener {

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDrop(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.click != ClickType.LEFT && event.click != ClickType.RIGHT) return
        if (event.view.topInventory.holder is kr.inmc.core.gui.Menu && event.rawSlot < event.view.topInventory.size) return
        if (event.clickedInventory?.type != InventoryType.PLAYER) return
        val cursor = event.cursor
        val target = event.currentItem ?: return
        val kind = e.items.kindOf(cursor) ?: return
        if (kind == ItemKind.BOOK && e.items.kindOf(target) == null && !e.config.dragDropApply) return

        if (kind == ItemKind.NAMETAG) {
            if (e.items.kindOf(target) != null || target.type.isAir) return
            event.isCancelled = true
            rename(player, event.slot, target, cursor)
            return
        }

        val result = e.uses.use(player, cursor, target) ?: return
        event.isCancelled = true
        event.currentItem = result.target
        if (result.used) player.setItemOnCursor(e.uses.consumeOne(cursor))
        result.returned?.let { give(player, it) }
        report(player, result)
        e.statics.refresh(player)
    }

    /** 이름표: 한 장을 먼저 떼어 두고 채팅으로 이름을 받는다. 취소·시간 초과면 돌려준다. */
    private fun rename(player: Player, slot: Int, target: ItemStack, tag: ItemStack) {
        val single = tag.clone().also { it.amount = 1 }
        player.setItemOnCursor(e.uses.consumeOne(tag))
        val expected = target.clone()
        e.prompts.request(player, listOf(e.messages.raw("nametag-prompt")), onCancel = { give(player, single) }) { input ->
            val now = player.inventory.getItem(slot)
            if (now == null || !now.isSimilar(expected)) {
                give(player, single)
                e.messages.send(player, "nametag-moved", e.ph())
                return@request
            }
            player.inventory.setItem(slot, e.uses.rename(now, input.take(48), player.hasPermission("inmcenchant.nametag.color")))
            e.messages.send(player, "nametag-applied", e.ph())
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onUse(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        val stack = player.inventory.itemInMainHand
        val opened = when (e.items.kindOf(stack)) {
            ItemKind.UNOPENED_BOOK -> e.uses.open(stack) ?: run {
                e.messages.send(player, "unopened-empty", e.ph())
                return
            }
            ItemKind.SECRET_DUST -> e.uses.openSecret(stack)
            else -> return
        }
        event.isCancelled = true
        player.inventory.setItemInMainHand(e.uses.consumeOne(stack))
        give(player, opened)
        player.playSound(player.location, Sound.ITEM_BOOK_PAGE_TURN, 1f, 1.2f)
        e.messages.send(player, "item-opened", e.ph().item(kr.inmc.core.util.Text.plain(opened.effectiveName())))
    }

    // --- 모루 ------------------------------------------------------------------------------

    /** 미리보기는 **성공한 모습**이다. 실제 굴림은 결과를 집을 때 한다. */
    @EventHandler(priority = EventPriority.HIGH)
    fun onAnvil(event: PrepareAnvilEvent) {
        if (!e.config.anvilApply) return
        val inventory = event.inventory
        val left = inventory.firstItem ?: return
        val right = inventory.secondItem ?: return
        if (e.items.kindOf(right) != ItemKind.BOOK || e.items.kindOf(left) != null) return
        val preview = e.uses.applyBook(null, right, left, certain = true)
        if (!preview.outcome.applied) {
            event.result = null
            return
        }
        event.result = preview.target
        event.view.repairCost = 1
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onAnvilTake(event: InventoryClickEvent) {
        val inventory = event.inventory as? AnvilInventory ?: return
        if (event.rawSlot != 2 || !e.config.anvilApply) return
        val player = event.whoClicked as? Player ?: return
        val left = inventory.firstItem ?: return
        val right = inventory.secondItem ?: return
        if (e.items.kindOf(right) != ItemKind.BOOK || e.items.kindOf(left) != null) return
        event.isCancelled = true
        if (!player.itemOnCursor.type.isAir) return
        val result = e.uses.applyBook(player, right, left)
        if (!result.used) {
            report(player, result)
            return
        }
        inventory.secondItem = e.uses.consumeOne(right)
        inventory.firstItem = null
        inventory.result = null
        result.target?.let { player.setItemOnCursor(it) }
        report(player, result)
    }

    // --- 숫돌 ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    fun onGrindstone(event: PrepareGrindstoneEvent) {
        if (!e.config.grindstoneRemoves) return
        val inventory = event.inventory
        val input = listOfNotNull(inventory.upperItem, inventory.lowerItem).singleOrNull { EnchantStorage.has(it) } ?: return
        val enchants = EnchantStorage.read(input)
        val kept = enchants.filterKeys { e.registry.get(it)?.settings?.removeable == false }
        if (kept.size == enchants.size) return
        val base = event.result?.takeIf { !it.type.isAir } ?: input.clone()
        EnchantStorage.write(base, kept)
        EnchantStorage.setFlag(base, Keys.WHITE_SCROLL, false)
        e.lore.render(base)
        event.result = base
    }

    // --- 도움 ------------------------------------------------------------------------------

    private fun report(player: Player, result: ItemUses.Result) {
        val ph = e.ph()
        (result.label ?: result.enchant?.let { e.display(it, result.level) })?.let { ph.enchant(it) }
        e.messages.send(player, result.outcome.message, ph)
        val sound = when {
            result.outcome == ItemUses.Outcome.DESTROYED -> Sound.ENTITY_ITEM_BREAK
            result.outcome.applied -> Sound.ENTITY_PLAYER_LEVELUP
            result.used -> Sound.BLOCK_ANVIL_LAND
            else -> Sound.BLOCK_NOTE_BLOCK_BASS
        }
        player.playSound(player.location, sound, 0.8f, if (result.outcome.applied) 1.4f else 0.8f)
    }

    private fun give(player: Player, stack: ItemStack) {
        if (player.gameMode == GameMode.SPECTATOR) return
        for (left in player.inventory.addItem(stack).values) player.world.dropItemNaturally(player.location, left)
    }
}
