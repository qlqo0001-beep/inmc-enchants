package com.inmc.enchants.set

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.EnchantDefinition
import kr.inmc.core.integration.CustomItemHook
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 커스텀아이템 세트의 **인첸트 효과**를 엔진에 태운다.
 *
 * 누가 몇 벌을 입었는지는 커스텀아이템이 센다(core [CustomItemHook.setEffects] — 커스텀아이템의 능력치 캐시에서 답한다).
 * 여기서는 그 단계가 들고 온 트리를 [BonusEffects] 로 읽어 정의로 바꿔 둔다. 트리는 고치기 전까지 같은 객체라서(core 의 약속)
 * **객체가 같으면 다시 읽지 않는다** — 사건마다 불리기 때문이다.
 *
 * 세트 효과의 "아이템"은 흉갑이다(내구도를 깎는 효과가 어디를 깎을지).
 */
class SetService(private val e: Enchants) {

    private class Resolved(val tree: Map<String, Any?>, val effects: BonusEffects, val definitions: List<EnchantDefinition>)

    /** `세트:벌 수` → 읽어 둔 것. */
    private val resolved = ConcurrentHashMap<String, Resolved>()

    /**
     * 정의 id → 정의. **지우지 않는다** — 세트를 벗은 뒤 지속 효과를 떼려면([com.inmc.enchants.engine.StaticEffects]) 그 정의가
     * 남아 있어야 한다. 세트 단계 수만큼이라 작다.
     */
    private val byId = ConcurrentHashMap<String, EnchantDefinition>()

    /** 사람 → 마지막으로 알린 단계들(`세트:벌 수`). 붙고 떨어진 것을 알릴 때 비교한다. */
    private val announced = ConcurrentHashMap<UUID, Set<String>>()

    /** 검증기가 입히지 않고 엔진 쪽만 볼 때 대신 쓰는 단계들. null 이면 커스텀아이템에 묻는다. */
    @Volatile
    var forced: List<CustomItemHook.SetEffects>? = null

    /** 리로드 — 효과 줄 문법이 바뀌었을 수 있으니 다시 읽게 한다. */
    fun rebuild() {
        resolved.clear()
    }

    fun definition(id: String): EnchantDefinition? = byId[id]

    fun bonuses(player: Player): List<CustomItemHook.SetEffects> = forced ?: CustomItemHook.setEffects(player)

    /** PlaceholderAPI 의 `set`·`set_id` — 붙은 단계 가운데 벌 수가 가장 많은 것. */
    fun worn(player: Player): CustomItemHook.SetEffects? = bonuses(player).maxByOrNull { it.pieces }

    private fun resolve(bonus: CustomItemHook.SetEffects): Resolved {
        val key = bonus.set + ":" + bonus.pieces
        resolved[key]?.let { if (it.tree === bonus.effects) return it }
        val effects = BonusEffects.of(bonus.effects) { e.logger.warning("세트 $key: $it") }
        val definitions = effects.definitions("set:$key", bonus.name)
        for (definition in definitions) byId[definition.id] = definition
        return Resolved(bonus.effects, effects, definitions).also { resolved[key] = it }
    }

    /** 지금 도는 세트 효과의 정의와 그 칸. 사람만 — 세트를 세는 것은 커스텀아이템이고 커스텀아이템은 사람만 센다. */
    fun active(holder: LivingEntity): List<Pair<EnchantDefinition, EquipmentSlot>> {
        val player = holder as? Player ?: return emptyList()
        val bonuses = bonuses(player)
        if (bonuses.isEmpty()) return emptyList()
        val out = ArrayList<Pair<EnchantDefinition, EquipmentSlot>>()
        for (bonus in bonuses) for (definition in resolve(bonus).definitions) out += definition to EquipmentSlot.CHEST
        return out
    }

    /** 붙은 단계가 바뀌었으면 알린다. 지속 효과를 맞출 때마다 불린다. */
    fun announce(player: Player) {
        val bonuses = bonuses(player)
        val now = bonuses.mapTo(HashSet()) { it.set + ":" + it.pieces }
        val before = announced[player.uniqueId].orEmpty()
        if (now == before) return
        if (now.isEmpty()) announced.remove(player.uniqueId) else announced[player.uniqueId] = now
        for (key in before - now) resolved[key]?.effects?.unequipped?.forEach { player.sendMessage(e.text(it)) }
        for (bonus in bonuses) {
            if (bonus.set + ":" + bonus.pieces in before) continue
            resolve(bonus).effects.equipped.forEach { player.sendMessage(e.text(it)) }
        }
    }

    fun forget(player: UUID) {
        announced.remove(player)
    }
}
