package com.inmc.enchants.engine

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import kotlin.math.abs

/** 효과가 닿는 것 하나. */
sealed interface Target {
    val location: Location

    data class OfEntity(val entity: Entity) : Target {
        override val location: Location get() = entity.location
    }

    data class OfBlock(val block: Block) : Target {
        override val location: Location get() = block.location.add(0.5, 0.5, 0.5)
    }

    data class At(override val location: Location) : Target
}

/**
 * [TargetSpec] → 실제 대상들.
 *
 * 옵션 값에도 변수가 들어갈 수 있어(`@Aoe{radius=%level%}`) [resolveOption] 으로 먼저 푼다.
 * 블록 영역(트렌치·터널·광맥)은 **발동의 블록**을 중심으로 한다 — 채굴이 아니면 비어 있다.
 */
class Targets(private val resolveOption: (String, TriggerContext) -> String) {

    /** 블록 영역 하나가 돌려줄 수 있는 최대 블록. 광맥 깊이를 크게 적어도 서버가 멈추지 않게. */
    private val maxBlocks = 512

    fun resolve(spec: TargetSpec, ctx: TriggerContext, isAlly: (Entity, Entity) -> Boolean = { _, _ -> false }): List<Target> {
        val opts = spec.copy(options = spec.options.mapValues { resolveOption(it.value, ctx) })
        val self = ctx.self
        return when (spec.kind) {
            TargetKind.VICTIM -> listOfNotNull(ctx.victim?.let { Target.OfEntity(it) })
            TargetKind.ATTACKER -> listOfNotNull(ctx.attacker?.let { Target.OfEntity(it) })
            TargetKind.SELF -> listOf(Target.OfEntity(self))
            TargetKind.BLOCK -> listOfNotNull(ctx.block?.let { Target.OfBlock(it) })
            TargetKind.EYE_HEIGHT -> listOf(Target.At(self.eyeLocation))
            TargetKind.ADD -> listOf(
                Target.At(self.location.clone().add(opts.double("x", 0.0), opts.double("y", 0.0), opts.double("z", 0.0))),
            )
            TargetKind.ALL_PLAYERS -> Bukkit.getOnlinePlayers().map { Target.OfEntity(it) }
            TargetKind.PLAYER_FROM_NAME -> listOfNotNull(Bukkit.getPlayerExact(opts.string("name", ""))?.let { Target.OfEntity(it) })
            TargetKind.NEAREST_PLAYER -> {
                val radius = opts.double("radius", 0.0).let { if (it <= 0) 30.0 else it }
                self.getNearbyEntities(radius, radius, radius).filterIsInstance<Player>()
                    .minByOrNull { it.location.distanceSquared(self.location) }
                    ?.let { listOf(Target.OfEntity(it)) } ?: emptyList()
            }
            TargetKind.AOE -> {
                val radius = opts.double("radius", 1.0)
                val limit = opts.int("limit", 0)
                val kind = opts.string("target", "ALL").uppercase()
                self.getNearbyEntities(radius, radius, radius)
                    .filter { it is LivingEntity && it != self && matchesKind(kind, self, it, isAlly) }
                    .sortedBy { it.location.distanceSquared(self.location) }
                    .let { if (limit > 0) it.take(limit) else it }
                    .map { Target.OfEntity(it) }
            }
            TargetKind.ENTITY_IN_SIGHT -> {
                val distance = opts.double("distance", 20.0)
                val angle = Math.toRadians(opts.double("angle", 40.0))
                val limit = opts.int("limit", 5)
                val kind = opts.string("target", "ALL").uppercase()
                val eye = self.eyeLocation
                val facing = eye.direction.normalize()
                self.getNearbyEntities(distance, distance, distance)
                    .filter { it is LivingEntity && it != self && matchesKind(kind, self, it, isAlly) }
                    .filter { other ->
                        val to = other.location.toVector().add(org.bukkit.util.Vector(0.0, other.height / 2, 0.0)).subtract(eye.toVector())
                        to.length() <= distance && to.length() > 0 && facing.angle(to) <= angle
                    }
                    .sortedBy { it.location.distanceSquared(self.location) }
                    .take(if (limit > 0) limit else Int.MAX_VALUE)
                    .map { Target.OfEntity(it) }
            }
            TargetKind.BLOCK_IN_DISTANCE -> {
                val distance = opts.double("distance", 1.0)
                val hit = self.rayTraceBlocks(distance)?.hitBlock
                val block = hit ?: self.eyeLocation.add(self.eyeLocation.direction.multiply(distance)).block
                listOf(Target.OfBlock(block))
            }
            TargetKind.TRENCH -> {
                val center = ctx.block ?: return emptyList()
                if (opts.string("facing", "false").equals("true", ignoreCase = true)) {
                    return facing(center, self, opts).map { Target.OfBlock(it) }
                }
                val (sx, sy, sz) = dims(opts, "radius", 3)
                cube(center, sx, sy, sz).map { Target.OfBlock(it) }
            }
            TargetKind.TUNNEL -> {
                val center = ctx.block ?: return emptyList()
                tunnel(center, self, opts).map { Target.OfBlock(it) }
            }
            TargetKind.VEINMINE -> {
                val start = ctx.block ?: return emptyList()
                vein(start, opts.int("depth", 75).coerceIn(1, maxBlocks)).map { Target.OfBlock(it) }
            }
        }
    }

    private fun matchesKind(kind: String, self: Entity, other: Entity, isAlly: (Entity, Entity) -> Boolean): Boolean = when (kind) {
        "MOBS" -> other is Mob
        "PLAYERS" -> other is Player
        "DAMAGEABLE" -> !isAlly(self, other) && !other.isInvulnerable &&
            (other !is Player || other.gameMode.name in setOf("SURVIVAL", "ADVENTURE"))
        "UNDAMAGEABLE" -> isAlly(self, other)
        else -> true
    }

    /** `radius=3` → 3×3×3, `radiuscustom=3x1x3` → 가로×높이×세로. 짝수는 홀수로 올린다(가운데가 있어야 한다). */
    private fun dims(opts: TargetSpec, key: String, default: Int): Triple<Int, Int, Int> {
        opts.options["radiuscustom"]?.split('x', 'X', '*')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }?.let {
            return Triple(odd(it[0]), odd(it[1]), odd(it[2]))
        }
        val r = odd(opts.int(key, default))
        return Triple(r, r, r)
    }

    private fun odd(n: Int): Int = n.coerceIn(1, 15).let { if (it % 2 == 0) it + 1 else it }

    private fun cube(center: Block, sx: Int, sy: Int, sz: Int): List<Block> {
        val out = ArrayList<Block>()
        for (dx in -(sx / 2)..(sx / 2)) for (dy in -(sy / 2)..(sy / 2)) for (dz in -(sz / 2)..(sz / 2)) {
            if (dx == 0 && dy == 0 && dz == 0) continue
            out += center.getRelative(dx, dy, dz)
            if (out.size >= maxBlocks) return out
        }
        return out
    }

    /**
     * `facing=true` — 캔 **면**을 기준으로 한 판. `radiuscustom=가로x세로x깊이` 의 가로×세로는 바라보는 방향에 수직인 면이고,
     * 깊이는 캔 블록부터 바라보는 쪽으로 몇 칸. 올려다보거나 내려다보면(45° 넘게) 판이 눕는다.
     * 월드 축 그대로인 [cube] 는 북·남을 볼 때만 "앞면" 이고 동·서를 보면 옆으로 선 판이 된다 — 3×3×1 이 벽을 깎지 못했다.
     * 깊이는 짝수도 된다(가운데가 필요 없다).
     */
    private fun facing(center: Block, self: LivingEntity, opts: TargetSpec): List<Block> {
        val size = opts.options["radiuscustom"]?.split('x', 'X', '*')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }
            ?: listOf(3, 3, 1)
        val width = odd(size[0])
        val height = odd(size[1])
        val depth = size[2].coerceIn(1, 15)
        val pitch = self.location.pitch
        val out = ArrayList<Block>()
        if (pitch > 45 || pitch < -45) {
            // 바닥·천장: 가로 = 동서, 세로 = 남북, 깊이 = 아래(또는 위)
            val dy = if (pitch > 45) -1 else 1
            for (d in 0 until depth) for (x in -(width / 2)..(width / 2)) for (z in -(height / 2)..(height / 2)) {
                val b = center.getRelative(x, d * dy, z)
                if (b == center) continue
                out += b
                if (out.size >= maxBlocks) return out
            }
            return out
        }
        val face = horizontal(self)
        val right = if (face == BlockFace.NORTH || face == BlockFace.SOUTH) BlockFace.EAST else BlockFace.SOUTH
        for (d in 0 until depth) for (w in -(width / 2)..(width / 2)) for (h in -(height / 2)..(height / 2)) {
            val b = center.getRelative(face, d).getRelative(right, w).getRelative(0, h, 0)
            if (b == center) continue
            out += b
            if (out.size >= maxBlocks) return out
        }
        return out
    }

    /**
     * 캔 블록에서 **바라보는 방향으로** 파고드는 직육면체. mode 가 UP/DOWN 이면 계단처럼 한 칸씩 올라가거나
     * 내려간다. AUTO 는 내려다보면 DOWN, 올려다보면 UP, 아니면 STRAIGHT.
     */
    private fun tunnel(center: Block, self: LivingEntity, opts: TargetSpec): List<Block> {
        val size = opts.options["radiuscustom"]?.split('x', 'X')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 }
            ?: listOf(1, 3, 5)
        val width = odd(size[0])
        val height = size[1].coerceIn(1, 15)
        val depth = size[2].coerceIn(1, 32)
        val face = horizontal(self)
        val pitch = self.location.pitch
        val mode = opts.string("mode", "AUTO").uppercase().let {
            if (it != "AUTO") it else if (pitch > 35) "DOWN" else if (pitch < -35) "UP" else "STRAIGHT"
        }
        val right = if (face == BlockFace.NORTH || face == BlockFace.SOUTH) BlockFace.EAST else BlockFace.SOUTH
        val out = ArrayList<Block>()
        for (d in 0 until depth) {
            val step = when (mode) { "UP" -> d; "DOWN" -> -d; else -> 0 }
            val base = center.getRelative(face, d).getRelative(0, step, 0)
            for (w in -(width / 2)..(width / 2)) for (h in 0 until height) {
                val b = base.getRelative(right, w).getRelative(0, h - height / 2, 0)
                if (b == center) continue
                out += b
                if (out.size >= maxBlocks) return out
            }
        }
        return out.distinct()
    }

    private fun horizontal(entity: LivingEntity): BlockFace {
        val yaw = ((entity.location.yaw % 360) + 360) % 360
        return when {
            yaw < 45 || yaw >= 315 -> BlockFace.SOUTH
            yaw < 135 -> BlockFace.WEST
            yaw < 225 -> BlockFace.NORTH
            else -> BlockFace.EAST
        }
    }

    /** 같은 종류로 이어진 블록(26방향). 시작 블록은 빼고 준다 — 그건 이미 캐고 있다. */
    private fun vein(start: Block, depth: Int): List<Block> {
        val type = start.type
        val seen = HashSet<Block>()
        val queue = ArrayDeque<Block>()
        queue.add(start)
        seen.add(start)
        val out = ArrayList<Block>()
        while (queue.isNotEmpty() && out.size < depth) {
            val current = queue.removeFirst()
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (dx == 0 && dy == 0 && dz == 0) continue
                val next = current.getRelative(dx, dy, dz)
                if (next.type != type || !seen.add(next)) continue
                if (abs(next.y - start.y) > 64) continue
                out += next
                queue.add(next)
                if (out.size >= depth) break
            }
        }
        return out
    }
}
