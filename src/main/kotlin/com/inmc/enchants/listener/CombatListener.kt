package com.inmc.enchants.listener

import com.inmc.enchants.Enchants
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.engine.TriggerContext
import com.inmc.enchants.engine.Variables
import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.item.Keys
import com.inmc.enchants.item.Trackers
import org.bukkit.NamespacedKey
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/**
 * 전투 발동 조건. 공격·방어·사격·처치·사망·환경 피해.
 *
 * **한 번의 타격이 양쪽 인첸트를 다 돌린다.** 때린 쪽의 ATTACK 과 맞은 쪽의 DEFENSE 가 같은
 * 사건의 피해량을 차례로 바꾼다(증가 × 감소). 우선순위 HIGH — 다른 플러그인의 NORMAL 보정이
 * 끝난 뒤에 우리가 곱한다.
 */
class CombatListener(private val e: Enchants) : Listener {

    private val monsterMob = NamespacedKey("inmc-monster", "mob_id")

    /** 투사체면 쏜 쪽, 아니면 그대로. 살아 있는 것이 아니면 null. */
    private fun realAttacker(damager: Entity): LivingEntity? =
        (damager as? Projectile)?.shooter as? LivingEntity ?: damager as? LivingEntity

    private fun baseValues(event: EntityDamageEvent): MutableMap<String, String> = hashMapOf(
        "damage" to Variables.num(event.finalDamage),
        "raw damage" to Variables.num(event.damage),
        "damage cause" to event.cause.name,
    )

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHit(event: EntityDamageByEntityEvent) {
        if (e.support.effectDamage || !e.ready) return
        val victim = event.entity as? LivingEntity ?: return
        val attacker = realAttacker(event.damager) ?: return
        if (attacker == victim) return
        val projectile = event.damager as? Projectile
        val now = System.currentTimeMillis()

        e.support.ownerAttacked(victim, attacker)

        val values = baseValues(event)
        values["is critical"] = event.isCritical.toString()
        values["damaged from behind"] = fromBehind(attacker, victim).toString()
        if (projectile != null) {
            values["projectile type"] = if (projectile.type.name == "TRIDENT") "trident" else "arrow"
            values["is headshot"] = (projectile.location.y >= victim.eyeLocation.y - 0.35).toString()
        }

        // --- 때린 쪽 ---
        if (projectile != null) {
            val shot = e.projectiles.snapshot(projectile)
            if (shot != null) {
                val trigger = if (victim is Player) Trigger.SHOOT else Trigger.SHOOT_MOB
                val ctx = TriggerContext(trigger, attacker, attacker, victim, event, values = HashMap(values))
                e.engine.fireWith(ctx, shot.item, shot.enchants)
            }
        } else {
            if (attacker is Player || e.config.comboForMobs) {
                e.state.hit(attacker.uniqueId, victim.uniqueId, now, e.config.comboWindowMillis)
            }
            val trigger = if (victim is Player) Trigger.ATTACK else Trigger.ATTACK_MOB
            e.engine.fire(TriggerContext(trigger, attacker, attacker, victim, event, values = HashMap(values)))
            if (victim.persistentDataContainer.has(monsterMob)) {
                val extra = HashMap(values).also { it["mob id"] = victim.persistentDataContainer.get(monsterMob, org.bukkit.persistence.PersistentDataType.STRING).orEmpty() }
                e.engine.fire(TriggerContext(Trigger.CUSTOM_MOB_DEFENSE, attacker, attacker, victim, event, values = extra))
            }
        }
        if (event.isCancelled) return

        // --- 맞은 쪽 ---
        val defense = when {
            projectile != null && attacker is Player -> Trigger.DEFENSE_PROJECTILE
            projectile != null -> Trigger.DEFENSE_MOB_PROJECTILE
            attacker is Player -> Trigger.DEFENSE
            else -> Trigger.DEFENSE_MOB
        }
        values["damage"] = Variables.num(event.finalDamage)
        e.engine.fire(TriggerContext(defense, victim, attacker, victim, event, values = HashMap(values)))
    }

    /** 맞은 쪽이 바라보는 방향과 때린 쪽 방향이 반대면(등을 보이고 있으면) 뒤에서 맞은 것. */
    private fun fromBehind(attacker: Entity, victim: LivingEntity): Boolean {
        val facing = victim.location.direction.setY(0)
        val toAttacker = attacker.location.toVector().subtract(victim.location.toVector()).setY(0)
        if (facing.lengthSquared() < 1e-6 || toAttacker.lengthSquared() < 1e-6) return false
        return facing.angle(toAttacker) > Math.toRadians(110.0)
    }

    /** 방패로 막았는가. 막히면 최종 피해가 0 이 된다 — 그 뒤에 본다. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onShield(event: EntityDamageByEntityEvent) {
        if (e.support.effectDamage || !e.ready) return
        val victim = event.entity as? Player ?: return
        if (!victim.isBlocking || event.finalDamage > 0.0) return
        val attacker = realAttacker(event.damager)
        e.engine.fire(TriggerContext(Trigger.SHIELD_BLOCK, victim, attacker, victim, event, values = baseValues(event)))
    }

    /** 환경 피해(낙하·불·폭발·번개)와 겉날개 비행 중 피해. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        if (e.support.effectDamage || !e.ready) return
        val victim = event.entity as? LivingEntity ?: return
        val trigger = when (event.cause) {
            EntityDamageEvent.DamageCause.FALL -> Trigger.FALL_DAMAGE
            EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK,
            EntityDamageEvent.DamageCause.LAVA, EntityDamageEvent.DamageCause.HOT_FLOOR,
            EntityDamageEvent.DamageCause.CAMPFIRE -> Trigger.FIRE
            EntityDamageEvent.DamageCause.BLOCK_EXPLOSION, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION -> Trigger.EXPLOSION
            EntityDamageEvent.DamageCause.LIGHTNING -> Trigger.PASSIVE_DEATH
            else -> null
        }
        val attacker = (event as? EntityDamageByEntityEvent)?.let { realAttacker(it.damager) }
        if (trigger != null) e.engine.fire(TriggerContext(trigger, victim, attacker, victim, event, values = baseValues(event)))
        if (victim.isGliding && !event.isCancelled) {
            e.engine.fire(TriggerContext(Trigger.ELYTRA_FLY_DAMAGE, victim, attacker, victim, event, values = baseValues(event)))
        }
    }

    /**
     * 죽기 직전. 토템이 없으면 이 사건은 **이미 취소된 채로** 온다 — 그래서 ignoreCancelled 를 끈다.
     * REVIVE 가 취소를 풀면 토템처럼 살아난다.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onResurrect(event: EntityResurrectEvent) {
        if (!e.ready) return
        val dying = event.entity
        val cause = dying.lastDamageCause as? EntityDamageByEntityEvent
        val attacker = cause?.let { realAttacker(it.damager) }
        e.engine.fire(TriggerContext(Trigger.DEATH, dying, attacker, dying, event))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDeath(event: EntityDeathEvent) {
        if (!e.ready) return
        val dead = event.entity
        val cause = dead.lastDamageCause as? EntityDamageByEntityEvent
        val killer = cause?.let { realAttacker(it.damager) } ?: dead.killer ?: return
        if (killer == dead) return
        val values: MutableMap<String, String> = hashMapOf("exp" to event.droppedExp.toString())
        val ctx = TriggerContext(Trigger.KILL, killer, killer, dead, event, values = values)
        e.engine.fire(ctx)
        val specific = TriggerContext(if (dead is Player) Trigger.KILL_PLAYER else Trigger.KILL_MOB, killer, killer, dead, event, values = HashMap(values))
        e.engine.fire(specific)

        // 드랍을 바꾸는 효과는 두 발동의 것을 합쳐 한 번에 적용한다.
        ctx.dropMultiplier *= specific.dropMultiplier
        ctx.smeltDrops = ctx.smeltDrops || specific.smeltDrops
        ctx.teleportDrops = ctx.teleportDrops || specific.teleportDrops
        if (dead !is Player) applyDrops(event, killer, ctx)

        // 추적기와 영혼. 주 손 무기 기준.
        Trackers.bump(e, killer, EquipmentSlot.HAND, if (dead is Player) Trackers.STAT else Trackers.MOB)
        val soulsFrom = if (dead is Player) e.config.soulsFromPlayers else e.config.soulsFromMobs
        val weapon = killer.equipment?.itemInMainHand
        if (soulsFrom && EnchantStorage.flag(weapon, Keys.SOUL_TRACKER) && weapon != null) {
            e.souls.add(weapon, e.config.soulsPerKill)
            killer.equipment?.setItemInMainHand(weapon)
        }
    }

    private fun applyDrops(event: EntityDeathEvent, killer: LivingEntity, ctx: TriggerContext) {
        if (ctx.dropMultiplier == 1.0 && !ctx.smeltDrops && !ctx.teleportDrops) return
        val drops: MutableList<ItemStack> = event.drops.toMutableList()
        e.drops.transform(drops, ctx)
        event.drops.clear()
        val player = killer as? Player
        if (ctx.teleportDrops && player != null) {
            for (stack in drops.flatMap(e.drops::split)) {
                for (left in player.inventory.addItem(stack).values) player.world.dropItemNaturally(player.location, left)
            }
        } else {
            event.drops.addAll(drops.flatMap(e.drops::split))
        }
    }
}
