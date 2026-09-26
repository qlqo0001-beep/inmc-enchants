package com.inmc.enchants.item

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.Applicability
import kr.inmc.core.integration.CustomEnchantHook
import org.bukkit.inventory.ItemStack

/**
 * core [CustomEnchantHook] 에 꽂는 공급처. 커스텀아이템이 인첸트를 고르고 붙이고, 낚시가 낚싯대의
 * 인첸트 수치를 읽는 길이다.
 *
 * 레지스트리를 **그때그때** 읽는다 — 리로드로 정의가 바뀌어도 다시 꽂을 필요가 없다.
 */
class EnchantProvider(private val e: Enchants) : CustomEnchantHook.Provider {

    override fun all(): List<CustomEnchantHook.Info> = e.registry.all().map { def ->
        CustomEnchantHook.Info(def.id, e.lore.name(def), def.maxLevel, def.group, def.appliesTo, def.description)
    }

    override fun canApply(id: String, material: String): Boolean {
        val def = e.registry.get(id) ?: return false
        return Applicability.matchesAny(def.applies, material, e.config.appliesGroups)
    }

    override fun levels(stack: ItemStack?): Map<String, Int> = EnchantStorage.read(stack)

    override fun apply(stack: ItemStack, id: String, level: Int): Boolean {
        val def = e.registry.get(id) ?: return false
        val enchants = EnchantStorage.read(stack)
        enchants[def.id] = level.coerceIn(1, def.maxLevel.coerceAtLeast(1))
        EnchantStorage.write(stack, enchants)
        e.lore.render(stack)
        return true
    }

    override fun remove(stack: ItemStack, id: String): Boolean {
        val enchants = EnchantStorage.read(stack)
        if (enchants.remove(id.lowercase()) == null) return false
        EnchantStorage.write(stack, enchants)
        e.lore.render(stack)
        return true
    }

    /** 우리 줄 수 기억(`lore_top`·`lore_bottom`)을 버리고 다시 그린다. 우리 흔적이 없는 아이템은 건드리지 않는다. */
    override fun redraw(stack: ItemStack) {
        if (!stack.hasItemMeta()) return
        val pdc = stack.itemMeta.persistentDataContainer
        if (!pdc.has(Keys.ENCHANTS) && !pdc.has(Keys.LORE_TOP) && !pdc.has(Keys.LORE_BOTTOM)) return
        stack.editMeta { meta ->
            meta.persistentDataContainer.remove(Keys.LORE_TOP)
            meta.persistentDataContainer.remove(Keys.LORE_BOTTOM)
        }
        e.lore.render(stack)
    }

    override fun lines(stack: ItemStack): CustomEnchantHook.EnchantLines {
        if (!stack.hasItemMeta()) return CustomEnchantHook.EnchantLines()
        val pdc = stack.itemMeta.persistentDataContainer
        return CustomEnchantHook.EnchantLines(e.lore.topLines(EnchantStorage.read(stack), pdc.has(Keys.TRANSMOG)), e.lore.statusLines(pdc))
    }

    override fun slots(stack: ItemStack): Pair<Int, Int>? =
        if (!e.config.slotsEnabled || stack.type.isAir) null else EnchantStorage.read(stack).size to e.slots.max(stack)

    /** 커스텀아이템 세트 화면의 "인첸트 효과" — 효과 문법을 아는 우리가 화면을 그리고, 고친 트리를 돌려준다. */
    override fun editEffects(viewer: org.bukkit.entity.Player, title: String, effects: Map<String, Any?>, save: (Map<String, Any?>) -> Unit, back: () -> Unit): Boolean {
        val current = com.inmc.enchants.set.BonusEffects.of(effects) { e.logger.warning("세트 효과($title): $it") }
        com.inmc.enchants.gui.BonusEffectsMenu(e, viewer, com.inmc.enchants.gui.EffectsHolder(title, current, save, back)).show()
        return true
    }

    override fun describeEffects(effects: Map<String, Any?>): String = com.inmc.enchants.set.BonusEffects.of(effects).describe()

    override fun data(stack: ItemStack?): Map<String, String> {
        val parts = EnchantStorage.read(stack).mapNotNull { (id, level) -> e.registry.get(id)?.level(level)?.data }
        if (parts.isEmpty()) return emptyMap()
        return CustomEnchantHook.merge(*parts.toTypedArray())
    }
}
