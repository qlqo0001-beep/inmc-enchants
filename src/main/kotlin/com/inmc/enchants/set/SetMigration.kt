package com.inmc.enchants.set

import com.inmc.enchants.Enchants
import com.inmc.enchants.item.EnchantStorage
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.core.integration.ItemRoles
import kr.inmc.core.item.ItemRef
import kr.inmc.core.util.Text
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.LeatherArmorMeta

/**
 * 옛 `sets.yml`(인첸트가 방어구 세트·세트 무기를 직접 관리하던 때)을 **커스텀아이템 세트로 한 번 옮긴다**(사용자 결정 2026-09-25).
 *
 * - 네 부위·무기는 커스텀아이템 아이템이 된다(이름·설명·모델 번호·숨김·가죽 색·바닐라 인챈트·커스텀 인첸트).
 * - 세트 효과는 커스텀아이템 세트의 **4벌** 단계에 인첸트 효과로, 그 세트가 필요한 무기의 효과는 **5벌**(네 부위 + 든 무기)에.
 * - 세트가 필요 없는 무기(또는 같은 세트의 두 번째 무기)는 제 이름의 세트가 되고 **1벌** 에 효과가 붙는다.
 *
 * 커스텀아이템이 있을 때만, 파일이 있고 아직 안 옮겼을 때만 돈다. 끝나면 원본은 `sets.yml.migrated` 가 되고 다시는 읽지 않는다.
 */
object SetMigration {

    const val FILE = "sets.yml"

    /** 커스텀아이템 세트 하나로 갈 것. */
    data class Plan(
        val id: String,
        val name: String,
        val armor: ArmorSet?,
        val weapons: List<SetWeapon>,
        /** 벌 수 → 인첸트 효과. */
        val effects: Map<Int, BonusEffects>,
        /** 세트가 필요했는데 따로 세트가 된 무기(그 조건은 옮기지 못했다). */
        val lostRequirement: Boolean = false,
    )

    /** 무엇을 어느 세트의 몇 벌로 옮길지. 서버 없이 도는 순수 계산이다. */
    fun plan(sets: List<ArmorSet>, weapons: List<SetWeapon>): List<Plan> {
        val armorPieces = Piece.entries.size
        val byId = sets.associateBy { it.id }
        val joined = LinkedHashMap<String, SetWeapon>()
        val alone = ArrayList<SetWeapon>()
        for (weapon in weapons) {
            val set = byId[weapon.requiredSet]
            if (set != null && set.id !in joined) joined[set.id] = weapon else alone += weapon
        }
        val taken = sets.mapTo(HashSet()) { it.id }
        val out = ArrayList<Plan>()
        for (set in sets) {
            val weapon = joined[set.id]
            val effects = LinkedHashMap<Int, BonusEffects>()
            effects[armorPieces] = BonusEffects(set.events, set.equipped, set.unequipped, set.disabledWorlds)
            if (weapon != null) effects[armorPieces + 1] = BonusEffects(weapon.events)
            out += Plan(set.id, set.name, set, listOfNotNull(weapon), effects.filterValues { !it.isEmpty })
        }
        for (weapon in alone) {
            var id = weapon.id
            if (id in taken) id += "_weapon"
            taken += id
            val effects = if (weapon.events.isEmpty()) emptyMap() else mapOf(1 to BonusEffects(weapon.events))
            out += Plan(id, weapon.gear.name.ifBlank { weapon.id }, null, listOf(weapon), effects, lostRequirement = weapon.requiredSet.isNotEmpty())
        }
        return out
    }

    /** 커스텀아이템이 꽂혀 있고 옮길 파일이 있으면 옮긴다. 인첸트 정의를 다 읽은 뒤에 부를 것(부위의 커스텀 인첸트를 본다). */
    fun run(e: Enchants) {
        if (!ItemRoles.active) return
        val file = e.io.file(FILE)
        if (!file.isFile || ItemRoles.isRetired(file)) return
        val yaml = e.io.load(file)
        val problems = ArrayList<String>()
        val sets = yaml.getConfigurationSection("sets")?.let { node ->
            node.getKeys(false).mapNotNull { key -> node.getConfigurationSection(key)?.let { ArmorSet.load(key.lowercase(), it) { p -> problems += p } } }
        }.orEmpty()
        val weapons = yaml.getConfigurationSection("weapons")?.let { node ->
            node.getKeys(false).mapNotNull { key -> node.getConfigurationSection(key)?.let { SetWeapon.load(key.lowercase(), it) { p -> problems += p } } }
        }.orEmpty()
        for (problem in problems.take(20)) e.logger.warning("옛 세트 파일: $problem")

        for (plan in plan(sets, weapons)) {
            val members = ArrayList<ItemRef.Namespaced>()
            plan.armor?.let { set -> for (piece in Piece.entries) ItemRoles.adopt(piece(e, set, piece), set.id + "_" + piece.id)?.let(members::add) }
            for (weapon in plan.weapons) ItemRoles.adopt(weapon(e, weapon), weapon.id)?.let(members::add)
            if (!ItemRoles.defineSet(plan.id, plan.name, plan.effects.mapValues { it.value.toTree() }, members)) {
                e.logger.warning("커스텀아이템에 세트 '${plan.id}' 가 이미 있어 효과는 옮기지 않았습니다(아이템은 옮겼습니다)")
            }
            if (plan.lostRequirement) {
                e.logger.warning("무기 '${plan.weapons.first().id}' 는 제 세트('${plan.id}')가 됐습니다 — '필요한 세트' 조건은 옮기지 못했습니다")
            }
        }
        ItemRoles.retire(file)
        e.logger.info("방어구 세트 ${sets.size}개·세트 무기 ${weapons.size}개를 커스텀아이템 세트로 옮겼습니다(원본은 $FILE.migrated)")
    }

    // --- 옮길 아이템 -------------------------------------------------------------------------

    fun piece(e: Enchants, set: ArmorSet, piece: Piece): ItemStack {
        val material = Material.matchMaterial(set.material.name + piece.suffix) ?: Material.LEATHER_HELMET
        val stack = build(e, material, set.gear(piece))
        color(set.color)?.let { rgb -> stack.editMeta(LeatherArmorMeta::class.java) { it.setColor(rgb) } }
        return stack
    }

    fun weapon(e: Enchants, weapon: SetWeapon): ItemStack = build(e, Material.matchMaterial(weapon.material) ?: Material.IRON_SWORD, weapon.gear)

    /**
     * 겉모습대로 만든다. 인첸트 범위(`1-3`)는 **가장 높은 값** — 커스텀아이템의 인첸트는 굴리지 않아 하나로 정해야 한다.
     * 로어에 인첸트 줄을 얹지 않는다 — 커스텀아이템이 제 순서로 그리고, 얹으면 설명에 같은 줄이 두 번 옮겨진다.
     */
    private fun build(e: Enchants, material: Material, gear: Gear): ItemStack {
        val stack = ItemStack(material)
        val custom = LinkedHashMap<String, Int>()
        stack.editMeta { meta ->
            if (gear.name.isNotBlank()) meta.displayName(Text.renderFlat(gear.name))
            if (gear.lore.isNotEmpty()) meta.lore(Text.renderLore(gear.lore))
            @Suppress("DEPRECATION")
            if (gear.customModelData > 0) meta.setCustomModelData(gear.customModelData)
            for (flag in gear.flags) runCatching { meta.addItemFlags(ItemFlag.valueOf(flag)) }
            for (raw in gear.enchants) {
                val (name, range) = Gear.roll(raw) ?: continue
                // 같은 이름이면 커스텀 인첸트가 먼저다 — 관리자가 만든 것이 바닐라를 가리는 쪽이 옛 동작이었다.
                val def = e.registry.get(name)
                if (def != null) custom[def.id] = range.last.coerceAtMost(def.maxLevel.coerceAtLeast(1))
                else vanilla(name)?.let { meta.addEnchant(it, range.last, true) }
            }
        }
        if (custom.isNotEmpty()) EnchantStorage.write(stack, custom)
        return stack
    }

    private fun vanilla(name: String): Enchantment? {
        val key = NamespacedKey.fromString(name.lowercase()) ?: return null
        return runCatching { RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key) }.getOrNull()
    }

    private fun color(hex: String): Color? =
        hex.trim().removePrefix("#").takeIf { it.isNotEmpty() }?.toIntOrNull(16)?.takeIf { it in 0..0xFFFFFF }?.let(Color::fromRGB)
}
