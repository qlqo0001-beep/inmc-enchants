package com.inmc.enchants.gui

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.EnchantLevel
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.set.BonusEffects
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.util.Numbers
import org.bukkit.Material
import org.bukkit.entity.Player

// 세트는 커스텀아이템이 관리한다(사용자 결정 2026-09-25). 여기 남은 것은 커스텀아이템 세트 화면의 "인첸트 효과" 버튼이 여는
// 편집 화면뿐이다(core CustomEnchantHook.editEffects) — 효과 문법을 아는 것이 인첸트뿐이라 화면도 인첸트가 그린다.

/** 적은 글을 줄로. 채팅은 줄바꿈을 못 넣으므로 `\n` 으로 나눈다. */
private fun lines(input: String): List<String> = input.replace("\\n", "\n").split('\n').map(String::trim).filter(String::isNotEmpty)

private fun eventLines(events: Map<Trigger, EnchantLevel>): List<String> =
    events.map { (trigger, level) -> "<gray>· <white>" + trigger.label + " <dark_gray>(" + Numbers.chance(level.chance) + "% · " + level.effects.size + "줄)" }

/**
 * 커스텀아이템 세트 한 단계의 인첸트 효과를 고치는 동안 들고 있는 것. 고칠 때마다 트리로 바꿔 커스텀아이템에 돌려준다([save]) —
 * 세트는 커스텀아이템의 `sets.yml` 에 적힌다. 화면을 오가도 같은 것을 들고 다녀 두 번째 편집이 첫 번째를 지우지 않는다.
 */
class EffectsHolder(
    val title: String,
    initial: BonusEffects,
    private val save: (Map<String, Any?>) -> Unit,
    /** 커스텀아이템 세트 화면으로 돌아가기. */
    val back: () -> Unit,
) {
    @Volatile
    var current: BonusEffects = initial
        private set

    fun update(change: (BonusEffects) -> BonusEffects) {
        current = change(current)
        save(current.toTree())
    }
}

/** 세트 효과의 발동 조건 하나. 인첸트 레벨과 같은 편집 화면을 쓴다. */
class SetEventRef(private val e: Enchants, private val holder: EffectsHolder, private val trigger: Trigger) : LevelRef {

    override val title = holder.title + " - " + trigger.label

    override val enchant = false

    override fun get(): EnchantLevel? = holder.current.events[trigger]

    override fun set(level: EnchantLevel) = holder.update { it.copy(events = it.events + (trigger to level)) }

    override fun back(viewer: Player) = EventListMenu(e, viewer, holder).show()
}

/** 세트 한 단계의 인첸트 효과 — 발동 조건들 · 붙을 때/떨어질 때 알림 · 꺼진 월드. */
class BonusEffectsMenu(e: Enchants, viewer: Player, private val holder: EffectsHolder) :
    Menu(e, viewer, 54, "<dark_red>세트 효과 - ${holder.title}</dark_red>") {

    override val back: (() -> Unit) = { holder.back() }

    private fun ask(label: String, hints: List<String>, apply: (String, BonusEffects) -> BonusEffects) {
        Editors.promptText(e.prompts, viewer, label, hints, reopen = { show() }) { input -> holder.update { apply(input, it) } }
    }

    override fun draw() {
        clear()
        val effects = holder.current
        set(4, Icon.of(Material.ENCHANTED_BOOK, "<light_purple>" + holder.title, listOf(
            "<gray>이 단계(몇 벌)를 입고 들면 도는 인첸트 효과입니다.",
            "<gray>효과 줄 문법은 인첸트와 같습니다.",
            "<dark_gray>세트 자체(이름·아이템·능력치)는 커스텀아이템에서 고칩니다.",
        )))
        set(20, Icon.of(Material.BLAZE_POWDER, "<yellow>발동 조건 " + effects.events.size + "개", eventLines(effects.events) +
            listOf("<gray>EFFECT_STATIC 은 \"입고 있는 동안\".", "", "<yellow>▶ 클릭"))) { EventListMenu(e, viewer, holder).show() }
        set(22, Icon.of(Material.LIME_DYE, "<yellow>붙을 때 알림", effects.equipped.map { "<gray>$it" } + listOf("", "<yellow>▶ 좌클릭: 적기(\\n 으로 줄) · 우클릭: 비우기"))) { event ->
            if (event.isRightClick) {
                holder.update { it.copy(equipped = emptyList()) }
                refresh()
                return@set
            }
            ask("붙을 때 알림", listOf("<gray>예: <white><green>서리 거인 세트가 깨어났습니다.")) { input, s -> s.copy(equipped = lines(input)) }
        }
        set(23, Icon.of(Material.GRAY_DYE, "<yellow>떨어질 때 알림", effects.unequipped.map { "<gray>$it" } + listOf("", "<yellow>▶ 좌클릭: 적기(\\n 으로 줄) · 우클릭: 비우기"))) { event ->
            if (event.isRightClick) {
                holder.update { it.copy(unequipped = emptyList()) }
                refresh()
                return@set
            }
            ask("떨어질 때 알림", emptyList()) { input, s -> s.copy(unequipped = lines(input)) }
        }
        set(24, Icon.of(Material.GRASS_BLOCK, "<yellow>꺼진 월드", effects.disabledWorlds.map { "<gray>· $it" } + listOf("", "<yellow>▶ 좌클릭: 적기(쉼표) · 우클릭: 비우기"))) { event ->
            if (event.isRightClick) {
                holder.update { it.copy(disabledWorlds = emptyList()) }
                refresh()
                return@set
            }
            ask("꺼진 월드", listOf("<gray>쉼표로 나눕니다.")) { input, s -> s.copy(disabledWorlds = input.split(',').map(String::trim).filter(String::isNotEmpty)) }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 세트 효과의 발동 조건 목록. 하나를 누르면 인첸트 레벨과 같은 편집 화면이 열린다. */
class EventListMenu(e: Enchants, viewer: Player, private val holder: EffectsHolder) :
    Menu(e, viewer, 54, "<dark_red>발동 조건 - ${holder.title}</dark_red>") {

    override val back: (() -> Unit) = { BonusEffectsMenu(e, viewer, holder).show() }

    override fun draw() {
        clear()
        val events = holder.current.events
        for ((index, entry) in events.entries.take(36).withIndex()) {
            val (trigger, level) = entry
            set(index, Icon.of(Material.REPEATER, "<yellow>" + trigger.label, listOf(
                "<gray>확률 <white>" + Numbers.chance(level.chance) + "%<gray> · 대기 <white>" + Numbers.chance(level.cooldown) + "초",
            ) + level.effects.take(6).map { "<gray>· <white>" + it.raw } + listOf("", "<yellow>▶ 좌클릭: 편집 · <red>우클릭: 지우기"))) { event ->
                if (event.isRightClick) {
                    holder.update { it.copy(events = it.events - trigger) }
                    refresh()
                    return@set
                }
                LevelEditMenu(e, viewer, SetEventRef(e, holder, trigger)).show()
            }
        }
        set(49, Icon.of(Material.LIME_DYE, "<green>발동 조건 추가", "<gray>고르면 빈 효과로 만들어지고 편집 화면이 열립니다.")) {
            val taken = events.keys
            PickMenu(e, viewer, "<dark_red>발동 조건</dark_red>", Trigger.entries.filter { it !in taken },
                icon = { Icon.of(Material.REPEATER, "<yellow>" + it.label, "<gray>" + it.description, "<dark_gray>" + it.name) },
                back = { show() },
            ) { trigger ->
                holder.update { it.copy(events = it.events + (trigger to EnchantLevel())) }
                LevelEditMenu(e, viewer, SetEventRef(e, holder, trigger)).show()
            }.show()
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}
