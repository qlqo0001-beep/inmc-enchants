package com.inmc.enchants.gui

import com.inmc.enchants.Enchants
import com.inmc.enchants.config.EnchantConfig
import com.inmc.enchants.enchant.Group
import com.inmc.enchants.item.ItemKind
import com.inmc.enchants.item.OrbKind
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 아이템 지급 — 이 플러그인의 모든 아이템을 화면에서 받는다. 좌클릭 1개, Shift 클릭 16개(겹치는 것만).
 * 인자가 필요한 것(부여서의 인첸트·레벨, 등급, 오브 칸, 영혼석 영혼)은 이어지는 화면·채팅으로 묻는다.
 */
class GiveMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<dark_red>아이템 지급</dark_red>") {

    override val back: (() -> Unit) = { AdminMenu(e, viewer).show() }

    private fun give(stack: ItemStack) {
        for (left in viewer.inventory.addItem(stack).values) viewer.world.dropItem(viewer.location, left)
    }

    private fun pickGroup(then: (Group) -> Unit) {
        PickMenu(e, viewer, "<dark_red>등급</dark_red>", e.groups.all(), icon = { Icon.of(Material.NAME_TAG, it.color + it.name) }, back = { show() }) {
            then(it)
            show()
        }.show()
    }

    override fun draw() {
        clear()
        for ((index, kind) in ItemKind.entries.withIndex()) {
            val look = e.items.looks[kind]
            val icon = Icon.of(look?.material ?: Material.ENCHANTED_BOOK, "<yellow>" + kind.label, "", "<yellow>▶ 좌클릭: 1개 · Shift: 16개(겹치는 것만)")
            set(10 + index % 7 + (index / 7) * 9, icon) { event ->
                val count = if (event.isShiftClick) 16 else 1
                when (kind) {
                    ItemKind.BOOK -> PickMenu(e, viewer, "<dark_red>인첸트</dark_red>", e.registry.all(), icon = { enchantIcon(e, it) }, back = { show() }) { def ->
                        PickMenu(e, viewer, "<dark_red>레벨</dark_red>", (1..def.maxLevel).toList(), icon = { Icon.of(Material.PAPER, e.display(def, it)) }, back = { show() }) { level ->
                            give(e.items.randomBook(def, level))
                        }.show()
                    }.show()
                    ItemKind.UNOPENED_BOOK -> pickGroup { give(e.items.unopened(it, count)) }
                    ItemKind.MAGIC_DUST -> pickGroup { repeat(count) { _ -> give(e.items.magicDust(it, e.items.roll(it.dustMin..it.dustMax))) } }
                    ItemKind.SECRET_DUST -> pickGroup { give(e.items.secretDust(it, count)) }
                    ItemKind.RANDOM_SCROLL -> pickGroup { give(e.items.randomScroll(it, count)) }
                    ItemKind.SLOT_INCREASER -> pickGroup { g -> repeat(count) { give(e.items.slotIncreaser(g.slotIncreaser, g)) } }
                    ItemKind.MYSTERY_DUST -> give(e.items.mysteryDust(count))
                    ItemKind.WHITE_SCROLL -> give(e.items.whiteScroll(count))
                    ItemKind.TRANSMOG_SCROLL -> give(e.items.transmogScroll(count))
                    ItemKind.NAMETAG -> give(e.items.nametag(count))
                    ItemKind.SOUL_TRACKER -> give(e.items.soulTracker(count))
                    ItemKind.STATTRAK, ItemKind.MOBTRAK, ItemKind.BLOCKTRAK, ItemKind.FISHTRAK -> give(e.items.tracker(kind, count))
                    ItemKind.BLACK_SCROLL -> repeat(count) { give(e.items.blackScroll(e.items.roll(e.config.blackScrollSuccess))) }
                    ItemKind.ORB -> PickMenu(e, viewer, "<dark_red>오브 종류</dark_red>", OrbKind.entries, icon = { Icon.of(Material.ENDER_EYE, "<yellow>" + it.label) }, back = { show() }) { orb ->
                        Editors.promptInt(e.prompts, viewer, "오브 칸 수", 1, 54, reopen = { show() }) { give(e.items.orb(orb, it)) }
                    }.show()
                    ItemKind.SOUL_GEM -> Editors.promptInt(e.prompts, viewer, "영혼 수", 1, 1_000_000, reopen = { show() }) { give(e.items.soulGem(it)) }
                }
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/**
 * 설정 — `config.yml` 의 값을 화면에서 바꾼다. 파일에 적고 **설정 객체만** 다시 만든다(전체 리로드는
 * 열린 화면을 닫는다).
 */
class SettingsMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<dark_red>설정</dark_red>") {

    override val back: (() -> Unit) = { AdminMenu(e, viewer).show() }

    private sealed interface Entry {
        val path: String
        val label: String
    }

    private data class Toggle(override val path: String, override val label: String) : Entry

    private data class Number(override val path: String, override val label: String, val min: Double, val max: Double, val step: Double) : Entry

    private data class Line(override val path: String, override val label: String, val hint: String) : Entry

    private val entries: List<Entry> = listOf(
        Toggle("slots.enabled", "인첸트 칸 제한"),
        Number("slots.max", "기본 칸 수", 1.0, 54.0, 1.0),
        Number("slots.max-increase", "확장기로 늘릴 수 있는 최대", 0.0, 30.0, 1.0),
        Toggle("books.drag-drop", "부여서 끌어다 놓기"),
        Toggle("books.anvil", "모루에서 부여서"),
        Toggle("books.random-rates", "부여서 확률 무작위"),
        Number("books.success", "고정 성공률", 0.0, 100.0, 5.0),
        Number("books.destroy", "고정 파괴율", 0.0, 100.0, 5.0),
        Toggle("books.destroy-on-fail", "실패하면 파괴율 굴림"),
        Toggle("books.destroy-item", "파괴되면 아이템도 사라짐"),
        Line("limitation.lore", "이 설명이 있으면 인첸트 금지", "예: 수정 불가"),
        Line("limitation.tag", "이 PDC 표식이 있으면 인첸트 금지", "예: otherplugin:unmodifiable"),
        Toggle("combining.enabled", "부여서 합치기"),
        Toggle("combining.use-chances", "합칠 때 두 장 확률 평균"),
        Number("sources.enchanting-table.chance-per-level", "부여대: 레벨당 확률(%)", 0.0, 10.0, 0.5),
        Number("sources.loot.chance", "상자 전리품 확률(%)", 0.0, 100.0, 1.0),
        Number("sources.villagers.chance", "사서 주민 확률(%)", 0.0, 100.0, 1.0),
        Number("tinkerer.restore-hours", "땜장이 되돌리기 시간(시간 · 0 끔)", 0.0, 720.0, 1.0),
        Toggle("effects.break-block-damages-tool", "블록 부수기가 도구를 닳게 함"),
        Toggle("effects.grindstone-removes", "숫돌이 커스텀 인첸트도 지움"),
        Toggle("lore.roman-numerals", "레벨 로마 숫자"),
        Toggle("lore.hide-level-if-only-one", "최대 1레벨이면 숫자 숨김"),
        Toggle("lore.descriptions.enabled", "로어에 설명도"),
        Toggle("lore.glow", "인첸트 아이템 반짝임"),
        Number("souls.per-kill", "처치당 영혼", 0.0, 100.0, 1.0),
        Toggle("souls.from-players", "플레이어 처치로 영혼"),
        Toggle("souls.from-mobs", "몹 처치로 영혼"),
        Number("souls.mining-chance", "채굴로 영혼 확률(%)", 0.0, 100.0, 0.5),
        Number("souls.fishing-chance", "낚시로 영혼 확률(%)", 0.0, 100.0, 0.5),
        Number("combo.window-seconds", "연속 타격 인정 시간(초)", 0.5, 30.0, 0.5),
        Toggle("combo.mobs", "몹의 연속 타격도 셈"),
    )

    private fun write(path: String, value: Any) {
        val file = e.io.file("config.yml")
        val yaml = e.io.load(file)
        yaml.set(path, value)
        e.io.save(file, yaml)
        e.config = EnchantConfig.load(yaml)
    }

    override fun draw() {
        clear()
        val yaml = e.io.load(e.io.file("config.yml"))
        for ((slot, entry) in entries.withIndex()) {
            when (entry) {
                is Toggle -> {
                    val on = yaml.getBoolean(entry.path)
                    set(slot, Icon.of(Icon.toggleMaterial(on), "<yellow>" + entry.label + ": " + Icon.toggle(on), "<dark_gray>" + entry.path)) {
                        write(entry.path, !on)
                        refresh()
                    }
                }
                is Line -> {
                    val value = yaml.getString(entry.path).orEmpty()
                    set(slot, Icon.of(Material.WRITABLE_BOOK, "<yellow>" + entry.label + ": <white>" + value.ifEmpty { "(비어 있음)" },
                        "<dark_gray>" + entry.path, "", "<yellow>▶ 좌클릭: 적기 · 우클릭: 비우기")) { event ->
                        if (event.isRightClick) {
                            write(entry.path, "")
                            refresh()
                            return@set
                        }
                        Editors.promptText(e.prompts, viewer, entry.label, listOf("<gray>" + entry.hint), reopen = { show() }) { write(entry.path, it.trim()) }
                    }
                }
                is Number -> {
                    val value = yaml.getDouble(entry.path)
                    set(slot, Editors.numberIcon(Material.COMPARATOR, "<yellow>" + entry.label, value, extra = listOf("<dark_gray>" + entry.path), stepLabel = kr.inmc.core.util.Numbers.chance(entry.step))) { event ->
                        if (Editors.isPrompt(event)) {
                            Editors.promptDouble(e.prompts, viewer, entry.label, entry.min, entry.max, reopen = { show() }) { write(entry.path, it) }
                            return@set
                        }
                        write(entry.path, (value + Editors.step(event, entry.step)).coerceIn(entry.min, entry.max))
                        refresh()
                    }
                }
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}
