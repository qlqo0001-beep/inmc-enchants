package com.inmc.enchants.effect.impl

import com.inmc.enchants.effect.EffectExec
import com.inmc.enchants.effect.EffectRun
import org.bukkit.EntityEffect
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

/** 체력·허기·산소·불·빙결·무적·부활·물약. */
internal object StateEffects {

    private fun maxHealth(entity: LivingEntity): Double = entity.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0

    fun register(map: MutableMap<String, EffectExec>) {
        map["ADD_HEALTH"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            if (target.isDead) return@EffectExec
            target.health = (target.health + run.num(0)).coerceIn(0.0, maxHealth(target))
        }
        map["ADD_FOOD"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            player.foodLevel = (player.foodLevel + run.int(0)).coerceIn(0, 20)
            if (run.int(0) > 0) player.saturation = (player.saturation + run.int(0) * 0.5f).coerceAtMost(player.foodLevel.toFloat())
        }
        map["AIR"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            target.remainingAir = (target.remainingAir + run.int(0)).coerceIn(0, target.maximumAir)
        }
        map["SET_AIR"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            target.remainingAir = run.int(0).coerceIn(0, target.maximumAir)
        }
        map["BURN"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            target.fireTicks = maxOf(target.fireTicks, run.int(0))
        }
        map["EXTINGUISH"] = EffectExec { run -> run.entity?.fireTicks = 0 }
        map["FREEZE"] = EffectExec { run -> run.living?.let { run.enchants.support.freeze(it, run.int(0)) } }
        map["SCREEN_FREEZE"] = EffectExec { run -> run.living?.let { run.enchants.support.screenFreeze(it, run.int(0)) } }
        map["SNOWBLIND"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            val ticks = run.int(0).coerceAtLeast(1)
            target.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, ticks, 0, false, false))
            run.enchants.support.screenFreeze(target, ticks)
        }
        map["INVINCIBLE"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            val ticks = run.int(0)
            when {
                run.removal -> target.isInvulnerable = false
                ticks > 0 -> {
                    target.isInvulnerable = true
                    run.enchants.support.later(ticks.toLong()) { if (target.isValid) target.isInvulnerable = false }
                }
                run.static -> target.isInvulnerable = true
                else -> target.isInvulnerable = !target.isInvulnerable
            }
        }
        map["REVIVE"] = EffectExec { run ->
            // 죽기 직전(EntityResurrectEvent)에만 뜻이 있다. 취소를 풀면 토템처럼 살아난다.
            val event = run.ctx.event as? EntityResurrectEvent ?: return@EffectExec
            event.isCancelled = false
        }
        map["CURE"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            val type = run.enchants.support.potion(run.arg(0)) ?: return@EffectExec
            val current = target.getPotionEffect(type) ?: return@EffectExec
            if (!current.isInfinite) target.removePotionEffect(type)
        }
        map["CURE_PERMANENT"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            val type = run.enchants.support.potion(run.arg(0)) ?: return@EffectExec
            val current = target.getPotionEffect(type) ?: return@EffectExec
            if (current.isInfinite) target.removePotionEffect(type)
        }
        map["POTION"] = EffectExec { run -> potion(run, override = false) }
        map["POTION_OVERRIDE"] = EffectExec { run -> potion(run, override = true) }
        map["TOTEM"] = EffectExec { run ->
            @Suppress("DEPRECATION")
            run.entity?.playEffect(EntityEffect.TOTEM_RESURRECT)
        }
        map["PLAY_ENTITY"] = EffectExec { run ->
            val effect = runCatching { EntityEffect.valueOf(run.arg(0).uppercase()) }.getOrNull() ?: return@EffectExec
            @Suppress("DEPRECATION")
            runCatching { run.entity?.playEffect(effect) }
        }
    }

    /**
     * 물약 효과. 지속형(착용·들기)에서 시간을 비우면 **무한**으로 걸고, 벗을 때 무한인 것만 푼다
     * — 마신 물약까지 지우면 안 된다.
     */
    private fun potion(run: EffectRun, override: Boolean) {
        val target = run.living ?: return
        val type = run.enchants.support.potion(run.arg(0)) ?: return
        val explicitTicks = run.args.getOrNull(2)?.isNotBlank() == true
        if (run.removal) {
            val current = target.getPotionEffect(type) ?: return
            if (current.isInfinite) target.removePotionEffect(type)
            return
        }
        val amplifier = run.int(1).coerceIn(0, 255)
        val ticks = if (run.static && !explicitTicks) PotionEffect.INFINITE_DURATION else run.int(2).coerceAtLeast(1)
        val current = target.getPotionEffect(type)
        if (!override && current != null) {
            // 더 약하거나 더 짧은 것으로 덮지 않는다(바닐라와 같다). 무한은 덮지 않는다.
            if (current.isInfinite || current.amplifier > amplifier) return
            if (current.amplifier == amplifier && ticks != PotionEffect.INFINITE_DURATION && current.duration >= ticks) return
        }
        if (override && current != null) target.removePotionEffect(type)
        target.addPotionEffect(PotionEffect(type, ticks, amplifier, false, !run.static, true))
    }
}
