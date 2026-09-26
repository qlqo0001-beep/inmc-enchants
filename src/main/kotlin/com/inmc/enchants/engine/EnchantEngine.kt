package com.inmc.enchants.engine

import com.inmc.enchants.Enchants
import com.inmc.enchants.effect.EffectRun
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.enchant.EnchantLevel
import com.inmc.enchants.item.EnchantStorage
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.Random
import java.util.logging.Level

/**
 * 발동 조건 하나가 일어났을 때 무엇이 도는지 정한다.
 *
 * ```
 * 장비(주 손·왼손·방어구) → 인첸트 → 발동 조건이 맞는가
 *   → 월드·봉인 → 조건식 → 쿨다운 → 확률 → 영혼 → (액션바) → 효과 줄들
 * ```
 *
 * 순서는 AE 와 같다. 조건식이 쿨다운보다 먼저인 것이 중요하다 — `%force%` 가 쿨다운을 무시해야 한다.
 *
 * **효과 하나가 던져도 사건은 계속된다.** 공격 한 번에 인첸트 다섯 개가 돌 수 있고, 하나의
 * 설정 실수가 나머지 넷과 바닐라 피해까지 날리면 안 된다. 잡아서 기록만 한다(같은 줄은 한 번).
 */
class EnchantEngine(private val e: Enchants) {

    private val random = Random()
    private val reported = HashSet<String>()

    /** 발동 조건마다 보는 칸. 왼손은 [Trigger.offHand] 가 허락할 때만. */
    private val slots = listOf(
        EquipmentSlot.HAND, EquipmentSlot.OFF_HAND,
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
    )

    /**
     * [TriggerContext.self] 의 장비 전체에서 이 발동 조건의 인첸트를 돌린다.
     *
     * @return 하나라도 발동했으면 true (검증기가 본다).
     */
    fun fire(ctx: TriggerContext): Boolean {
        val holder = ctx.self
        if (holder.world.name in e.config.disabledWorlds) return false
        var any = false
        for (slot in slots) {
            if (slot == EquipmentSlot.OFF_HAND && !ctx.trigger.offHand) continue
            val item = holder.itemIn(slot) ?: continue
            if (item.type.isAir || !fits(slot, item)) continue
            val enchants = EnchantStorage.read(item)
            if (enchants.isEmpty()) continue
            for ((id, level) in enchants) {
                if (runOne(ctx, id, level, item, slot)) any = true
            }
        }
        if (runSets(ctx)) any = true
        return any
    }

    /** 장비가 아니라 정해진 인첸트 묶음으로(투사체가 들고 온 활의 인첸트 등). */
    fun fireWith(ctx: TriggerContext, item: ItemStack?, enchants: Map<String, Int>, slot: EquipmentSlot? = null): Boolean {
        if (ctx.self.world.name in e.config.disabledWorlds) return false
        var any = false
        for ((id, level) in enchants) if (runOne(ctx, id, level, item, slot)) any = true
        // 쏜 순간의 무기는 [item] 이다. 입은 세트는 지금도 입고 있으니 돈다.
        if (runSets(ctx)) any = true
        return any
    }

    /** 커스텀아이템 세트의 인첸트 효과(입고 든 벌 수로 붙은 단계). 인첸트와 같은 길(조건·대기·확률)을 지난다. */
    private fun runSets(ctx: TriggerContext): Boolean {
        var any = false
        for ((def, slot) in e.setService.active(ctx.self)) {
            if (ctx.trigger !in def.triggers) continue
            val lvl = def.level(1) ?: continue
            ctx.item = ctx.self.itemIn(slot)
            ctx.slot = slot
            ctx.enchantId = def.id
            ctx.level = 1
            if (activate(ctx, def, lvl)) any = true
        }
        return any
    }

    /**
     * 방어구는 **그 부위에 입었을 때만**, 그 밖의 것은 **손에 들었을 때만** 센다.
     * 투구를 손에 들고 때렸는데 투구의 인첸트가 도는 일을 막는다.
     */
    private fun fits(slot: EquipmentSlot, item: ItemStack): Boolean {
        val piece = Applicability.armorPiece(item.type.name)
        return when (slot) {
            EquipmentSlot.HEAD -> piece == "HELMET" || !isHandOnly(item)
            EquipmentSlot.CHEST -> piece == "CHESTPLATE" || item.type.name == "ELYTRA"
            EquipmentSlot.LEGS -> piece == "LEGGINGS"
            EquipmentSlot.FEET -> piece == "BOOTS"
            else -> piece == null
        }
    }

    /** 머리에 쓸 수 있는 비방어구(호박·머리)는 머리 칸에서도 센다. */
    private fun isHandOnly(item: ItemStack): Boolean {
        val name = item.type.name
        return !(name == "CARVED_PUMPKIN" || name.endsWith("_HEAD") || name.endsWith("_SKULL"))
    }

    private fun runOne(ctx: TriggerContext, id: String, level: Int, item: ItemStack?, slot: EquipmentSlot?): Boolean {
        val def = e.registry.get(id) ?: return false
        if (ctx.trigger !in def.triggers) return false
        val lvl = def.level(level) ?: return false
        ctx.item = item
        ctx.slot = slot
        ctx.enchantId = id
        ctx.level = level
        return activate(ctx, def, lvl)
    }

    /**
     * 한 인첸트 한 레벨을 발동시켜 본다.
     *
     * @param forced 검증기용. 조건식·쿨다운·확률·영혼을 건너뛴다.
     */
    fun activate(ctx: TriggerContext, def: EnchantDefinition, lvl: EnchantLevel, forced: Boolean = false): Boolean {
        val holder = ctx.self
        val now = System.currentTimeMillis()
        ctx.settings = lvl.settings

        // 벗을 때는 되돌리기만 한다 — 월드·확률·쿨다운을 보면 안 된다. 금지 월드로 걸어 들어가는
        // 것이 바로 "벗는" 순간인데, 여기서 월드를 보고 돌아가면 걸린 효과가 영영 안 풀린다.
        if (ctx.removal) {
            runLines(ctx, lvl.effects, 0)
            return true
        }
        val world = holder.world.name
        if (world in def.settings.disabledWorlds || world in lvl.settings.worldBlacklist) return false
        // 레벨의 whitelist·blacklist 는 **캔 블록**을 거른다. 효과마다 보게 두면 안 보는 효과가 생긴다 — 제련·블록 바꾸기·
        // 씨앗 심기가 그래서 "광물만"·"돌만"·"밀만" 을 무시했다(자동 제련이 모래를, 보석화가 아무 블록이나 바꿨다).
        ctx.blockType?.let { if (!lvl.settings.allowsMaterial(it.name)) return false }
        if (ctx.trigger == Trigger.COMMAND) {
            // 레벨이 기다리는 명령어일 때만. 안 적었으면 아무 명령어에도 안 돈다(모든 명령어마다 도는 사고를 막는다).
            val want = lvl.command?.trim()?.removePrefix("/")?.lowercase()
            if (want.isNullOrEmpty() || ctx.values["command"] != want) return false
        }
        if (!forced && e.state.isDisabled(holder.uniqueId, def.id, now)) return false

        if (!forced) {
            val decision = Condition.decide(lvl.conditions) { e.variables.resolve(it, ctx) }
            if (!decision.activate) return false
            if (!decision.force) {
                val player = holder as? Player
                val bypassCooldown = player?.hasPermission("inmcenchant.bypass.cooldown") == true
                if (!bypassCooldown && e.state.onCooldown(holder.uniqueId, def.id, now)) return false
                val chance = lvl.chance + decision.chanceDelta
                if (chance < 100.0 && random.nextDouble() * 100.0 >= chance) return false
            }
            if (lvl.souls > 0) {
                val bypassSouls = (holder as? Player)?.hasPermission("inmcenchant.bypass.souls") == true
                if (!bypassSouls && !e.souls.take(holder, ctx.item, lvl.souls)) {
                    (holder as? Player)?.let { e.messages.send(it, "souls-not-enough", e.ph().enchant(def.id).amount(lvl.souls)) }
                    return false
                }
            }
            e.state.startCooldown(holder.uniqueId, def.id, lvl.cooldown, now)
        }

        if (def.settings.showActionBar && holder is Player && e.state.mayShowActionBar(holder.uniqueId, now)) {
            holder.sendActionBar(e.text(e.config.activationActionBar.replace("{enchant}", e.display(def, ctx.level))))
        }
        runLines(ctx, lvl.effects, 0)
        e.stats.activated(def.id)
        return true
    }

    /** [from] 부터 차례로. `WAIT` 를 만나면 나머지를 그만큼 뒤로 미룬다. */
    fun runLines(ctx: TriggerContext, lines: List<EffectLine>, from: Int) {
        for (index in from until lines.size) {
            val line = lines[index]
            if (line.effect == "WAIT") {
                // 벗을 때는 기다리지 않는다 — 되돌리기가 늦으면 그 사이 효과가 남는다.
                if (!line.allows(ctx.trigger) || ctx.removal) continue
                val ticks = resolveArgs(ctx, line).firstOrNull()?.toDoubleOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L
                val snapshot = ctx.fork()
                Bukkit.getScheduler().runTaskLater(e.plugin, Runnable {
                    if (snapshot.self.isValid) runLines(snapshot, lines, index + 1)
                }, ticks)
                return
            }
            runLine(ctx, line)
        }
    }

    private fun runLine(ctx: TriggerContext, line: EffectLine) {
        if (!line.allows(ctx.trigger)) return
        if (!ctx.removal) {
            line.chance?.let { raw ->
                val chance = Functions.apply(e.variables.resolve(raw, ctx), random).text.trim().toDoubleOrNull() ?: 100.0
                if (chance < 100.0 && random.nextDouble() * 100.0 >= chance) return
            }
            line.condition?.let { condition ->
                val decision = Condition.decide(listOf(condition)) { e.variables.resolve(it, ctx) }
                if (!decision.activate) return
            }
        }
        execute(ctx, line) { failure -> report(line, failure) }
    }

    /** 이 줄의 효과가 닿을 대상들. 검증기가 실행 전 상태를 찍으려고 먼저 묻는다. */
    fun targetsOf(ctx: TriggerContext, line: EffectLine): List<Target> {
        val spec = EffectSpecs.of(line.effect) ?: return emptyList()
        if (spec.acts == EffectSpec.Acts.EVENT) return emptyList()
        return line.targets.ifEmpty { listOf(TargetSpec(TargetKind.SELF)) }
            .flatMap { e.targets.resolve(it, ctx) { a, b -> e.support.isAlly(a, b) } }
    }

    /**
     * 한 줄을 **문지기 없이** 실행한다(포인터·줄 확률·줄 조건을 안 본다). 엔진은 문지기를 통과한
     * 줄만 여기로 보내고, 검증기는 곧바로 부른다.
     *
     * @param onError 효과가 던진 것. 엔진은 로그로, 검증기는 실패 이유로 받는다.
     */
    fun execute(ctx: TriggerContext, line: EffectLine, onError: (Throwable) -> Unit) {
        val spec = EffectSpecs.of(line.effect) ?: return
        if (ctx.removal && !spec.reversible) return
        val exec = e.effects.of(spec.name) ?: return onError(IllegalStateException("실행 코드 없음"))
        val args = resolveArgs(ctx, line)
        val override = line.location?.let { parseLocation(e.variables.resolve(it, ctx), ctx) }

        if (spec.acts == EffectSpec.Acts.EVENT) {
            try {
                exec.run(EffectRun(e, ctx, line, spec, args, null, override))
            } catch (t: Throwable) {
                onError(t)
            }
            return
        }
        for (target in targetsOf(ctx, line)) {
            try {
                exec.run(EffectRun(e, ctx, line, spec, args, target, override))
            } catch (t: Throwable) {
                onError(t)
            }
        }
    }

    /** 변수 → 함수 순서. `%random%` 은 함수가 뽑은 마지막 수다. */
    fun resolveArgs(ctx: TriggerContext, line: EffectLine): List<String> = line.args.map { raw ->
        val resolved = Functions.apply(e.variables.resolve(raw, ctx), random)
        resolved.lastRandom?.let { ctx.values["random"] = it.toString() }
        resolved.text
    }

    /** `~0|0|-8`(발동한 쪽 기준 상대) · `10|64|10`(절대) · 치환된 `%hit location%`. */
    fun parseLocation(raw: String, ctx: TriggerContext): Location? {
        val relative = raw.startsWith("~")
        val parts = raw.removePrefix("~").split('|').map { it.trim().toDoubleOrNull() }
        if (parts.size != 3 || parts.any { it == null }) return null
        val base = ctx.self.location
        return if (relative) {
            base.clone().add(parts[0]!!, parts[1]!!, parts[2]!!)
        } else {
            Location(base.world, parts[0]!!, parts[1]!!, parts[2]!!, base.yaw, base.pitch)
        }
    }

    private fun report(line: EffectLine, t: Throwable) {
        if (!reported.add(line.raw)) return
        e.logger.log(Level.WARNING, "효과 실행 실패: " + line.raw + " (" + t.javaClass.simpleName + ": " + t.message + ")", t)
    }
}

/** WAIT 뒤를 늦게 돌릴 때 쓰는 사본. 원본은 다음 인첸트가 item·level 을 바꿔 쓴다. */
fun TriggerContext.fork(): TriggerContext {
    val copy = TriggerContext(trigger, self, attacker, victim, event, block, hitLocation, removal, HashMap(values))
    copy.item = item
    copy.slot = slot
    copy.enchantId = enchantId
    copy.level = level
    copy.settings = settings
    return copy
}
