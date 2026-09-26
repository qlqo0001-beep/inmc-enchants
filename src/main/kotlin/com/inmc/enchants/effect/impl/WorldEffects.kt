package com.inmc.enchants.effect.impl

import com.inmc.enchants.effect.EffectExec
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.EntityType
import org.bukkit.entity.Fireball
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Projectile
import org.bukkit.util.Vector

/** 소환·투사체·입자·폭죽·소리. */
internal object WorldEffects {

    /** 이름 또는 `r,g,b`. */
    private fun color(raw: String): Color {
        val parts = raw.split(',').mapNotNull { it.trim().toIntOrNull() }
        if (parts.size == 3) return Color.fromRGB(parts[0].coerceIn(0, 255), parts[1].coerceIn(0, 255), parts[2].coerceIn(0, 255))
        return runCatching { Color::class.java.getField(raw.trim().uppercase()).get(null) as Color }.getOrDefault(Color.RED)
    }

    /** 위험하거나 보스인 것은 효과로 소환하지 않는다. 설정 실수 하나가 서버를 망친다. */
    private val FORBIDDEN = setOf(EntityType.ENDER_DRAGON, EntityType.WITHER, EntityType.PLAYER, EntityType.UNKNOWN)

    fun register(map: MutableMap<String, EffectExec>) {
        map["GUARD"] = EffectExec { run ->
            val owner = run.living ?: return@EffectExec
            val enemy = run.counterpart(owner)
            run.enchants.support.spawnGuards(owner, enemy, run.arg(0), run.int(1), run.int(2), run.arg(3), run.bool(4))
        }
        map["STEAL_GUARD"] = EffectExec { run ->
            val from = run.entity ?: return@EffectExec
            val to = run.counterpart(from) as? LivingEntity ?: return@EffectExec
            run.enchants.support.stealGuards(from, to, from)
        }
        map["SPAWN_ENTITY"] = EffectExec { run ->
            val type = runCatching { EntityType.valueOf(run.arg(0).uppercase()) }.getOrNull() ?: return@EffectExec
            if (!type.isSpawnable || type in FORBIDDEN) return@EffectExec
            run.location.world.spawnEntity(run.location, type)
        }
        map["PROJECTILE"] = EffectExec { run ->
            val shooter = run.living ?: return@EffectExec
            val type = runCatching { EntityType.valueOf(run.arg(0).uppercase()) }.getOrNull() ?: return@EffectExec
            val clazz = type.entityClass ?: return@EffectExec
            if (!Projectile::class.java.isAssignableFrom(clazz)) return@EffectExec
            @Suppress("UNCHECKED_CAST")
            shooter.launchProjectile(clazz as Class<out Projectile>)
        }
        map["FIREBALL"] = EffectExec { run -> run.living?.launchProjectile(Fireball::class.java) }
        map["SPAWN_ARROWS"] = EffectExec { run ->
            val center = run.location
            val shooter = run.ctx.self
            repeat(run.int(0).coerceIn(1, 40)) {
                val at = center.clone().add((Math.random() - 0.5) * 3, 8.0, (Math.random() - 0.5) * 3)
                val arrow = center.world.spawn(at, Arrow::class.java) { a ->
                    a.shooter = shooter
                    a.pickupStatus = AbstractArrow.PickupStatus.DISALLOWED
                }
                arrow.velocity = Vector(0.0, -1.5, 0.0)
                run.enchants.support.later(100L) { if (arrow.isValid) arrow.remove() }
            }
        }
        map["SPAWN_BLOCKS"] = EffectExec { run ->
            val material = Material.matchMaterial(run.arg(0)) ?: return@EffectExec
            run.enchants.support.fallingBlocks(run.location, material, run.num(1), run.ctx.self)
        }
        map["PARTICLE"] = EffectExec { run ->
            val particle = run.enchants.support.particle(run.arg(0)) ?: return@EffectExec
            val offset = run.num(2)
            val location = run.location.clone().add(0.0, 1.0, 0.0)
            location.world.spawnParticle(particle, location, run.int(1).coerceIn(1, 500), offset, offset, offset, 0.0, run.enchants.support.particleData(particle))
        }
        map["PARTICLE_LINE"] = EffectExec { run ->
            val particle = run.enchants.support.particle(run.arg(0)) ?: return@EffectExec
            val from = run.ctx.self.eyeLocation
            val to = run.location.clone().add(0.0, 1.0, 0.0)
            if (from.world != to.world) return@EffectExec
            val points = run.int(2).coerceIn(2, 200)
            val step = to.toVector().subtract(from.toVector()).multiply(1.0 / points)
            val data = run.enchants.support.particleData(particle)
            val cursor = from.clone()
            repeat(points) {
                cursor.add(step)
                cursor.world.spawnParticle(particle, cursor, run.int(1).coerceIn(1, 20), 0.0, 0.0, 0.0, 0.0, data)
            }
        }
        map["BLOOD"] = EffectExec { run ->
            val location = run.location.clone().add(0.0, 1.0, 0.0)
            location.world.spawnParticle(Particle.BLOCK, location, 30, 0.25, 0.4, 0.25, Material.REDSTONE_BLOCK.createBlockData())
        }
        map["CACTUS"] = EffectExec { run ->
            val location = run.location.clone().add(0.0, 1.0, 0.0)
            location.world.spawnParticle(Particle.BLOCK, location, 20, 0.3, 0.4, 0.3, Material.CACTUS.createBlockData())
            location.world.playSound(location, Sound.ENCHANT_THORNS_HIT, 1f, 1f)
        }
        map["FIREWORK"] = EffectExec { run ->
            val type = runCatching { FireworkEffect.Type.valueOf(run.arg(2).uppercase()) }.getOrDefault(FireworkEffect.Type.BALL)
            run.enchants.support.firework(run.location, color(run.arg(0)), color(run.arg(1)), type, run.int(3), run.bool(4), run.bool(5))
        }
        map["PLAY_SOUND"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            val sound = run.enchants.support.sound(run.arg(0)) ?: return@EffectExec
            player.playSound(player.location, sound, run.num(2).toFloat(), run.num(1).toFloat())
        }
        map["PLAY_SOUND_OUTLOUD"] = EffectExec { run ->
            val sound = run.enchants.support.sound(run.arg(0)) ?: return@EffectExec
            run.location.world.playSound(run.location, sound, run.num(2).toFloat(), run.num(1).toFloat())
        }
    }
}
