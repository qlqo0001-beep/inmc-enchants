package com.inmc.enchants.gui

import com.inmc.enchants.Enchants
import com.inmc.enchants.command.EnchantsCommand
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.item.Keys
import com.inmc.enchants.item.Trackers
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Numbers
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/** 인첸트 아이콘 — 도감·편집 목록·고르기 화면이 같은 모양을 쓴다. */
internal fun enchantIcon(e: Enchants, def: EnchantDefinition, extra: List<String> = emptyList()): ItemStack {
    val group = e.groups.get(def.group)
    val lore = buildList {
        addAll(def.description.map { "<gray>$it</gray>" })
        add("")
        add("<gray>등급: " + group.color + group.name)
        add("<gray>붙는 곳: <white>" + def.appliesTo.ifBlank { def.applies.joinToString(", ") })
        add("<gray>최대 레벨: <white>" + def.maxLevel)
        add("<gray>발동: <white>" + def.triggers.joinToString(", ") { it.label })
        addAll(extra)
    }
    return Icon.of(Material.ENCHANTED_BOOK, e.lore.name(def), lore)
}

/**
 * `/인첸트` — 모든 것의 입구. 관리 권한이 있으면 관리 화면 버튼이 보인다.
 */
class MainMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<dark_purple>커스텀 인첸트</dark_purple>") {

    override fun draw() {
        clear()
        set(20, Icon.of(Material.BOOKSHELF, "<yellow>인첸트 도감", "<gray>모든 인첸트와 레벨별 효과를 봅니다.", "", "<yellow>▶ 클릭")) {
            CatalogMenu(e, viewer, back = { MainMenu(e, viewer).show() }).show()
        }
        set(21, Icon.of(Material.ENCHANTING_TABLE, "<light_purple>인챈터", "<gray>등급별 미확인 부여서를 삽니다.", "", "<yellow>▶ 클릭")) {
            EnchanterMenu(e, viewer).show()
        }
        set(22, Icon.of(Material.BREWING_STAND, "<aqua>연금술사", "<gray>같은 부여서 두 장 → 한 단계 위", "<gray>같은 등급 마법 가루 두 개 → 하나로", "", "<yellow>▶ 클릭")) {
            AlchemistMenu(e, viewer).show()
        }
        set(23, Icon.of(Material.ANVIL, "<gold>땜장이", "<gray>부여서·가루·인첸트 아이템을", "<gray>경험치나 비밀 가루로 바꿉니다.", "", "<yellow>▶ 클릭")) {
            TinkererMenu(e, viewer).show()
        }
        val held = viewer.inventory.itemInMainHand
        set(24, Icon.of(if (held.type.isAir) Material.BARRIER else held.type, "<green>내 아이템", "<gray>손에 든 아이템의 인첸트·칸·영혼을 봅니다.", "", "<yellow>▶ 클릭")) {
            HeldItemMenu(e, viewer).show()
        }
        if (viewer.hasPermission(EnchantsCommand.ADMIN)) {
            set(31, Icon.of(Material.COMMAND_BLOCK, "<red>관리", "<gray>인첸트·등급·아이템·설정·검증", "", "<yellow>▶ 클릭")) {
                AdminMenu(e, viewer).show()
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 도감. 등급·붙는 곳으로 거르고 이름으로 찾는다. */
class CatalogMenu(
    e: Enchants,
    viewer: Player,
    override val back: (() -> Unit)?,
    private val admin: Boolean = false,
) : Menu(e, viewer, 54, if (admin) "<dark_red>인첸트 편집</dark_red>" else "<dark_purple>인첸트 도감</dark_purple>") {

    private var page = 0
    private var group: String? = null
    private var applies: String? = null
    private var search: String? = null

    private fun visible(): List<EnchantDefinition> = e.registry.all()
        .filter { group == null || it.group.equals(group, ignoreCase = true) }
        .filter { applies == null || it.applies.any { a -> a.equals(applies, ignoreCase = true) } || matchesPreset(it) }
        .filter { search == null || it.id.contains(search!!, ignoreCase = true) || e.lore.plainName(it).contains(search!!, ignoreCase = true) }
        .sortedWith(compareByDescending<EnchantDefinition> { e.groups.get(it.group).order }.thenBy { e.lore.plainName(it) })

    /** `ALL_SWORD` 로 거르면 `ALL_WEAPONS` 인첸트도 보여야 한다 — 검에 붙으니까. */
    private fun matchesPreset(def: EnchantDefinition): Boolean {
        val sample = SAMPLE[applies] ?: return false
        return Applicability.matchesAny(def.applies, sample, e.config.appliesGroups)
    }

    override fun draw() {
        clear()
        val list = visible()
        page = Paging.clamp(page, list.size)
        for ((slot, def) in Paging.slice(list, page).withIndex()) {
            val extra = if (admin) listOf("", "<gray>발동 횟수: <white>" + e.stats.count(def.id), "<yellow>▶ 클릭: 편집") else listOf("", "<yellow>▶ 클릭: 레벨별 효과")
            set(slot, enchantIcon(e, def, extra)) {
                if (admin) EnchantEditMenu(e, viewer, def.id).show() else EnchantInfoMenu(e, viewer, def.id, back = { show() }).show()
            }
        }
        fillEmpty(Icon.FILLER)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < Paging.pageCount(list.size) - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }

        val groups = listOf<String?>(null) + e.groups.all().map { it.id }
        set(48, Icon.of(Material.NAME_TAG, "<yellow>등급: <white>" + (group?.let { e.groups.get(it).name } ?: "전체"),
            listOf("<gray>" + list.size + "개", "", "<yellow>▶ 좌클릭: 다음 · 우클릭: 이전"))) { event ->
            group = kr.inmc.core.gui.Editors.cycle(event, groups, group)
            page = 0
            refresh()
        }
        val presets = listOf<String?>(null) + Applicability.PRESETS.map { it.first }
        set(49, Icon.of(Material.IRON_SWORD, "<yellow>붙는 곳: <white>" + (applies?.let { a -> Applicability.PRESETS.first { it.first == a }.second } ?: "전체"),
            listOf("", "<yellow>▶ 좌클릭: 다음 · 우클릭: 이전"))) { event ->
            applies = kr.inmc.core.gui.Editors.cycle(event, presets, applies)
            page = 0
            refresh()
        }
        set(50, Icon.of(Material.SPYGLASS, "<yellow>찾기: <white>" + (search ?: "없음"), listOf("", "<yellow>▶ 좌클릭: 이름으로 찾기 · 우클릭: 지우기"))) { event ->
            if (event.isRightClick) {
                search = null
                page = 0
                refresh()
                return@set
            }
            e.prompts.request(viewer, listOf("<yellow>찾을 이름(일부)을 적으세요."), onCancel = { show() }) { input ->
                search = input.trim().ifEmpty { null }
                page = 0
                show()
            }
        }
        if (admin) {
            set(51, Icon.of(Material.WRITABLE_BOOK, "<green>새 인첸트", "<gray>id 를 적으면 빈 인첸트가 만들어지고", "<gray>편집 화면이 열립니다.", "", "<yellow>▶ 클릭")) {
                createEnchant(e, viewer)
            }
        }
        navigation()
    }

    companion object {
        /** 붙는 곳 걸러내기의 대표 재질. */
        private val SAMPLE = mapOf(
            "ALL_SWORD" to "DIAMOND_SWORD", "ALL_AXE" to "DIAMOND_AXE", "ALL_PICKAXE" to "DIAMOND_PICKAXE",
            "ALL_SPADE" to "DIAMOND_SHOVEL", "ALL_HOE" to "DIAMOND_HOE", "ALL_HELMET" to "DIAMOND_HELMET",
            "ALL_CHESTPLATE" to "DIAMOND_CHESTPLATE", "ALL_LEGGINGS" to "DIAMOND_LEGGINGS", "ALL_BOOTS" to "DIAMOND_BOOTS",
            "BOW" to "BOW", "CROSSBOW" to "CROSSBOW", "TRIDENT" to "TRIDENT", "MACE" to "MACE", "FISHING_ROD" to "FISHING_ROD",
            "ELYTRA" to "ELYTRA", "SHIELD" to "SHIELD", "SHEARS" to "SHEARS", "GOAT_HORN" to "GOAT_HORN",
        )
    }
}

/** 한 인첸트의 레벨별 효과. 관리자에게는 부여서 받기 버튼이 있다. */
class EnchantInfoMenu(
    e: Enchants,
    viewer: Player,
    private val id: String,
    override val back: (() -> Unit)?,
) : Menu(e, viewer, 54, "<dark_purple>인첸트 정보</dark_purple>") {

    override fun draw() {
        clear()
        val def = e.registry.get(id) ?: return viewer.closeInventory()
        set(4, enchantIcon(e, def))
        val admin = viewer.hasPermission(EnchantsCommand.ADMIN)
        for ((index, entry) in def.levels.entries.sortedBy { it.key }.take(27).withIndex()) {
            val (number, level) = entry
            val lore = buildList {
                addAll(def.descriptionFor(number).map { "<gray>$it</gray>" })
                add("")
                add("<gray>확률: <white>" + Numbers.chance(level.chance) + "%")
                if (level.cooldown > 0) add("<gray>재사용 대기: <white>" + Numbers.chance(level.cooldown) + "초")
                if (level.souls > 0) add("<gray>영혼: <white>" + level.souls)
                if (level.time > 0) add("<gray>주기: <white>" + level.time + "초")
                level.command?.let { add("<gray>명령어: <white>/" + it) }
                if (level.data.isNotEmpty()) {
                    add("<gray>연동 값:")
                    level.data.forEach { (k, v) -> add("  <dark_gray>▸ <white>$k <gray>= <yellow>$v") }
                }
                if (admin) addAll(listOf("", "<yellow>▶ 클릭: 이 레벨 부여서 받기(100%)"))
            }
            set(18 + index, Icon.of(Material.PAPER, e.display(def, number).ifBlank { e.lore.name(def) }, lore)) {
                if (!admin) return@set
                for (left in viewer.inventory.addItem(e.items.book(def, number, 100, 0)).values) viewer.world.dropItem(viewer.location, left)
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 손에 든 아이템의 인첸트·칸·영혼·추적기. 영혼을 영혼석으로 꺼낼 수 있다. */
class HeldItemMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<dark_green>내 아이템</dark_green>") {

    override val back: (() -> Unit) = { MainMenu(e, viewer).show() }

    override fun draw() {
        clear()
        val held = viewer.inventory.itemInMainHand
        if (held.type.isAir) {
            set(22, Icon.of(Material.BARRIER, "<red>손에 든 것이 없습니다"))
        } else {
            set(4, held.clone())
            val enchants = e.lore.ordered(EnchantStorage.read(held))
            for ((index, pair) in enchants.take(27).withIndex()) {
                val (def, level) = pair
                set(18 + index, Icon.of(Material.ENCHANTED_BOOK, e.display(def, level), def.descriptionFor(level).map { "<gray>$it</gray>" }))
            }
            val pdc = held.itemMeta.persistentDataContainer
            val info = buildList {
                add("<gray>인첸트 칸: <white>" + enchants.size + " / " + e.slots.max(held, viewer))
                if (EnchantStorage.flag(held, Keys.WHITE_SCROLL)) add("<white>화이트 스크롤로 보호됨")
                if (EnchantStorage.flag(held, Keys.SOUL_TRACKER)) add("<gray>영혼: <white>" + e.souls.souls(held))
                for (tracker in Trackers.ALL) pdc.get(tracker.key, PersistentDataType.INTEGER)?.let { add("<gray>" + tracker.label + ": <white>" + it) }
            }
            set(49, Icon.of(Material.BOOK, "<yellow>요약", info))
            if (e.souls.souls(held) > 0 && EnchantStorage.flag(held, Keys.SOUL_TRACKER)) {
                set(50, Icon.of(Material.SOUL_LANTERN, "<dark_red>영혼 꺼내기", "<gray>이 아이템의 영혼을 모두 영혼석으로 꺼냅니다.", "", "<yellow>▶ 클릭")) {
                    val (emptied, gem) = e.uses.withdrawSouls(viewer.inventory.itemInMainHand) ?: return@set
                    viewer.inventory.setItemInMainHand(emptied)
                    for (left in viewer.inventory.addItem(gem).values) viewer.world.dropItem(viewer.location, left)
                    e.messages.send(viewer, "souls-withdrawn", e.ph())
                    refresh()
                }
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}
