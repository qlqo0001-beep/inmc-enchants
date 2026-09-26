package com.inmc.enchants.item

import com.inmc.enchants.Enchants
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** 추적기 넷. 아이템에 붙이면 로어 아래에 수가 뜬다. */
object Trackers {
    data class Tracker(val id: String, val key: NamespacedKey, val loreKey: String, val label: String)

    val STAT = Tracker("stattrak", Keys.STAT_TRAK, "tracker-stattrak", "킬 추적기")
    val MOB = Tracker("mobtrak", Keys.MOB_TRAK, "tracker-mobtrak", "몹 추적기")
    val BLOCK = Tracker("blocktrak", Keys.BLOCK_TRAK, "tracker-blocktrak", "블록 추적기")
    val FISH = Tracker("fishtrak", Keys.FISH_TRAK, "tracker-fishtrak", "낚시 추적기")
    val ALL = listOf(STAT, MOB, BLOCK, FISH)

    /** 붙어 있으면 1 올린다. 로어도 다시 그린다. */
    fun bump(e: Enchants, holder: LivingEntity, slot: EquipmentSlot, tracker: Tracker, amount: Int = 1) {
        val stack = holder.equipment?.getItem(slot) ?: return
        if (stack.type.isAir || !stack.hasItemMeta()) return
        val current = stack.itemMeta.persistentDataContainer.get(tracker.key, PersistentDataType.INTEGER) ?: return
        EnchantStorage.setInt(stack, tracker.key, current + amount)
        e.lore.render(stack)
        holder.equipment?.setItem(slot, stack)
    }
}

/**
 * 인첸트 칸. 한 아이템에 붙일 수 있는 수.
 *
 * `기본(설정 또는 inmcenchant.limit.N 권한) ↑ 오브 → + 확장기(상한 max-increase)`.
 */
class SlotService(private val e: Enchants) {

    fun max(stack: ItemStack, player: Player? = null): Int {
        val base = maxOf(playerLimit(player) ?: e.config.maxSlots, EnchantStorage.int(stack, Keys.ORB_SLOTS))
        val extra = EnchantStorage.int(stack, Keys.EXTRA_SLOTS).coerceIn(0, e.config.maxExtraSlots)
        return base + extra
    }

    fun hasRoom(stack: ItemStack, player: Player?): Boolean =
        !e.config.slotsEnabled || EnchantStorage.read(stack).size < max(stack, player)

    /**
     * `inmcenchant.limit.N` 중 가장 큰 N. **명시적으로 준 권한만 본다** — `hasPermission` 으로 물으면
     * 선언 안 된 노드를 OP 가 전부 가진 것으로 나와 OP 는 무제한이 된다(지뢰 11).
     */
    private fun playerLimit(player: Player?): Int? {
        player ?: return null
        return player.effectivePermissions
            .filter { it.value && it.permission.startsWith("inmcenchant.limit.") }
            .mapNotNull { it.permission.removePrefix("inmcenchant.limit.").toIntOrNull() }
            .maxOrNull()
    }
}

/**
 * 영혼. 영혼 추적기를 단 아이템에 쌓이고, 영혼 비용이 있는 인첸트가 쓴다.
 *
 * 빼는 순서: 발동한 아이템 → 주 손 → 방어구. 발동한 아이템에 추적기가 없어도 손의 무기에 모인
 * 영혼으로 방어구 인첸트를 돌릴 수 있게 한다(AE 와 같은 동작).
 */
class SoulService(private val e: Enchants) {

    fun souls(stack: ItemStack?): Int = EnchantStorage.int(stack, Keys.SOULS)

    fun add(stack: ItemStack, delta: Int) {
        if (!EnchantStorage.flag(stack, Keys.SOUL_TRACKER)) return
        EnchantStorage.setInt(stack, Keys.SOULS, (souls(stack) + delta).coerceAtLeast(0))
        e.lore.render(stack)
    }

    fun take(holder: LivingEntity, item: ItemStack?, amount: Int): Boolean {
        val equipment = holder.equipment
        val candidates = buildList {
            item?.let { add(null to it) }
            equipment?.let { eq ->
                for (slot in listOf(EquipmentSlot.HAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
                    add(slot to eq.getItem(slot))
                }
            }
        }
        for ((slot, stack) in candidates) {
            if (!EnchantStorage.flag(stack, Keys.SOUL_TRACKER) || souls(stack) < amount) continue
            add(stack, -amount)
            if (slot != null) equipment?.setItem(slot, stack)
            return true
        }
        return false
    }
}

/**
 * 머리 떨구기(DROP_HEAD). 플레이어는 주인 머리, 바닐라에 머리가 있는 몹은 그것,
 * 나머지는 `mob-heads.yml` 에 관리자가 적은 텍스처로.
 */
class HeadService {

    private val textures = ConcurrentHashMap<String, String>()

    fun load(map: Map<String, String>) {
        textures.clear()
        textures.putAll(map.mapKeys { it.key.uppercase() })
    }

    fun of(entity: Entity): ItemStack? {
        if (entity is Player) {
            return ItemStack(Material.PLAYER_HEAD).also { stack -> stack.editMeta(SkullMeta::class.java) { it.owningPlayer = entity } }
        }
        VANILLA[entity.type]?.let { return ItemStack(it) }
        val texture = textures[entity.type.name] ?: return null
        return ItemStack(Material.PLAYER_HEAD).also { stack ->
            stack.editMeta(SkullMeta::class.java) { meta ->
                val profile = Bukkit.createProfile(UUID.nameUUIDFromBytes(entity.type.name.toByteArray()))
                profile.setProperty(com.destroystokyo.paper.profile.ProfileProperty("textures", texture))
                meta.playerProfile = profile
            }
        }
    }

    companion object {
        private val VANILLA = mapOf(
            EntityType.ZOMBIE to Material.ZOMBIE_HEAD,
            EntityType.SKELETON to Material.SKELETON_SKULL,
            EntityType.CREEPER to Material.CREEPER_HEAD,
            EntityType.WITHER_SKELETON to Material.WITHER_SKELETON_SKULL,
            EntityType.PIGLIN to Material.PIGLIN_HEAD,
            EntityType.ENDER_DRAGON to Material.DRAGON_HEAD,
        )
    }
}

/** 인첸트별 발동 횟수. 관리 화면의 통계와 검증기가 본다. 메모리만. */
class ActivationStats {
    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun activated(id: String) {
        counts.getOrPut(id) { AtomicLong() }.incrementAndGet()
    }

    fun count(id: String): Long = counts[id]?.get() ?: 0L

    fun reset() = counts.clear()
}
