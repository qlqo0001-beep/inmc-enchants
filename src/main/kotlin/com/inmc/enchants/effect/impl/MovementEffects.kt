package com.inmc.enchants.effect.impl

import com.inmc.enchants.effect.EffectExec
import com.inmc.enchants.effect.EffectRun
import com.inmc.enchants.engine.EngineState
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/** 튕기기·당기기·순간이동·비행·속도·물 위 걷기. */
internal object MovementEffects {

    /** 한 번에 줄 수 있는 속도의 상한. 설정 실수로 사람을 우주로 보내지 않게. */
    private const val MAX_SPEED = 4.0

    private fun push(entity: Entity, velocity: Vector) {
        val capped = if (velocity.length() > MAX_SPEED) velocity.normalize().multiply(MAX_SPEED) else velocity
        entity.velocity = entity.velocity.add(capped)
    }

    /** 대상과 기준 사이 방향. 대상이 발동한 쪽이면 상대가 기준이다. */
    private fun away(run: EffectRun, target: Entity): Vector? {
        val from = run.counterpart(target) ?: return null
        val direction = target.location.toVector().subtract(from.location.toVector())
        if (direction.lengthSquared() < 1e-6) return null
        return direction.setY(0).normalize()
    }

    private fun Player.canToggleFlight(): Boolean = gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE

    fun register(map: MutableMap<String, EffectExec>) {
        map["BOOST"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            val amount = run.num(1)
            val facing = target.location.direction
            val velocity = when (run.arg(0).uppercase()) {
                "FORWARD" -> facing.clone().multiply(amount)
                "BACKWARD" -> facing.clone().multiply(-amount)
                "DOWN" -> Vector(0.0, -amount, 0.0)
                else -> Vector(0.0, amount, 0.0)
            }
            push(target, velocity)
        }
        map["PULL_AWAY"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            val direction = away(run, target) ?: return@EffectExec
            push(target, direction.multiply(run.num(0) * 0.4).setY(0.35))
        }
        map["PULL_CLOSER"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            val direction = away(run, target) ?: return@EffectExec
            push(target, direction.multiply(-run.num(0) * 0.4).setY(0.25))
        }
        map["TELEPORT"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            val destination = run.locationOverride ?: run.ctx.hitLocation ?: return@EffectExec
            target.teleport(Location(destination.world, destination.x, destination.y, destination.z, target.location.yaw, target.location.pitch))
        }
        map["TELEPORT_BEHIND"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            val other = run.counterpart(target) ?: return@EffectExec
            val facing = other.location.direction.setY(0)
            if (facing.lengthSquared() < 1e-6) return@EffectExec
            val behind = other.location.clone().subtract(facing.normalize().multiply(1.5))
            if (!behind.block.isPassable || !behind.clone().add(0.0, 1.0, 0.0).block.isPassable) return@EffectExec
            behind.direction = other.location.toVector().subtract(behind.toVector())
            target.teleport(behind)
        }
        map["FLY"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            if (!player.canToggleFlight()) return@EffectExec
            if (run.removal) {
                player.isFlying = false
                player.allowFlight = false
                return@EffectExec
            }
            val ticks = run.int(0)
            if (!run.static && ticks <= 0) {
                player.allowFlight = !player.allowFlight
                if (!player.allowFlight) player.isFlying = false
                return@EffectExec
            }
            player.allowFlight = true
            if (ticks > 0) run.enchants.support.later(ticks.toLong()) {
                if (player.isOnline && player.canToggleFlight()) {
                    player.isFlying = false
                    player.allowFlight = false
                }
            }
        }
        map["FLY_SPEED"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            player.flySpeed = if (run.removal) 0.1f else run.num(0).toFloat().coerceIn(-1f, 1f)
        }
        map["ADD_FLY_SPEED"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            player.flySpeed = shifted(run, player, "fly", player.flySpeed)
        }
        map["WALK_SPEED"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            player.walkSpeed = if (run.removal) 0.2f else run.num(0).toFloat().coerceIn(-1f, 1f)
        }
        map["ADD_WALK_SPEED"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            player.walkSpeed = shifted(run, player, "walk", player.walkSpeed)
        }
        map["WATER_WALKER"] = walker(EngineState.Walker.WATER)
        map["LAVA_WALKER"] = walker(EngineState.Walker.LAVA)
        map["WEB_WALKER"] = walker(EngineState.Walker.WEB)
    }

    /**
     * 속도 더하기. 지속형은 **실제로 더한 만큼**을 기억했다가 벗을 때 그만큼만 뺀다 — 적힌 값을 그대로
     * 빼면 ±1 에서 잘린 만큼 더 빠지고 소수 오차가 쌓여, 입었다 벗기를 반복할수록 속도가 틀어진다.
     */
    private fun shifted(run: EffectRun, player: Player, kind: String, current: Float): Float {
        val key = "${player.uniqueId}|$kind|${run.ctx.slot}|${run.ctx.enchantId}"
        val deltas = run.enchants.state.speedDeltas
        if (run.removal) return deltas.remove(key)?.let { (current - it).coerceIn(-1f, 1f) } ?: current
        val next = (current + run.num(0).toFloat()).coerceIn(-1f, 1f)
        if (run.static) deltas[key] = next - current
        return next
    }

    /** 지속형이면 벗을 때 끈다. 한 번짜리 발동이면 5초 동안. */
    private fun walker(kind: EngineState.Walker) = EffectExec { run ->
        val entity = run.entity ?: return@EffectExec
        val state = run.enchants.state
        if (run.removal) {
            state.setWalker(entity.uniqueId, kind, false)
            return@EffectExec
        }
        state.setWalker(entity.uniqueId, kind, true)
        if (!run.static) run.enchants.support.later(100L) { state.setWalker(entity.uniqueId, kind, false) }
    }
}
