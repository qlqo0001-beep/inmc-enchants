package com.inmc.enchants.gui

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.item.EnchantRoles
import com.inmc.enchants.item.ItemKind
import com.inmc.enchants.item.ScrollEnchant
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.integration.ItemRoles
import kr.inmc.core.item.ItemRef
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** 강화 스크롤이 올리는 인첸트의 아이콘 — 우리 것은 도감과 같은 모양, 바닐라는 이름과 스크롤 최대 레벨. */
internal fun scrollEnchantIcon(e: Enchants, target: ScrollEnchant, extra: List<String> = emptyList()): ItemStack = when (target) {
    is ScrollEnchant.Custom -> enchantIcon(e, target.def, extra)
    is ScrollEnchant.Vanilla -> Icon.of(Material.ENCHANTED_BOOK, "<aqua>" + e.scrolls.label(target), buildList {
        add("<gray>바닐라 인첸트 <dark_gray>" + target.id)
        add("<gray>스크롤 최대 레벨: <white>" + e.scrolls.maxLevel(target))
        addAll(extra)
    })
}

/**
 * 강화 스크롤 값 고르기 — 인첸트 → 성공 확률 → 하락 확률. 지급과 커스텀아이템에 올리기가 같이 쓴다.
 * 두 확률은 채팅으로 잇달아 묻는다(사이에 화면을 다시 열면 두 번째 입력을 칠 수 없다). 끝나면 [reopen].
 */
internal fun pickLevelScroll(e: Enchants, viewer: Player, reopen: () -> Unit, then: (ScrollEnchant, Int, Int) -> Unit) {
    PickMenu(e, viewer, "<dark_red>강화할 인첸트</dark_red>", e.scrolls.all(), icon = { scrollEnchantIcon(e, it) }, back = reopen) { target ->
        val id = target.id
        e.prompts.requestInt(viewer, listOf("<yellow>강화 성공 확률(%)을 입력하세요.</yellow>", "<gray>범위: <white>0 ~ 100</white></gray>"), 0, 100, onCancel = reopen) { success ->
            e.prompts.requestInt(viewer, listOf("<yellow>실패했을 때 한 레벨 떨어질 확률(%)을 입력하세요.</yellow>", "<gray>범위: <white>0 ~ 100</white> · 0 이면 떨어지지 않습니다.</gray>"), 0, 100, onCancel = reopen) { downgrade ->
                // 묻는 사이에 인첸트가 지워졌을 수 있다 — id 로 다시 찾는다.
                e.scrolls.resolve(id)?.let { then(it, success, downgrade) }
                reopen()
            }
        }
    }.show()
}

/** 강화 스크롤 지급. 아이템 지급 화면과 강화 스크롤 화면이 같이 쓴다. */
internal fun giveLevelScroll(e: Enchants, viewer: Player, count: Int, reopen: () -> Unit) =
    pickLevelScroll(e, viewer, reopen) { target, success, downgrade ->
        for (left in viewer.inventory.addItem(e.items.levelScroll(target, success, downgrade, count)).values) viewer.world.dropItem(viewer.location, left)
    }

/**
 * 강화 스크롤을 **커스텀아이템 아이템으로** 올린다(core [ItemRoles.adopt] → [ItemRoles.assign]) — 상점은 그 아이템을 판다.
 * 커스텀아이템의 역할 화면에서 인첸트를 고르는 것과 같은 결과다(역할 `enchants.item`, 종류 `level-scroll`).
 */
internal fun registerLevelScroll(e: Enchants, viewer: Player, reopen: () -> Unit) =
    pickLevelScroll(e, viewer, reopen) { target, success, downgrade ->
        val ref = ItemRoles.adopt(e.items.levelScroll(target, success, downgrade), "강화스크롤_" + target.id.replace(':', '_'))
        val values = mapOf("kind" to ItemKind.LEVEL_SCROLL.id, "enchant" to target.id, "success" to success.toString(), "downgrade" to downgrade.toString())
        if (ref != null && ItemRoles.assign(ref, EnchantRoles.ITEM, values)) {
            e.messages.send(viewer, "scroll-registered", e.ph().item(ref.id))
        } else {
            e.messages.send(viewer, "scroll-register-failed", e.ph())
        }
    }

/**
 * 관리 → 강화 스크롤. **금지 목록**(어떤 아이템에 어떤 인첸트를 스크롤로 붙이거나 올리면 안 되는지)·바닐라 최대 레벨·지급.
 *
 * 규칙은 대상 글자(재질·종류 묶음·커스텀아이템)로 들고 있다 — 화면이 규칙 객체를 붙들지 않는다.
 */
class ScrollAdminMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<dark_red>강화 스크롤</dark_red>") {

    override val back: (() -> Unit) = { AdminMenu(e, viewer).show() }

    override fun draw() {
        clear()
        for ((index, entry) in e.scrolls.rules().entries.take(36).withIndex()) {
            val (key, enchants) = entry
            val names = enchants.map { id -> e.scrolls.resolve(id)?.let { e.scrolls.label(it) } ?: "<dark_gray>$id (없음)" }
            val lore = buildList {
                add("<gray>" + targetLabel(key))
                add("")
                if (names.isEmpty()) add("<dark_gray>금지한 인첸트가 없습니다.") else {
                    add("<red>스크롤로 붙이거나 올릴 수 없는 것:")
                    names.take(12).forEach { add("<gray>- $it") }
                    if (names.size > 12) add("<gray>…" + (names.size - 12) + "개 더")
                }
                add("")
                add("<yellow>▶ 클릭: 금지 인첸트 고르기")
                add("<red>▶ Shift+우클릭: 규칙 지우기")
            }
            set(index, Icon.annotate(targetIcon(e, key), "<yellow>$key", lore)) { event ->
                if (event.isShiftClick && event.isRightClick) {
                    ConfirmMenu(e, "<red>$key 규칙을 지울까요?", onConfirm = {
                        e.scrolls.removeRule(key)
                        show()
                    }, onCancel = { show() }).open(viewer)
                    return@set
                }
                editRule(e, viewer, key) { show() }
            }
        }
        set(40, Icon.of(Material.BOOK, "<yellow>금지 목록이란",
            "<gray>강화 스크롤은 없는 인첸트도 1레벨로 붙입니다.",
            "<gray>여기에 적은 아이템에는 고른 인첸트를",
            "<gray>스크롤로 붙이지도 올리지도 못합니다.",
            "",
            "<gray>대상: 재질 · 종류 묶음(ALL_SWORD 등) · 커스텀아이템"))
        if (ItemRoles.active) {
            set(47, Icon.of(Material.NETHER_STAR, "<green>커스텀아이템에 올리기", "<gray>인첸트 → 성공 확률 → 하락 확률로 만든 스크롤을",
                "<gray>커스텀아이템 아이템으로 등록합니다(상점에서 팝니다).", "<gray>겉모습은 커스텀아이템에서 바꿉니다.", "", "<yellow>▶ 클릭")) {
                registerLevelScroll(e, viewer) { show() }
            }
        }
        set(48, Icon.of(Material.PAPER, "<green>강화 스크롤 지급", "<gray>인첸트 → 성공 확률 → 하락 확률", "", "<yellow>▶ 클릭")) {
            giveLevelScroll(e, viewer, 1) { show() }
        }
        set(49, Icon.of(Material.IRON_SWORD, "<green>손에 든 아이템으로 규칙 추가", "<gray>커스텀아이템이면 그 아이템, 아니면 재질", "", "<yellow>▶ 클릭")) {
            addFromHand()
        }
        set(50, Icon.of(Material.NAME_TAG, "<green>종류 묶음으로 규칙 추가", "<gray>검 전부·방어구 전부 …", "", "<yellow>▶ 클릭")) {
            val presets = (Applicability.PRESETS.map { it.first } + e.config.appliesGroups.keys).distinct()
            PickMenu(e, viewer, "<dark_red>종류 묶음</dark_red>", presets, icon = { Icon.of(Material.NAME_TAG, "<yellow>" + targetLabel(it), "<gray>$it") }, back = { show() }) { key ->
                editRule(e, viewer, e.scrolls.addRule(key)) { show() }
            }.show()
        }
        set(51, Icon.of(Material.ENCHANTING_TABLE, "<green>바닐라 최대 레벨", "<gray>스크롤로 올릴 수 있는 바닐라 인첸트의 최대 레벨", "", "<yellow>▶ 클릭")) {
            editVanillaMax(e, viewer)
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }

    private fun addFromHand() {
        val held = viewer.inventory.itemInMainHand
        if (held.type.isAir) {
            e.messages.send(viewer, "hand-empty", e.ph())
            return
        }
        val options = e.customItems.identifyAll(held).map { it.serialize() } + held.type.name
        if (options.size == 1) {
            editRule(e, viewer, e.scrolls.addRule(options.single())) { show() }
            return
        }
        PickMenu(e, viewer, "<dark_red>무엇을 기준으로</dark_red>", options, icon = { Icon.of(if (':' in it) Material.NETHER_STAR else held.type, "<yellow>" + targetLabel(it), "<gray>$it") }, back = { show() }) { key ->
            editRule(e, viewer, e.scrolls.addRule(key)) { show() }
        }.show()
    }

    companion object {

        /** 대상 글자를 사람이 읽는 말로. */
        fun targetLabel(key: String): String = when {
            ':' in key -> "커스텀아이템 $key"
            Material.matchMaterial(key) != null -> "재질 $key"
            else -> "종류 묶음 " + (Applicability.PRESETS.firstOrNull { it.first == key }?.second ?: key)
        }

        /** 대상의 견본. 종류 묶음은 견본이 없다. */
        fun sample(e: Enchants, key: String): ItemStack? {
            if (':' in key) {
                val (namespace, id) = key.split(':', limit = 2)
                return e.customItems.create(ItemRef.Namespaced(namespace, id))
            }
            return Material.matchMaterial(key)?.takeIf { it.isItem }?.let { ItemStack(it) }
        }

        fun targetIcon(e: Enchants, key: String): ItemStack = sample(e, key) ?: ItemStack(Material.NAME_TAG)

        /**
         * 규칙 하나의 금지 인첸트를 켜고 끈다. 견본이 있으면 그 아이템에 붙을 수 있는 인첸트만 보인다(이미 켠 것은 늘 보인다).
         */
        fun editRule(e: Enchants, viewer: Player, key: String, back: () -> Unit) {
            val sample = sample(e, key)
            val chosen = { e.scrolls.rules()[key].orEmpty() }
            val options = e.scrolls.all().filter { target ->
                sample == null || target.id in chosen() || when (target) {
                    is ScrollEnchant.Custom -> Applicability.matchesAny(target.def.applies, sample.type.name, e.config.appliesGroups)
                    is ScrollEnchant.Vanilla -> target.enchantment.canEnchantItem(sample)
                }
            }
            PickMenu(
                e, viewer, "<dark_red>금지 - $key</dark_red>", options,
                icon = { scrollEnchantIcon(e, it) },
                multi = true,
                selected = { options.filter { it.id in chosen() }.toSet() },
                back = back,
            ) { target -> e.scrolls.toggle(key, target.id) }.show()
        }
    }
}

/** 바닐라 인첸트마다 스크롤 최대 레벨. 0 을 적으면 바닐라 값으로 되돌린다. */
internal fun editVanillaMax(e: Enchants, viewer: Player) {
    PickMenu(e, viewer, "<dark_red>바닐라 최대 레벨</dark_red>", e.scrolls.vanilla(), icon = { enchantment ->
        val override = e.scrolls.vanillaMaxOverride(enchantment)
        scrollEnchantIcon(e, ScrollEnchant.Vanilla(enchantment), listOf(
            "<gray>바닐라 최대: <white>" + enchantment.maxLevel + (if (override != null) " <yellow>(바꿈)" else ""),
            "", "<yellow>▶ 클릭: 바꾸기 (0 = 바닐라 값)",
        ))
    }, back = { ScrollAdminMenu(e, viewer).show() }) { enchantment ->
        Editors.promptInt(e.prompts, viewer, ScrollEnchant.Vanilla(enchantment).id + " 스크롤 최대 레벨", 0, 255, reopen = { editVanillaMax(e, viewer) }) {
            e.scrolls.setVanillaMax(enchantment, it.takeIf { level -> level > 0 })
        }
    }.show()
}
