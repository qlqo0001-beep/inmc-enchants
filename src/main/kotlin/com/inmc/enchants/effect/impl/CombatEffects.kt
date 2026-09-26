package com.inmc.enchants.effect.impl

import com.inmc.enchants.effect.EffectExec
import com.inmc.enchants.effect.EffectRun
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Cancellable
import org.bukkit.event.entity.EntityDamageEvent

/** 피해·체력을 직접 다루는 효과. */
internal object CombatEffects {

    private val EffectRun.damageEvent: EntityDamageEvent? get() = ctx.event as? EntityDamageEvent

    private fun EffectRun.scaleDamage(factor: Double) {
        val event = damageEvent ?: return
        event.damage = (event.damage * factor).coerceAtLeast(0.0)
    }

    fun register(map: MutableMap<String, EffectExec>) {
        map["INCREASE_DAMAGE"] = EffectExec { it.scaleDamage(1.0 + it.num(0) / 100.0) }
        map["DECREASE_DAMAGE"] = EffectExec { it.scaleDamage((1.0 - it.num(0) / 100.0).coerceAtLeast(0.0)) }
        map["DOUBLE_DAMAGE"] = EffectExec { it.scaleDamage(2.0) }
        map["HALF_DAMAGE"] = EffectExec { it.scaleDamage(0.5) }
        map["NEGATE_DAMAGE"] = EffectExec { run ->
            val event = run.damageEvent ?: return@EffectExec
            event.damage = (event.damage - run.num(0)).coerceAtLeast(0.0)
        }
        map["IGNORE_ARMOR_PROTECTION"] = EffectExec { run ->
            val event = run.damageEvent ?: return@EffectExec
            // 오래된 API 지만 대체재가 없다. 없어지면 조용히 아무 일도 안 한다.
            @Suppress("DEPRECATION")
            runCatching {
                if (event.isApplicable(EntityDamageEvent.DamageModifier.ARMOR)) {
                    event.setDamage(EntityDamageEvent.DamageModifier.ARMOR, 0.0)
                }
            }
        }
        map["IGNORE_ARMOR_DAMAGE"] = EffectExec { run ->
            val victim = run.ctx.victim ?: return@EffectExec
            // 이 틱에 이 사람 방어구는 닳지 않는다. 피해 계산이 같은 틱에 끝나므로 50ms 면 넉넉하다.
            run.enchants.state.protectArmor(victim.uniqueId, System.currentTimeMillis() + 50)
        }
        map["CANCEL_EVENT"] = EffectExec { run ->
            (run.ctx.event as? Cancellable)?.isCancelled = true
            run.ctx.cancelled = true
        }
        map["DISABLE_KNOCKBACK"] = EffectExec { run ->
            val entity = run.entity ?: return@EffectExec
            run.enchants.state.blockKnockback(entity.uniqueId, System.currentTimeMillis() + run.int(0) * 50L)
        }
        map["STOP_KNOCKBACK"] = EffectExec { run ->
            val entity = run.entity ?: return@EffectExec
            run.enchants.state.blockKnockback(entity.uniqueId, System.currentTimeMillis() + 150L)
        }
        map["DO_HARM"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            val source = if (target == run.ctx.self) run.ctx.opponent else run.ctx.self
            run.enchants.support.damage(target, run.num(0), source)
        }
        map["REMOVE_HEALTH"] = EffectExec { run -> run.living?.let { run.enchants.support.removeHealth(it, run.num(0), false) } }
        map["REMOVE_HEALTH_DAMAGE"] = EffectExec { run -> run.living?.let { run.enchants.support.removeHealth(it, run.num(0), true) } }
        map["REMOVE_HEALTH_TOTEM"] = EffectExec { run -> run.living?.let { run.enchants.support.removeHealthTotem(it, run.num(0), false) } }
        map["REMOVE_HEALTH_DAMAGE_TOTEM"] = EffectExec { run -> run.living?.let { run.enchants.support.removeHealthTotem(it, run.num(0), true) } }
        map["KILL"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            if (target.isDead) return@EffectExec
            target.health = 0.0
        }
        map["BLEED"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            run.enchants.support.bleed(target, run.num(0), run.int(1), run.int(2))
        }
        map["STEAL_HEALTH"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            val thief = run.counterpart(target) as? LivingEntity ?: return@EffectExec
            val amount = run.num(0).coerceAtMost(target.health)
            if (amount <= 0) return@EffectExec
            run.enchants.support.removeHealth(target, amount, animate = true)
            val max = thief.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0
            if (!thief.isDead) thief.health = (thief.health + amount).coerceAtMost(max)
        }
        map["TNT"] = EffectExec { run ->
            val location = run.location
            val radius = run.num(1)
            location.world.spawnParticle(Particle.EXPLOSION_EMITTER, location, 1)
            location.world.playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f)
            val source = run.ctx.self
            for (near in location.world.getNearbyEntities(location, radius, radius, radius)) {
                val living = near as? LivingEntity ?: continue
                if (living == source || run.enchants.support.isAlly(source, living)) continue
                run.enchants.support.damage(living, run.num(0), source)
            }
        }
        map["EXPLODE"] = EffectExec { run ->
            val location = run.location
            location.world.createExplosion(location, run.num(0).toFloat().coerceIn(0f, 10f), run.bool(1), run.bool(2), run.ctx.self)
        }
        map["LIGHTNING"] = EffectExec { run ->
            val location = run.location
            if (run.bool(0)) location.world.strikeLightning(location) else location.world.strikeLightningEffect(location)
        }
    }
}
