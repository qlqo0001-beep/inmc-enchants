package com.inmc.enchants.listener

import com.inmc.enchants.Enchants
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.engine.TriggerContext
import com.inmc.enchants.engine.Variables
import com.inmc.enchants.item.EnchantStorage
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Projectile
import org.bukkit.entity.Trident
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 투사체가 **쏜 순간의** 활 인첸트를 들고 간다.
 *
 * 맞히는 것은 쏜 뒤다. 그 사이 손의 아이템을 바꿔도 화살은 쏜 활의 인첸트로 맞혀야 한다 —
 * 맞는 순간 손을 보면 검으로 바꿔 든 사람의 화살이 검 인첸트를 낸다.
 */
class ProjectileStore {

    data class Shot(val item: ItemStack?, val enchants: Map<String, Int>, val at: Long)

    private val shots = ConcurrentHashMap<UUID, Shot>()

    fun remember(projectile: Projectile, item: ItemStack?, enchants: Map<String, Int>) {
        if (enchants.isEmpty()) return
        shots[projectile.uniqueId] = Shot(item?.clone(), enchants, System.currentTimeMillis())
    }

    fun snapshot(projectile: Projectile): Shot? = shots[projectile.uniqueId]

    /** 1분 넘은 것은 치운다. 박힌 화살은 계속 남아 있으므로 유효성만으로는 못 치운다. */
    fun sweep(now: Long) {
        shots.entries.removeIf { now - it.value.at > 60_000L }
    }
}

class ProjectileListener(private val e: Enchants) : Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShoot(event: EntityShootBowEvent) {
        if (!e.ready) return
        val shooter = event.entity
        val bow = event.bow ?: return
        val enchants = EnchantStorage.read(bow)
        val projectile = event.projectile as? Projectile ?: return
        e.projectiles.remember(projectile, bow, enchants)
        if (enchants.isEmpty()) return
        // AE 와 같이 끝까지 당겨야 BOW_FIRE. 쇠뇌는 늘 끝까지다.
        if (event.force < 0.95f && bow.type.name == "BOW") return
        val ctx = TriggerContext(Trigger.BOW_FIRE, shooter, shooter, null, event, values = hashMapOf("force" to Variables.num(event.force.toDouble())))
        e.engine.fireWith(ctx, bow, enchants)
    }

    /** 삼지창은 활 사건을 안 거친다. 던지는 순간 손의 삼지창을 기억한다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val trident = event.entity as? Trident ?: return
        val shooter = trident.shooter as? LivingEntity ?: return
        val item = trident.itemStack
        e.projectiles.remember(trident, item, EnchantStorage.read(item))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHit(event: ProjectileHitEvent) {
        if (!e.ready) return
        val projectile = event.entity
        val shooter = projectile.shooter as? LivingEntity ?: return
        val shot = e.projectiles.snapshot(projectile) ?: return
        val hit = event.hitEntity
        val location = hit?.location ?: event.hitBlock?.location?.add(0.5, 0.5, 0.5) ?: projectile.location
        val ctx = TriggerContext(
            Trigger.ARROW_HIT, shooter, shooter, hit, event, event.hitBlock, location,
            values = hashMapOf("hit location" to Variables.loc(location)),
        )
        e.engine.fireWith(ctx, shot.item, shot.enchants)
    }
}
