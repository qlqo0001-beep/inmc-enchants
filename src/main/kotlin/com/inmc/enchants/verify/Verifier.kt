package com.inmc.enchants.verify

import com.inmc.enchants.Enchants
import com.inmc.enchants.effect.EffectRun
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.enchant.EnchantLevel
import com.inmc.enchants.enchant.LevelSettings
import com.inmc.enchants.engine.EffectLine
import com.inmc.enchants.engine.EffectSpec
import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.engine.Target
import com.inmc.enchants.engine.TargetKind
import com.inmc.enchants.engine.TargetSpec
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.engine.TriggerContext
import com.inmc.enchants.item.EnchantStorage
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `/인첸트 검증` — 효과·인첸트·발동 조건을 **서버 안에서 실제로 돌려** 확인한다.
 *
 * | 방식 | 무엇을 | 어떻게 |
 * |---|---|---|
 * | 효과 | 효과 하나하나의 실행 코드 | [Probes.SAMPLES] 의 표본 줄을 돌리고 [Probes] 로 전후 비교 |
 * | 인첸트 | 배포·편집된 인첸트의 **모든 레벨의 모든 줄** | 그 줄 그대로를 그 인첸트의 발동 조건 맥락에서 |
 * | 발동 조건 | 리스너 배선 | [Wiring] 이 가짜 사건을 우리 리스너에 넘긴다 |
 * | 아이템 | 부여서·가루·스크롤·확장기·오브·추적기·영혼석 규칙 | [ItemChecks] 가 아이템을 만들어 써 본다 |
 * | 화면 | 모든 화면의 이동과 버튼 | [MenuChecks] 가 화면을 열고 진짜 클릭 사건을 쏜다 |
 *
 * 줄을 돌릴 때는 확률·조건·쿨다운을 건너뛴다([com.inmc.enchants.engine.EnchantEngine.execute]) —
 * 5% 확률 효과를 검사하려고 스무 번 때릴 수는 없다. 문지기는 순수 로직이라 테스트가 따로 덮는다.
 *
 * 한 번에 하나만 돈다. 건마다 [Stage.between] 이 무대와 사람을 되돌린다.
 */
class Verifier(private val e: Enchants) : Listener {

    enum class Status(val label: String) { PASS("통과"), FAIL("실패"), OBSERVE("눈으로 확인"), SKIP("건너뜀") }

    data class Result(val subject: String, val status: Status, val detail: String = "")

    enum class Mode(val label: String) { EFFECTS("효과"), ENCHANTS("인첸트"), TRIGGERS("발동 조건"), ITEMS("아이템"), MENUS("화면"), ALL("전체") }

    /** 한 건. [subject] 는 이 건이 던졌을 때 보고서에 적을 이름이다. */
    private class Step(val subject: String, val run: (done: () -> Unit) -> Unit)

    private inner class Session(val player: Player, val mode: Mode) {
        val stage = Stage(e, player)
        val results = ArrayList<Result>()
        val steps = ArrayDeque<Step>()
        var total = 0
    }

    private var session: Session? = null

    val busy: Boolean get() = session != null

    /**
     * @param only 효과 이름이나 인첸트 id. null 이면 그 방식 전부.
     * @return 시작했으면 true.
     */
    fun start(player: Player, mode: Mode, only: String?): Boolean {
        if (session != null) {
            e.messages.send(player, "verify-busy", e.ph())
            return false
        }
        val s = Session(player, mode)
        session = s
        e.registry.putTransient(probeDef(Trigger.ATTACK_MOB, emptyList()))
        if (mode == Mode.EFFECTS || mode == Mode.ALL) effectSteps(s, only)
        if (mode == Mode.ENCHANTS || mode == Mode.ALL) enchantSteps(s, only)
        if (mode == Mode.TRIGGERS || mode == Mode.ALL) wiringSteps(s, only)
        if (mode == Mode.ITEMS || mode == Mode.ALL) itemSteps(s, "아이템 ", ItemChecks.ALL)
        if (mode == Mode.MENUS || mode == Mode.ALL) itemSteps(s, "화면 ", MenuChecks.ALL)
        s.total = s.steps.size
        if (s.total == 0 && s.results.isEmpty()) {
            finish(s, aborted = null)
            e.messages.send(player, "verify-nothing", e.ph())
            return false
        }
        e.messages.send(player, "verify-start", e.ph().value(mode.label).count(s.total))
        next(s)
        return true
    }

    /** 검증하던 사람이 나가거나 서버가 내려간다. 무대와 사람을 되돌리고 모은 것까지 보고한다. */
    fun abort(reason: String) {
        val s = session ?: return
        finish(s, aborted = reason)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onQuit(event: PlayerQuitEvent) {
        if (session?.player == event.player) abort("검증하던 사람이 나갔습니다")
    }

    /** 검증 도중 죽으면 가방을 떨구지 않게 하고 멈춘다. */
    @EventHandler(priority = EventPriority.LOWEST)
    fun onDeath(event: PlayerDeathEvent) {
        if (session?.player != event.player) return
        event.keepInventory = true
        event.keepLevel = true
        event.drops.clear()
        event.droppedExp = 0
        abort("검증하던 사람이 죽었습니다")
    }

    // --- 단계 만들기 -----------------------------------------------------------------------

    private fun effectSteps(s: Session, only: String?) {
        val specs = EffectSpecs.ALL.filter { only == null || it.name.equals(only, ignoreCase = true) }
        for (spec in specs) {
            val subject = "효과 " + spec.name
            val line = Probes.SAMPLES[spec.name]?.let(::parse)
            if (line == null) {
                s.results += Result(subject, Status.FAIL, "표본 줄이 없거나 읽히지 않는다")
                continue
            }
            val trigger = when {
                spec.triggers.isNotEmpty() -> spec.triggers.first()
                spec.acts == EffectSpec.Acts.BLOCK -> Trigger.MINING
                else -> Trigger.ATTACK_MOB
            }
            val item = if (trigger in Contexts.FISHING) Material.FISHING_ROD else Material.DIAMOND_PICKAXE
            s.steps += Step(subject) { done -> runCase(s, Plan(subject, line, trigger, item, EquipmentSlot.HAND), done) }
            Probes.STATIC_SAMPLES[spec.name]?.let(::parse)?.let { held ->
                val heldSubject = "$subject (들고 있는 동안 → 내려놓기)"
                s.steps += Step(heldSubject) { done -> runCase(s, Plan(heldSubject, held, Trigger.HELD, item, EquipmentSlot.HAND, staticPass = true), done) }
            }
        }
        if (only == null) s.steps += Step(REMOVAL_GATE) { done -> removalGate(s, done) }
    }

    private fun enchantSteps(s: Session, only: String?) {
        val defs = e.registry.all().filter { only == null || it.id.equals(only, ignoreCase = true) }
        for (problem in e.registry.problems) {
            val id = problem.substringBefore(':').substringBefore(' ')
            if (only == null || id.equals(only, ignoreCase = true)) s.results += Result(id, Status.FAIL, "설정: $problem")
        }
        for (def in defs) {
            for ((number, lvl) in def.levels.toSortedMap()) {
                lvl.effects.forEachIndexed { index, line ->
                    val subject = "${def.id} ${number}레벨 #${index + 1} ${line.raw}"
                    val trigger = def.triggers.firstOrNull { line.allows(it) }
                    when {
                        trigger == null -> s.results += Result(subject, Status.FAIL, "이 줄이 도는 발동 조건이 인첸트에 없다")
                        line.effect == "WAIT" -> s.results += Result(subject, Status.OBSERVE, "기다리기")
                        else -> {
                            val (material, slot) = itemFor(def)
                            val plan = Plan(subject, line, trigger, material, slot, def.id, number, lvl.settings, lvl.command, staticPass = trigger.static)
                            s.steps += Step(subject) { done -> runCase(s, plan, done) }
                        }
                    }
                }
            }
        }
    }

    private fun wiringSteps(s: Session, only: String?) {
        for (case in Wiring.CASES) {
            if (only != null && !case.trigger.name.equals(only, ignoreCase = true)) continue
            val subject = "발동 " + case.trigger.name + " (" + case.label + ")"
            if (case.skip != null) {
                s.results += Result(subject, Status.SKIP, case.skip)
                continue
            }
            s.steps += Step(subject) { done -> runWiring(s, subject, case, done) }
        }
    }

    private fun itemSteps(s: Session, prefix: String, checks: List<ItemChecks.Check>) {
        for (check in checks) {
            val subject = prefix + check.name
            s.steps += Step(subject) { done ->
                val (status, detail) = try {
                    check.run(e, s.player)?.let { Status.FAIL to it } ?: (Status.PASS to "")
                } catch (missing: ItemChecks.Missing) {
                    Status.SKIP to missing.message.orEmpty()
                }
                record(s, subject, status, detail, done)
            }
        }
    }

    // --- 진행 -----------------------------------------------------------------------------

    private fun next(s: Session) {
        if (session !== s) return
        if (!s.player.isOnline) return abort("검증하던 사람이 나갔습니다")
        val step = s.steps.removeFirstOrNull() ?: return finish(s, aborted = null)
        s.player.sendActionBar(e.messages.component("verify-progress", e.ph().count(s.total - s.steps.size).amount(s.total)))
        var finished = false
        val done = {
            if (!finished) {
                finished = true
                later(1) {
                    if (session === s) {
                        s.stage.between()
                        next(s)
                    }
                }
            }
        }
        try {
            step.run(done)
        } catch (t: Throwable) {
            s.results += Result(step.subject, Status.FAIL, "검증기 오류: " + describe(t))
            done()
        }
    }

    private fun finish(s: Session, aborted: String?) {
        if (session === s) session = null
        s.stage.close()
        e.registry.removeTransient(PROBE_ID)
        e.state.variables.remove(VAR)
        e.state.variables.remove("verify_var")

        val count = Status.entries.associateWith { status -> s.results.count { it.status == status } }
        val player = s.player
        if (aborted != null) e.messages.send(player, "verify-aborted", e.ph().value(aborted))
        e.messages.send(
            player, "verify-done",
            e.ph().passed(count.getValue(Status.PASS)).failed(count.getValue(Status.FAIL))
                .observed(count.getValue(Status.OBSERVE)).skipped(count.getValue(Status.SKIP)),
        )
        val failures = s.results.filter { it.status == Status.FAIL }
        for (failure in failures.take(10)) e.messages.send(player, "verify-failure", e.ph().item(failure.subject).value(failure.detail))
        if (failures.size > 10) e.messages.send(player, "verify-more", e.ph().count(failures.size - 10))

        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val file = e.io.file("verify", "${s.mode.name.lowercase()}-$stamp.txt")
        val text = report(s, aborted)
        e.io.asyncRun {
            file.parentFile.mkdirs()
            file.writeText(text)
        }
        e.messages.send(player, "verify-report", e.ph().file("plugins/${e.plugin.name}/verify/${file.name}"))
    }

    private fun report(s: Session, aborted: String?): String = buildString {
        appendLine("# inmc-enchants 검증 - ${s.mode.label} - ${LocalDateTime.now()}")
        if (aborted != null) appendLine("# 멈춤: $aborted")
        for (status in Status.entries) {
            val rows = s.results.filter { it.status == status }
            appendLine()
            appendLine("## ${status.label} (${rows.size})")
            for (row in rows) appendLine("- ${row.subject}" + if (row.detail.isNotEmpty()) " — ${row.detail}" else "")
        }
    }

    private fun later(ticks: Long, block: () -> Unit) {
        if (ticks <= 0) block() else Bukkit.getScheduler().runTaskLater(e.plugin, Runnable { block() }, ticks)
    }

    // --- 효과 한 줄 -------------------------------------------------------------------------

    private class Plan(
        val subject: String,
        val line: EffectLine,
        val trigger: Trigger,
        val item: Material,
        val slot: EquipmentSlot,
        val enchantId: String = PROBE_ID,
        val level: Int = 1,
        val settings: LevelSettings = LevelSettings(),
        val command: String? = null,
        /** 걸고 판정한 뒤 벗기고, 되돌릴 수 있는 효과면 사라졌는지까지 본다. */
        val staticPass: Boolean = false,
    )

    private fun runCase(s: Session, plan: Plan, done: () -> Unit) {
        val player = s.player
        val spec = EffectSpecs.of(plan.line.effect) ?: return record(s, plan.subject, Status.FAIL, "모르는 효과", done)
        val probe = Probes.ALL[spec.name] ?: return record(s, plan.subject, Status.FAIL, "검사식이 없다", done)

        val mob = s.stage.spawnVictim()
        val area = plan.line.targets.filter { it.kind in AREA_TARGETS }
        // 자기 자리에서 터지는 폭발은 자신을 빼고 친다 — 반경 안에 칠 것이 있어야 한다.
        if (spec.name in BLASTS) s.stage.spawnBystander()
        if (area.any { it.string("target", "ALL").equals("UNDAMAGEABLE", ignoreCase = true) }) s.stage.spawnAlly()
        if (area.any { !it.string("target", "ALL").equals("UNDAMAGEABLE", ignoreCase = true) }) s.stage.spawnBystander()
        // 맞는 쪽은 싸우는 중이다. 가득 찬 체력이면 "잃은 체력에 비례" 하는 줄이 0 이 된다.
        if (plan.trigger in Contexts.DEFENSE || plan.trigger in Contexts.ENVIRONMENT) {
            player.health = (player.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0) / 2
        }
        val block = s.stage.build(sceneFor(plan, spec), sceneMaterial(plan))
        // 다시 심기(AUTO)는 캔 작물을 본다 — 흙 위에 작물을 세워 그걸 캔 블록으로 사건을 만들고, 캔 뒤처럼 비운다.
        val harvested = if (plan.line.effect == "PLANT_SEEDS" && plan.line.args.getOrNull(1).equals("AUTO", ignoreCase = true)) {
            block?.getRelative(0, 1, 0)?.also { it.setType(Material.WHEAT, false) }
        } else {
            null
        }
        val item = ItemStack(plan.item)
        player.equipment.setItem(plan.slot, item)
        // 도구를 쥔 **뒤에** 넣는다. 먼저 넣으면 빈 손 칸에 들어간 씨앗을 도구가 덮어쓴다.
        if (plan.line.effect == "PLANT_SEEDS") SEEDS.forEach { player.inventory.addItem(ItemStack(it, 16)) }

        fun context(removal: Boolean): TriggerContext =
            Contexts.of(plan.trigger, player, mob, harvested ?: block, item, removal, plan.command).also {
                it.item = item
                it.slot = plan.slot
                it.enchantId = plan.enchantId
                it.level = plan.level
                it.settings = plan.settings
            }

        // 실전에서는 리스너가 타격을 먼저 센다. 연속 3타째로 두어 %combo% 를 쓰는 줄이 0 을 보지 않게 한다.
        if (plan.trigger in Contexts.ATTACK) repeat(3) { e.state.hit(player.uniqueId, mob.uniqueId, System.currentTimeMillis(), e.config.comboWindowMillis) }
        val ctx = context(removal = false)
        harvested?.setType(Material.AIR, false)
        val targets: List<Target?> = if (spec.acts == EffectSpec.Acts.EVENT) listOf(null) else e.engine.targetsOf(ctx, plan.line)
        if (targets.isEmpty() && plan.line.targets.isNotEmpty() && plan.line.targets.all(::needsOtherPlayers)) {
            return record(s, plan.subject, Status.SKIP, "주변의 다른 플레이어가 대상이다 - 두 사람이 게임 안에서 확인", done)
        }
        if (targets.isEmpty()) {
            return record(s, plan.subject, Status.FAIL, "대상이 비어 있다 - '${plan.trigger.label}' 에는 이 줄의 대상이 없다", done)
        }
        val args = e.engine.resolveArgs(ctx, plan.line)
        val runs = targets.map { EffectRun(e, ctx, plan.line, spec, args, it, null) }
        runs.firstNotNullOfOrNull { probe.skip(it) }?.let { return record(s, plan.subject, Status.SKIP, it, done) }
        if (spec.name in Probes.PLAYER_ONLY && runs.any { it.entity != null && it.entity !is Player }) {
            return record(s, plan.subject, Status.SKIP, "플레이어에게만 듣는 효과인데 무대의 상대는 몹이다 - 효과 자체는 효과 검증에서 확인", done)
        }
        // 지속형 검사는 준비 없이 — 준비가 바꾼 값과 벗긴 뒤 값이 달라 "남아 있다"로 오판한다.
        if (!plan.staticPass) runs.forEach { probe.prepare(it, it.entity) }

        val nearby = s.stage.nearby()
        val blocks = s.stage.blocks()
        val health = s.stage.health()
        val cases = runs.map { Probes.Case(it, it.entity, Probes.shot(it.entity, player, plan.slot, e), eventDamage(ctx), nearby) }
        var thrown: Throwable? = null
        e.engine.execute(ctx, plan.line) { if (thrown == null) thrown = it }

        fun observe() {
            val nearbyAfter = s.stage.nearby()
            val changed = s.stage.changed(blocks)
            val hurt = s.stage.hurt(health)
            for (case in cases) {
                case.hurtOthers = hurt
                Probes.shot(case.target, player, plan.slot, e)?.let { case.after = it }
                case.eventDamageAfter = eventDamage(ctx)
                case.nearbyAfter = nearbyAfter
                case.changedBlocks = changed
            }
        }

        fun finish(status: Status, detail: String) {
            for (case in cases) runCatching { probe.cleanup(case) }
            record(s, plan.subject, status, detail, done)
        }

        later(probe.delay) {
            observe()
            val verdict = thrown?.let { "효과가 오류를 냈다: " + describe(it) } ?: judge(probe, cases)
            when {
                verdict != null -> finish(Status.FAIL, verdict)
                spec.name in Probes.OBSERVE_ONLY -> finish(Status.OBSERVE, "오류 없이 실행됨")
                !plan.staticPass || !spec.reversible -> finish(Status.PASS, "")
                else -> {
                    e.engine.execute(context(removal = true), plan.line) { if (thrown == null) thrown = it }
                    later(1) {
                        observe()
                        val stays = cases.count { runCatching { probe.check(it) }.getOrNull() == null }
                        when {
                            thrown != null -> finish(Status.FAIL, "벗길 때 오류: " + describe(thrown!!))
                            stays > 0 -> finish(Status.FAIL, "내려놓은 뒤에도 효과가 남아 있다")
                            else -> finish(Status.PASS, "걸렸다가 내려놓자 풀렸다")
                        }
                    }
                }
            }
        }
    }

    /**
     * 엔진의 문 하나 — 지속형을 벗을 때 되돌릴 수 없는 효과(허기 채우기)는 **돌지 않아야** 한다.
     * 안 그러면 "웅크리면 튕겨 오른다"가 웅크림을 풀 때 또 튕긴다.
     */
    private fun removalGate(s: Session, done: () -> Unit) {
        val subject = REMOVAL_GATE
        val player = s.player
        val line = parse("ADD_FOOD:4") ?: return record(s, subject, Status.FAIL, "표본 줄이 읽히지 않는다", done)
        player.foodLevel = 5
        val ctx = Contexts.of(Trigger.HELD, player, s.stage.spawnVictim(), null, ItemStack(Material.DIAMOND_SWORD), removal = true)
        e.engine.execute(ctx, line) {}
        val status = if (player.foodLevel == 5) Status.PASS else Status.FAIL
        record(s, subject, status, if (status == Status.PASS) "" else "허기가 5 → ${player.foodLevel}", done)
    }

    private fun judge(probe: Probes.Probe, cases: List<Probes.Case>): String? = cases.firstNotNullOfOrNull { case ->
        try {
            probe.check(case)
        } catch (t: Throwable) {
            "검사 중 오류: " + describe(t)
        }
    }

    private fun sceneFor(plan: Plan, spec: EffectSpec): Stage.Scene = when {
        plan.line.effect == "BREAK_TREE" -> Stage.Scene.TREE
        plan.line.effect == "PLANT_SEEDS" -> Stage.Scene.FARM
        plan.trigger == Trigger.MINING || spec.acts == EffectSpec.Acts.BLOCK || plan.line.targets.any { it.kind.isBlockTarget } -> Stage.Scene.CUBE
        else -> Stage.Scene.NONE
    }

    /** 돌 대신 레벨 whitelist 의 첫 블록으로 쌓는다 — 광석·작물 전용 인첸트가 돌에서 안 돈다고 실패하면 안 된다. */
    private fun sceneMaterial(plan: Plan): Material = when {
        plan.line.effect == "PLANT_SEEDS" -> if (plan.line.args.getOrNull(1).equals("NETHER_WART", ignoreCase = true)) Material.SOUL_SAND else Material.FARMLAND
        else -> plan.settings.whitelist.firstNotNullOfOrNull { Material.matchMaterial(it)?.takeIf { m -> m.isBlock && !m.isAir } } ?: Material.STONE
    }

    private fun needsOtherPlayers(spec: TargetSpec): Boolean = when (spec.kind) {
        TargetKind.NEAREST_PLAYER, TargetKind.ALL_PLAYERS, TargetKind.PLAYER_FROM_NAME -> true
        TargetKind.AOE, TargetKind.ENTITY_IN_SIGHT -> spec.string("target", "ALL").equals("PLAYERS", ignoreCase = true)
        else -> false
    }

    private fun eventDamage(ctx: TriggerContext): Double? = (ctx.event as? EntityDamageEvent)?.damage

    // --- 발동 조건 배선 ---------------------------------------------------------------------

    private fun runWiring(s: Session, subject: String, case: Wiring.Case, done: () -> Unit) {
        val player = s.player
        val line = parse("SET_VARIABLE:$VAR:${case.trigger.name}") ?: return record(s, subject, Status.FAIL, "검증 줄이 읽히지 않는다", done)
        e.registry.putTransient(probeDef(case.trigger, listOf(line)))
        val mob = s.stage.spawnVictim()
        val block = s.stage.build(Stage.Scene.CUBE) ?: s.stage.blockCenter
        val item = ItemStack(case.item)
        EnchantStorage.write(item, mutableMapOf(PROBE_ID to 1))
        when (case.holder) {
            Wiring.Holder.PLAYER -> player.inventory.setItemInMainHand(item)
            Wiring.Holder.MOB -> mob.equipment.setItemInMainHand(item)
            Wiring.Holder.HEAD -> player.inventory.setHelmet(item)
        }
        e.state.variables.remove(VAR)
        try {
            case.fire(Wiring.Kit(e, player, mob, block, item))
        } catch (skip: Wiring.Skip) {
            return record(s, subject, Status.SKIP, skip.message.orEmpty(), done)
        } catch (t: Throwable) {
            return record(s, subject, Status.FAIL, "사건을 넘기다 오류: " + describe(t), done)
        }
        later(1) {
            val got = e.state.variables[VAR]
            if (got == case.trigger.name) {
                record(s, subject, Status.PASS, "", done)
            } else {
                record(s, subject, Status.FAIL, "리스너가 이 발동 조건으로 인첸트를 돌리지 않았다", done)
            }
        }
    }

    // --- 도움 -----------------------------------------------------------------------------

    private fun record(s: Session, subject: String, status: Status, detail: String, done: () -> Unit) {
        s.results += Result(subject, status, detail)
        done()
    }

    private fun parse(raw: String): EffectLine? = EffectLine.parse(raw, EffectSpecs::shape).line

    private fun describe(t: Throwable): String = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")

    /** 인첸트가 붙을 수 있는 것 중 대표 하나와 그것을 두는 칸. */
    private fun itemFor(def: EnchantDefinition): Pair<Material, EquipmentSlot> {
        val groups = e.config.appliesGroups
        val candidates = def.applies.mapNotNull { Material.matchMaterial(it) } + REPRESENTATIVES
        val material = candidates.firstOrNull { Applicability.matchesAny(def.applies, it.name, groups) } ?: Material.DIAMOND_SWORD
        val slot = when (Applicability.armorPiece(material.name)) {
            "HELMET" -> EquipmentSlot.HEAD
            "CHESTPLATE" -> EquipmentSlot.CHEST
            "LEGGINGS" -> EquipmentSlot.LEGS
            "BOOTS" -> EquipmentSlot.FEET
            else -> if (material == Material.ELYTRA) EquipmentSlot.CHEST else EquipmentSlot.HAND
        }
        return material to slot
    }

    private fun probeDef(trigger: Trigger, effects: List<EffectLine>) = EnchantDefinition(
        id = PROBE_ID,
        display = "<gray>검증용",
        description = listOf("검증기가 잠깐 쓰는 인첸트"),
        appliesTo = "모든 것",
        triggers = listOf(trigger),
        group = "SIMPLE",
        applies = emptyList(),
        levels = mapOf(1 to EnchantLevel(command = PROBE_COMMAND, effects = effects)),
    )

    companion object {
        /** 검증용 인첸트 id. 저장되지 않는다([com.inmc.enchants.enchant.EnchantRegistry.putTransient]). */
        const val PROBE_ID = "verify_probe"
        const val PROBE_COMMAND = "verifyprobe"
        private const val VAR = "verify_trigger"
        /** 주변을 훑는 대상. 무대의 몹은 5칸 떨어져 있어서, 이런 줄에는 가까이 구경꾼을 하나 더 세운다. */
        private val AREA_TARGETS = setOf(TargetKind.AOE, TargetKind.ENTITY_IN_SIGHT)
        private val BLASTS = setOf("TNT", "EXPLODE")
        private const val REMOVAL_GATE = "엔진: 내려놓을 때 되돌릴 수 없는 효과는 돌지 않는다"

        private val SEEDS = listOf(
            Material.WHEAT_SEEDS, Material.POTATO, Material.CARROT, Material.BEETROOT_SEEDS,
            Material.MELON_SEEDS, Material.PUMPKIN_SEEDS, Material.NETHER_WART,
        )

        private val REPRESENTATIVES = listOf(
            Material.DIAMOND_SWORD, Material.DIAMOND_AXE, Material.DIAMOND_PICKAXE, Material.DIAMOND_SHOVEL, Material.DIAMOND_HOE,
            Material.DIAMOND_HELMET, Material.DIAMOND_CHESTPLATE, Material.DIAMOND_LEGGINGS, Material.DIAMOND_BOOTS,
            Material.BOW, Material.CROSSBOW, Material.TRIDENT, Material.MACE, Material.FISHING_ROD,
            Material.ELYTRA, Material.SHIELD, Material.SHEARS, Material.GOAT_HORN,
        )
    }
}
