package com.inmc.enchants.item

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.EnchantDefinition
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * 인첸트 아이템의 로어를 다시 그린다.
 *
 * ```
 * [우리 윗줄]   인첸트 이름·레벨 (+설명)          ← LORE_TOP 줄
 * [원래 로어]   관리자·다른 플러그인이 쓴 것        ← 건드리지 않는다
 * [우리 아랫줄] 보호됨 · 영혼 · 킬 수 · 칸 수       ← LORE_BOTTOM 줄
 * ```
 *
 * 우리 줄 수를 PDC 에 적어 두고 다음에 그 수만큼 걷어낸다. 글자로 알아보면 관리자가 비슷한
 * 글자를 쓴 로어를 지워버리고, 다른 플러그인이 끼워 넣은 줄의 위치가 흔들린다.
 *
 * **커스텀아이템의 아이템은 그리지 않는다** — 그 로어는 커스텀아이템이 통째로 쥐고 있고(이름 → 종류 → 인첸트 → 능력치 …),
 * 인첸트 줄은 [topLines]·[statusLines] 를 core 로 받아 제자리에 넣는다. 여기서는 다시 그려 달라고만 한다.
 */
class LoreRenderer(private val e: Enchants) {

    fun render(stack: ItemStack) {
        if (stack.type.isAir) return
        if (kr.inmc.core.integration.CustomItemHook.redraw(stack)) return
        val enchants = EnchantStorage.read(stack)
        val config = e.config
        stack.editMeta { meta ->
            val pdc = meta.persistentDataContainer
            val topCount = pdc.get(Keys.LORE_TOP, PersistentDataType.INTEGER) ?: 0
            val bottomCount = pdc.get(Keys.LORE_BOTTOM, PersistentDataType.INTEGER) ?: 0
            val lore = meta.lore() ?: emptyList()
            val middle = if (lore.size >= topCount + bottomCount) lore.subList(topCount, lore.size - bottomCount) else lore

            val top = Text.renderLore(topLines(enchants, forceGroup = pdc.has(Keys.TRANSMOG)))
            val bottom = ArrayList(Text.renderLore(statusLines(pdc)))
            if (config.slotsEnabled && config.slotsInLore && enchants.isNotEmpty()) {
                val max = e.slots.max(stack)
                bottom += Text.renderLore(listOf(config.slotsLine.replace("{used}", enchants.size.toString()).replace("{max}", max.toString()))).first()
            }

            meta.lore(top + middle + bottom)
            if (top.isEmpty()) pdc.remove(Keys.LORE_TOP) else pdc.set(Keys.LORE_TOP, PersistentDataType.INTEGER, top.size)
            if (bottom.isEmpty()) pdc.remove(Keys.LORE_BOTTOM) else pdc.set(Keys.LORE_BOTTOM, PersistentDataType.INTEGER, bottom.size)

            if (config.customEnchantsGlow) {
                meta.setEnchantmentGlintOverride(if (enchants.isNotEmpty()) true else null)
            }
            if (pdc.has(Keys.TRANSMOG)) {
                val base = pdc.get(Keys.CUSTOM_NAME, PersistentDataType.STRING)?.let { GsonComponentSerializer.gson().deserialize(it) }
                    ?: meta.customName() ?: Component.translatable(stack.translationKey())
                if (!pdc.has(Keys.CUSTOM_NAME)) pdc.set(Keys.CUSTOM_NAME, PersistentDataType.STRING, GsonComponentSerializer.gson().serialize(base))
                meta.customName(base.append(Text.renderFlat(e.messages.raw("transmog-suffix").replace("{count}", enchants.size.toString()))))
            }
        }
    }

    /** 인첸트 이름·레벨(+설명) 줄. MiniMessage. */
    fun topLines(enchants: Map<String, Int>, forceGroup: Boolean = false): List<String> = buildList {
        val config = e.config
        for ((def, level) in ordered(enchants, forceGroup)) {
            add(line(config.enchantLine.replace("{name}", name(def)).replace("{level}", levelText(def, level)), def))
            if (config.descriptionsInLore) {
                for (text in def.descriptionFor(level)) add(line(config.descriptionLine.replace("{description}", text), def))
            }
        }
    }

    /** 보호됨 · 영혼 · 킬 수 줄. MiniMessage. */
    fun statusLines(pdc: org.bukkit.persistence.PersistentDataContainer): List<String> = buildList {
        val config = e.config
        if (pdc.has(Keys.WHITE_SCROLL)) add(config.whiteScrollLine)
        if (pdc.has(Keys.SOUL_TRACKER)) add(config.soulsLine.replace("{souls}", (pdc.get(Keys.SOULS, PersistentDataType.INTEGER) ?: 0).toString()))
        for (tracker in Trackers.ALL) {
            val count = pdc.get(tracker.key, PersistentDataType.INTEGER) ?: continue
            add(e.messages.raw(tracker.loreKey).replace("{count}", count.toString()))
        }
    }

    /** 표시 순서. 그룹 정렬이면 귀한 것부터, 아니면 붙인 순서. 모르는 인첸트는 뺀다. */
    fun ordered(enchants: Map<String, Int>, forceGroup: Boolean = false): List<Pair<EnchantDefinition, Int>> {
        val known = enchants.mapNotNull { (id, level) -> e.registry.get(id)?.let { it to level } }
        if (!e.config.sortByGroup && !forceGroup) return known
        return known.sortedWith(compareByDescending<Pair<EnchantDefinition, Int>> { e.groups.get(it.first.group).order }.thenBy { it.first.id })
    }

    /** `%group-color%` 을 푼 이름(색 포함). */
    fun name(def: EnchantDefinition): String {
        val color = e.groups.get(def.group).color
        return def.display.replace("%group-color%", color).replace("{color}", color)
    }

    /** 색을 뺀 이름. 부여서의 `{name}` 이 굵게·밑줄을 입힐 때 쓴다. */
    fun plainName(def: EnchantDefinition): String = Text.plain(name(def))

    fun levelText(def: EnchantDefinition, level: Int): String {
        if (e.config.hideLevelIfOnlyOne && def.maxLevel <= 1) return ""
        return if (e.config.romanNumerals) roman(level) else level.toString()
    }

    private fun line(raw: String, def: EnchantDefinition): String = raw.replace("{color}", e.groups.get(def.group).color).trimEnd()

    companion object {
        private val NUMERALS = listOf(
            1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
        )

        fun roman(value: Int): String {
            if (value <= 0 || value >= 4000) return value.toString()
            var left = value
            return buildString {
                for ((n, s) in NUMERALS) while (left >= n) {
                    append(s)
                    left -= n
                }
            }
        }
    }
}
