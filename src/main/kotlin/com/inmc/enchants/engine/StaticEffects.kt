package com.inmc.enchants.engine

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.item.EnchantStorage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.EquipmentSlot
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * "입고 있는 동안"(EFFECT_STATIC) · "들고 있는 동안"(HELD) · "주기마다"(REPEATING).
 *
 * **사건 하나하나를 믿지 않는다.** 방어구를 입고 벗는 길은 클릭·드래그·단축키·우클릭 착용·디스펜서·
 * 죽음·명령어로 수십 가지이고 하나라도 놓치면 효과가 영영 안 풀린다. 그래서 사건은 "다시 계산해라"
 * 신호로만 쓰고, 실제 결정은 **지금 장비 전체와 걸려 있는 것의 차이**로 한다. 1초마다 한 번 더 본다.
 */
class StaticEffects(private val e: Enchants) : Listener {

    private data class Active(val id: String, val level: Int, val trigger: Trigger, val slot: EquipmentSlot)

    /** 사람 → (칸|인첸트|발동조건) → 걸린 것. */
    private val applied = ConcurrentHashMap<UUID, MutableMap<String, Active>>()

    /** 사람 → (칸|인첸트) → 다음 반복 시각. */
    private val repeating = ConcurrentHashMap<UUID, MutableMap<String, Long>>()

    private val armorSlots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
    private val handSlots = listOf(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)

    /** 이 사람의 지속 효과를 지금 장비에 맞춘다. */
    fun refresh(player: Player) {
        if (!player.isOnline || player.isDead) return
        val desired = HashMap<String, Active>()
        if (player.world.name !in e.config.disabledWorlds) {
            collect(player, armorSlots, Trigger.EFFECT_STATIC, desired)
            collect(player, handSlots, Trigger.HELD, desired)
            for ((def, slot) in e.setService.active(player)) {
                val trigger = def.triggers.first()
                if (trigger == Trigger.EFFECT_STATIC || trigger == Trigger.HELD) desired["${slot.name}|${def.id}|${trigger.name}"] = Active(def.id, 1, trigger, slot)
            }
        }
        e.setService.announce(player)
        val current = applied.getOrPut(player.uniqueId) { ConcurrentHashMap() }

        for ((key, active) in current.entries.toList()) {
            if (desired[key] == active) continue
            current.remove(key)
            run(player, active, removal = true)
        }
        for ((key, active) in desired) {
            if (current.containsKey(key)) continue
            if (run(player, active, removal = false)) current[key] = active
        }
    }

    private fun collect(player: Player, slots: List<EquipmentSlot>, trigger: Trigger, out: MutableMap<String, Active>) {
        for (slot in slots) {
            val item = player.itemIn(slot) ?: continue
            if (item.type.isAir) continue
            // 방어구는 제 부위에서만, 그 밖은 손에서만 센다(엔진과 같은 규칙).
            val piece = Applicability.armorPiece(item.type.name)
            if (trigger == Trigger.EFFECT_STATIC && piece == null && item.type.name != "ELYTRA" && slot != EquipmentSlot.HEAD) continue
            if (trigger == Trigger.HELD && piece != null) continue
            for ((id, level) in EnchantStorage.read(item)) {
                val def = e.registry.get(id) ?: continue
                if (trigger !in def.triggers) continue
                out["${slot.name}|$id|${trigger.name}"] = Active(id, level, trigger, slot)
            }
        }
    }

    private fun run(player: Player, active: Active, removal: Boolean): Boolean {
        val def = e.registry.get(active.id) ?: e.setService.definition(active.id) ?: return removal
        val lvl = def.level(active.level) ?: return removal
        val ctx = TriggerContext(active.trigger, player, player, null, null, removal = removal)
        ctx.item = player.itemIn(active.slot)
        ctx.slot = active.slot
        ctx.enchantId = active.id
        ctx.level = active.level
        return e.engine.activate(ctx, def, lvl)
    }

    /** 다 되돌린다(퇴장·죽음·종료). */
    fun clear(player: Player) {
        val current = applied.remove(player.uniqueId) ?: return
        for (active in current.values) run(player, active, removal = true)
        repeating.remove(player.uniqueId)
        e.setService.forget(player.uniqueId)
    }

    fun clearAll() {
        for (player in Bukkit.getOnlinePlayers()) clear(player)
    }

    /** 1초 틱. 지속 효과를 다시 맞추고 반복 인첸트를 돌린다. */
    fun tick(now: Long) {
        for (player in Bukkit.getOnlinePlayers()) {
            try {
                refresh(player)
                repeat(player, now)
            } catch (t: Throwable) {
                e.logger.warning("지속 효과 처리 실패 (" + player.name + "): " + t.message)
            }
        }
    }

    private fun repeat(player: Player, now: Long) {
        val schedule = repeating.getOrPut(player.uniqueId) { ConcurrentHashMap() }
        val candidates = ArrayList<Triple<EquipmentSlot, EnchantDefinition, Int>>()
        for (slot in listOf(EquipmentSlot.HAND) + armorSlots) {
            val item = player.itemIn(slot) ?: continue
            if (item.type.isAir) continue
            for ((id, level) in EnchantStorage.read(item)) e.registry.get(id)?.let { candidates += Triple(slot, it, level) }
        }
        for ((def, slot) in e.setService.active(player)) candidates += Triple(slot, def, 1)
        val seen = HashSet<String>()
        for ((slot, def, level) in candidates) {
            if (Trigger.REPEATING !in def.triggers) continue
            val lvl = def.level(level) ?: continue
            val key = "${slot.name}|${def.id}"
            seen += key
            val period = lvl.time.coerceAtLeast(1) * 1000L
            val next = schedule.getOrPut(key) { if (lvl.instantApply) now else now + period }
            if (now < next) continue
            schedule[key] = now + period
            val ctx = TriggerContext(Trigger.REPEATING, player, player, null, null)
            ctx.item = player.itemIn(slot)
            ctx.slot = slot
            ctx.enchantId = def.id
            ctx.level = level
            e.engine.activate(ctx, def, lvl)
        }
        schedule.keys.retainAll(seen)
    }

    // --- "다시 계산해라" 신호 -------------------------------------------------------------

    private fun soon(player: Player) {
        Bukkit.getScheduler().runTask(e.plugin, Runnable { refresh(player) })
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHeld(event: PlayerItemHeldEvent) = soon(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSwap(event: PlayerSwapHandItemsEvent) = soon(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onClick(event: InventoryClickEvent) {
        (event.whoClicked as? Player)?.let(::soon)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrag(event: InventoryDragEvent) {
        (event.whoClicked as? Player)?.let(::soon)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        (event.player as? Player)?.let(::soon)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArmor(event: com.destroystokyo.paper.event.player.PlayerArmorChangeEvent) = soon(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) = soon(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        // 접속 직후 장비가 아직 안 올라왔을 수 있다. 한 틱 뒤.
        soon(event.player)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = soon(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorld(event: PlayerChangedWorldEvent) = soon(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDeath(event: PlayerDeathEvent) = clear(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) = clear(event.player)
}
