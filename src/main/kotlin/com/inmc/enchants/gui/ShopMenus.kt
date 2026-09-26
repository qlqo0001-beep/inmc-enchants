package com.inmc.enchants.gui

import com.inmc.enchants.Enchants
import com.inmc.enchants.effect.impl.Experience
import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.item.ItemKind
import com.inmc.enchants.item.Price
import kr.inmc.core.gui.Icon
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.ItemStack

private fun give(player: Player, stack: ItemStack) {
    for (left in player.inventory.addItem(stack).values) player.world.dropItemNaturally(player.location, left)
}

/**
 * 인챈터 — 등급마다 미확인 부여서 한 장을 판다. 가격은 등급의 `enchanter-price`.
 * 그 등급에 나올 인첸트가 없으면(전부 인챈터 제외) 팔지 않는다.
 */
class EnchanterMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<dark_purple>인챈터</dark_purple>") {

    override val back: (() -> Unit) = { MainMenu(e, viewer).show() }

    override fun draw() {
        clear()
        val groups = e.groups.all().filter { g -> e.registry.all().any { it.group.equals(g.id, ignoreCase = true) && !it.settings.disableInEnchanter } }
        for ((index, group) in groups.take(21).withIndex()) {
            val price = Price.parse(group.enchanterPrice)
            val slot = 10 + index % 7 + (index / 7) * 9
            val stack = e.items.unopened(group)
            val lore = listOf(
                "<gray>가격: <white>" + (price?.describe(e) ?: "<red>설정 오류"),
                if (price?.has(e, viewer) == true) "<green>살 수 있습니다" else "<red>모자랍니다",
                "",
                "<yellow>▶ 클릭: 한 장 사기",
            )
            set(slot, Icon.annotate(stack, lore = lore)) {
                if (price == null || !price.has(e, viewer)) {
                    e.messages.send(viewer, "enchanter-cannot-afford", e.ph().value(price?.describe(e) ?: "?"))
                    return@set
                }
                price.take(e, viewer)
                give(viewer, e.items.unopened(group))
                viewer.playSound(viewer.location, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1f, 1f)
                e.messages.send(viewer, "enchanter-bought", e.ph().group(group.name))
                refresh()
            }
        }
        set(49, Icon.of(Material.EXPERIENCE_BOTTLE, "<green>내 경험치", "<gray>레벨 <white>" + viewer.level + "<gray> · 점수 <white>" + Experience.total(viewer)))
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/**
 * 연금술사 — 왼쪽·오른쪽 칸에 같은 것 둘을 넣으면 가운데에 결과가 보인다.
 * 부여서 두 장(같은 인첸트·같은 레벨) → 한 단계 위. 같은 등급 마법 가루 둘 → 성공률을 더한 하나.
 *
 * 넣은 아이템은 화면을 닫으면 돌려준다.
 */
class AlchemistMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<dark_aqua>연금술사</dark_aqua>") {

    override val back: (() -> Unit) = { MainMenu(e, viewer).show() }

    override fun isSlotEditable(slot: Int): Boolean = slot == LEFT || slot == RIGHT

    override fun acceptsShiftInsert(): Boolean = true

    private fun left(): ItemStack? = inventory.getItem(LEFT)?.takeIf { !it.type.isAir }

    private fun right(): ItemStack? = inventory.getItem(RIGHT)?.takeIf { !it.type.isAir }

    /** 결과. 못 합치면 null. */
    private fun result(): ItemStack? {
        val a = left() ?: return null
        val b = right() ?: return null
        if (a.amount != 1 || b.amount != 1) return null
        return when (e.items.kindOf(a)) {
            ItemKind.BOOK -> e.uses.combine(a, b)?.takeIf { it.outcome.applied }?.target
            ItemKind.MAGIC_DUST -> {
                if (e.items.kindOf(b) != ItemKind.MAGIC_DUST) return null
                val group = e.items.group(a) ?: return null
                if (e.items.group(b)?.id != group.id) return null
                e.items.magicDust(group, (e.items.amount(a) + e.items.amount(b)).coerceAtMost(100))
            }
            else -> null
        }
    }

    override fun draw() {
        // 넣은 두 칸은 그대로 두고 나머지만 다시 그린다.
        val keepLeft = left()
        val keepRight = right()
        clear()
        keepLeft?.let { inventory.setItem(LEFT, it) }
        keepRight?.let { inventory.setItem(RIGHT, it) }
        val preview = result()
        set(OUT, preview ?: Icon.of(Material.GRAY_DYE, "<gray>결과 없음", "<gray>같은 부여서 두 장(같은 레벨)이나", "<gray>같은 등급 마법 가루 두 개를 넣으세요.")) {
            val made = result() ?: return@set
            inventory.setItem(LEFT, null)
            inventory.setItem(RIGHT, null)
            give(viewer, made)
            viewer.playSound(viewer.location, Sound.BLOCK_BREWING_STAND_BREW, 1f, 1.2f)
            refresh()
        }
        for (slot in listOf(19, 21, 23, 25, 28, 30, 32, 34)) set(slot, Icon.EDGE)
        fillEmptyExcept()
        navigation()
    }

    // 넣고 빼는 것은 바닐라가 한다. 끝난 다음 틱에 미리보기를 다시 그린다(가방 쪽 Shift 클릭 포함).
    override fun handleClick(event: InventoryClickEvent) {
        super.handleClick(event)
        redrawSoon()
    }

    override fun onDrag(event: InventoryDragEvent) {
        super.onDrag(event)
        redrawSoon()
    }

    private fun redrawSoon() = e.support.later(1L) { if (viewer.openInventory.topInventory == inventory) refresh() }

    private fun fillEmptyExcept() {
        for (slot in 0 until size) {
            if (slot == LEFT || slot == RIGHT || inventory.getItem(slot) != null) continue
            inventory.setItem(slot, Icon.FILLER)
        }
    }

    override fun onClose(event: InventoryCloseEvent) {
        left()?.let { give(viewer, it) }
        right()?.let { give(viewer, it) }
        inventory.setItem(LEFT, null)
        inventory.setItem(RIGHT, null)
    }

    companion object {
        const val LEFT = 20
        const val RIGHT = 24
        const val OUT = 22
    }
}

/**
 * 땜장이 — 넣은 것을 되사 준다.
 *
 * | 넣은 것 | 받는 것 |
 * |---|---|
 * | 부여서 | 그 등급의 비밀 가루 |
 * | 미확인 부여서 | 경험치(등급이 높을수록 많이) |
 * | 마법·신비한 가루 | 경험치 조금 |
 * | 커스텀 인첸트가 붙은 아이템 | 붙은 레벨 합 × 경험치 |
 *
 * 받지 않는 것은 닫을 때 그대로 돌려준다.
 */
class TinkererMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<gold>땜장이</gold>") {

    override val back: (() -> Unit) = { MainMenu(e, viewer).show() }

    override fun isSlotEditable(slot: Int): Boolean = slot in INPUT

    override fun acceptsShiftInsert(): Boolean = true

    /** 넣은 것 하나의 값. 받지 않으면 null. */
    private fun offer(stack: ItemStack): Pair<ItemStack?, Int>? {
        val amount = stack.amount
        return when (e.items.kindOf(stack)) {
            ItemKind.BOOK -> e.items.bookInfo(stack)?.let { e.items.secretDust(e.groups.get(it.def.group), amount) to 0 }
            ItemKind.UNOPENED_BOOK -> null to (e.items.group(stack)?.order ?: 1) * 30 * amount
            ItemKind.MAGIC_DUST, ItemKind.MYSTERY_DUST -> null to 10 * amount
            null -> EnchantStorage.read(stack).values.sum().takeIf { it > 0 }?.let { null to it * 50 * amount }
            else -> null
        }
    }

    override fun draw() {
        val kept = INPUT.associateWith { inventory.getItem(it) }
        clear()
        for ((slot, stack) in kept) if (stack != null) inventory.setItem(slot, stack)
        val offers = INPUT.mapNotNull { inventory.getItem(it)?.let(::offer) }
        val exp = offers.sumOf { it.second }
        val dusts = offers.count { it.first != null }
        set(49, Icon.of(Material.EMERALD, "<green>바꾸기", "<gray>경험치 <white>+$exp", "<gray>비밀 가루 <white>$dusts<gray>묶음", "", "<yellow>▶ 클릭")) {
            var total = 0
            val given = ArrayList<ItemStack>()
            val dusts = ArrayList<ItemStack>()
            for (slot in INPUT) {
                val stack = inventory.getItem(slot) ?: continue
                val (item, points) = offer(stack) ?: continue
                inventory.setItem(slot, null)
                given += stack.clone()
                item?.let { dusts += it.clone(); give(viewer, it) }
                total += points
            }
            if (total > 0) viewer.giveExp(total)
            e.tinkerLog.record(viewer.uniqueId, com.inmc.enchants.item.TinkerLog.Trade(System.currentTimeMillis(), given, total, dusts))
            viewer.playSound(viewer.location, Sound.ENTITY_VILLAGER_YES, 1f, 1f)
            refresh()
        }
        if (e.config.tinkerRestoreHours > 0) {
            val count = e.tinkerLog.trades(viewer.uniqueId).size
            set(SLOT_RESTORE, Icon.of(Material.RECOVERY_COMPASS, "<aqua>되돌리기 <white>" + count + "</white>건",
                "<gray>" + e.config.tinkerRestoreHours + "시간 안에 바꾼 것을 돌려받습니다.", "<gray>그때 받은 경험치·비밀 가루를 도로 냅니다.", "", "<yellow>▶ 클릭")) {
                TinkerRestoreMenu(e, viewer).show()
            }
        }
        for (slot in 0 until size) if (slot !in INPUT && inventory.getItem(slot) == null) inventory.setItem(slot, Icon.FILLER)
        navigation()
    }

    override fun handleClick(event: InventoryClickEvent) {
        super.handleClick(event)
        redrawSoon()
    }

    override fun onDrag(event: InventoryDragEvent) {
        super.onDrag(event)
        redrawSoon()
    }

    private fun redrawSoon() = e.support.later(1L) { if (viewer.openInventory.topInventory == inventory) refresh() }

    override fun onClose(event: InventoryCloseEvent) {
        for (slot in INPUT) {
            val stack = inventory.getItem(slot) ?: continue
            inventory.setItem(slot, null)
            give(viewer, stack)
        }
    }

    companion object {
        val INPUT: List<Int> = (10..16) + (19..25) + (28..34)
        const val SLOT_RESTORE = 51
    }
}

/**
 * 땜장이 되돌리기 — 정한 시간 안에 바꾼 것을 새것부터. 누르면 확인창, 받은 경험치·가루를 거두고 넣은 것을 돌려준다.
 */
class TinkerRestoreMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<aqua>땜장이 되돌리기</aqua>") {

    override val back: (() -> Unit) = { TinkererMenu(e, viewer).show() }

    private var page = 0

    override fun draw() {
        clear()
        val now = System.currentTimeMillis()
        val trades = e.tinkerLog.trades(viewer.uniqueId, now)
        page = kr.inmc.core.gui.Paging.clamp(page, trades.size)
        for ((slot, trade) in kr.inmc.core.gui.Paging.slice(trades, page).withIndex()) {
            val left = e.config.tinkerRestoreHours * 3_600_000L - (now - trade.at)
            val icon = trade.given.first().clone().also { it.amount = 1 }
            set(slot, Icon.annotate(icon, lore = buildList {
                add("")
                add("<gray>넣은 것 <white>" + trade.given.sumOf { it.amount } + "</white>개" + (if (trade.given.size > 1) " (" + trade.given.size + "칸)" else ""))
                add("<gray>돌려줄 것: 경험치 <white>" + trade.exp + "</white> · 비밀 가루 <white>" + trade.dusts.sumOf { it.amount } + "</white>개")
                add("<gray>남은 시간 <white>" + kr.inmc.core.util.Durations.formatShort(left / 1000) + "</white>")
                add("")
                add("<yellow>▶ 클릭: 되돌리기")
            })) { confirm(trade) }
        }
        if (trades.isEmpty()) set(22, Icon.of(Material.BARRIER, "<gray>되돌릴 것이 없습니다", "<gray>" + e.config.tinkerRestoreHours + "시간이 지난 것은 사라집니다."))
        if (page > 0) set(kr.inmc.core.gui.Paging.SLOT_PREV, Icon.of(Material.ARROW, "<yellow>이전")) { page--; refresh() }
        if (page + 1 < kr.inmc.core.gui.Paging.pageCount(trades.size)) set(kr.inmc.core.gui.Paging.SLOT_NEXT, Icon.of(Material.ARROW, "<yellow>다음")) { page++; refresh() }
        navigation()
    }

    private fun confirm(trade: com.inmc.enchants.item.TinkerLog.Trade) {
        kr.inmc.core.gui.ConfirmMenu(e, "<aqua>되돌릴까요?", listOf(
            "<gray>경험치 <white>" + trade.exp + "</white> 와 비밀 가루 <white>" + trade.dusts.sumOf { it.amount } + "</white>개를 냅니다.",
            "<gray>넣었던 것 <white>" + trade.given.sumOf { it.amount } + "</white>개를 돌려받습니다.",
        ), onConfirm = {
            val key = when (e.tinkerLog.restore(viewer, trade.at)) {
                com.inmc.enchants.item.TinkerLog.Result.RESTORED -> "tinker-restored"
                com.inmc.enchants.item.TinkerLog.Result.NO_EXP -> "tinker-restore-no-exp"
                com.inmc.enchants.item.TinkerLog.Result.NO_DUST -> "tinker-restore-no-dust"
                else -> "tinker-restore-gone"
            }
            e.messages.send(viewer, key, e.ph().value(trade.exp.toString()))
            show()
        }, onCancel = { show() }).open(viewer)
    }
}
