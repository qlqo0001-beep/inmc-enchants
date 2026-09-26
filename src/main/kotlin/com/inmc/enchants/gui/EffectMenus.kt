package com.inmc.enchants.gui

import com.inmc.enchants.Enchants
import com.inmc.enchants.engine.ArgSpec
import com.inmc.enchants.engine.ArgType
import com.inmc.enchants.engine.Condition
import com.inmc.enchants.engine.EffectLine
import com.inmc.enchants.engine.EffectSpec
import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.engine.Names
import com.inmc.enchants.engine.TargetKind
import com.inmc.enchants.engine.TargetSpec
import com.inmc.enchants.engine.Trigger
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent

/** 효과 줄을 레벨에 되써 넣는 한 자리. 목록·줄 편집이 같이 쓴다. */
private class Lines(private val ref: LevelRef) {

    fun get(): List<EffectLine> = ref.get()?.effects.orEmpty()

    fun set(lines: List<EffectLine>) {
        ref.get()?.let { ref.set(it.copy(effects = lines)) }
    }

    /** 원문을 다시 읽어 확인한 줄. 모양이 틀리면 null. */
    fun parse(raw: String): EffectLine? = EffectLine.parse(raw, EffectSpecs::shape).line

    /** 부분만 바꾼 줄을 원문까지 맞춘다(원문이 곧 저장되는 값이다). */
    fun rebuild(line: EffectLine): EffectLine {
        val raw = line.serialize()
        return parse(raw) ?: line.copy(raw = raw)
    }
}

/** 한 레벨의 효과 줄 목록. 순서가 실행 순서다(`WAIT` 가 뒤를 늦춘다). */
class EffectListMenu(e: Enchants, viewer: Player, private val ref: LevelRef) :
    Menu(e, viewer, 54, "<dark_red>${ref.title} 효과</dark_red>") {

    private val lines = Lines(ref)

    override val back: (() -> Unit) = { LevelEditMenu(e, viewer, ref).show() }

    override fun draw() {
        clear()
        for ((index, line) in lines.get().take(36).withIndex()) {
            val spec = EffectSpecs.of(line.effect)
            set(index, Icon.of(Material.BLAZE_POWDER, "<white>" + (index + 1) + ". " + (spec?.label ?: line.effect), listOf(
                "<gray>" + line.raw,
                "", "<yellow>▶ 클릭: 편집",
            ))) { EffectLineMenu(e, viewer, ref, index).show() }
        }
        set(48, Icon.of(Material.LIME_DYE, "<green>효과 추가", "<gray>분류 → 효과를 고르면 기본값으로 들어가고", "<gray>줄 편집 화면이 열립니다.")) {
            pickEffect(e, viewer, back = { show() }) { spec ->
                val defaults = spec.args.map { it.default.ifEmpty { sample(it) } }
                val raw = (listOf(spec.name) + defaults).joinToString(":").trimEnd(':') + if (spec.acts == EffectSpec.Acts.EVENT) "" else " @Victim"
                val line = lines.parse(raw) ?: lines.parse(spec.name) ?: return@pickEffect
                lines.set(lines.get() + line)
                EffectLineMenu(e, viewer, ref, lines.get().size - 1).show()
            }
        }
        set(50, Icon.of(Material.PAPER, "<yellow>원문으로 추가", "<gray>AE 문법 한 줄을 그대로 붙여 넣습니다.")) {
            Editors.promptText(e.prompts, viewer, "효과 한 줄", listOf("<gray>예: <white>POTION:SLOWNESS:1:60 @Victim"), reopen = { show() }) { input ->
                val parsed = EffectLine.parse(input.trim(), EffectSpecs::shape)
                val line = parsed.line
                if (line == null) {
                    e.messages.send(viewer, "admin-bad-line", e.ph().value(parsed.error ?: input))
                    return@promptText
                }
                lines.set(lines.get() + line)
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }

    /** 기본값이 없는 필수 칸에 넣을 무난한 값. 관리자가 바로 고친다. */
    private fun sample(arg: ArgSpec): String = when (arg.type) {
        ArgType.NUMBER, ArgType.INT -> "1"
        ArgType.BOOL -> "true"
        ArgType.POTION -> "SPEED"
        ArgType.SOUND -> "ENTITY_EXPERIENCE_ORB_PICKUP"
        ArgType.PARTICLE -> "FLAME"
        ArgType.MATERIAL, ArgType.ITEM -> "STONE"
        ArgType.ENTITY_TYPE -> "ZOMBIE"
        ArgType.CHOICE -> arg.choices.firstOrNull().orEmpty()
        ArgType.ENCHANT -> e.registry.ids().firstOrNull().orEmpty()
        ArgType.COLOR -> "RED"
        ArgType.TEXT, ArgType.WORD -> "text"
    }
}

/** 효과 고르기: 분류 → 효과. */
internal fun pickEffect(e: Enchants, viewer: Player, back: () -> Unit, onPick: (EffectSpec) -> Unit) {
    PickMenu(
        e, viewer, "<dark_red>효과 분류</dark_red>", EffectSpec.Category.entries,
        icon = { c -> Icon.of(Material.BOOKSHELF, "<yellow>" + c.label, "<gray>" + EffectSpecs.ALL.count { it.category == c } + "개") },
        back = back,
    ) { category ->
        PickMenu(
            e, viewer, "<dark_red>" + category.label + "</dark_red>", EffectSpecs.ALL.filter { it.category == category },
            icon = { spec ->
                Icon.of(Material.BLAZE_POWDER, "<yellow>" + spec.label, listOf("<gray>" + spec.description, "<dark_gray>" + spec.name,
                    "<gray>인자: <white>" + spec.args.joinToString(", ") { it.label }.ifEmpty { "없음" }))
            },
            back = { pickEffect(e, viewer, back, onPick) },
            onPick = onPick,
        ).open(viewer)
    }.open(viewer)
}

/**
 * 효과 한 줄. 칸마다 그 **종류에 맞는 편집기**를 띄운다 — 물약은 목록에서 고르고, 숫자는 넛지·입력,
 * 보기 칸은 순환. 무엇이든 원문 입력으로도 고칠 수 있다.
 */
class EffectLineMenu(e: Enchants, viewer: Player, private val ref: LevelRef, private val index: Int) :
    Menu(e, viewer, 54, "<dark_red>효과 줄 편집</dark_red>") {

    private val lines = Lines(ref)

    override val back: (() -> Unit) = { EffectListMenu(e, viewer, ref).show() }

    private fun line(): EffectLine? = lines.get().getOrNull(index)

    private fun replace(next: EffectLine) {
        val all = lines.get().toMutableList()
        if (index !in all.indices) return
        all[index] = lines.rebuild(next)
        lines.set(all)
    }

    private fun setArg(i: Int, value: String) {
        val line = line() ?: return
        val spec = EffectSpecs.of(line.effect) ?: return
        val args = MutableList(maxOf(line.args.size, i + 1, spec.requiredArgs)) { line.args.getOrNull(it) ?: spec.args.getOrNull(it)?.default.orEmpty() }
        args[i] = value
        replace(line.copy(args = args))
    }

    override fun draw() {
        clear()
        val line = line() ?: return back()
        val spec = EffectSpecs.of(line.effect)
        set(4, Icon.of(Material.BLAZE_POWDER, "<yellow>" + (spec?.label ?: line.effect), listOf("<gray>" + (spec?.description ?: ""), "", "<white>" + line.raw)))

        spec?.args?.take(7)?.forEachIndexed { i, arg ->
            val value = line.args.getOrNull(i)?.takeIf { it.isNotBlank() } ?: arg.default
            set(19 + i, Icon.of(Material.PAPER, "<yellow>" + arg.label + ": <white>" + value.ifEmpty { "(비어 있음)" }, listOf(
                "<gray>종류: " + arg.type.label + if (arg.optional) " · 선택" else "",
                "", "<yellow>▶ 클릭: 바꾸기",
            ))) { event -> editArg(event, i, arg, value) }
        }

        if (spec != null && spec.acts != EffectSpec.Acts.EVENT) {
            set(29, Icon.of(Material.TARGET, "<yellow>대상", line.targets.map { "<gray>· <white>" + it.serialize() }.ifEmpty { listOf("<gray>없음 = 발동한 쪽(@Self)") } +
                listOf("", "<yellow>▶ 좌클릭: 추가 · 우클릭: 모두 지우기"))) { event ->
                if (event.isRightClick) {
                    replace(line.copy(targets = emptyList()))
                    refresh()
                    return@set
                }
                pickTarget { target -> line()?.let { replace(it.copy(targets = it.targets + target)) } }
            }
        }
        set(30, Icon.of(Material.REPEATER, "<yellow>이 줄이 도는 발동 조건", line.pointers.map { "<gray>· <white>" + it.serialize() }.ifEmpty { listOf("<gray>제한 없음(인첸트의 모든 발동 조건)") } +
            listOf("", "<yellow>▶ 클릭: 고르기(여럿)"))) {
            PickMenu(
                e, viewer, "<dark_red>이 줄만의 발동 조건</dark_red>", Trigger.entries, multi = true,
                icon = { Icon.of(Material.REPEATER, "<yellow>" + it.label, "<dark_gray>" + it.name) },
                selected = { line()?.pointers?.filter { !it.negate }?.map { it.trigger }?.toSet().orEmpty() },
                back = { show() },
            ) { trigger ->
                val current = line() ?: return@PickMenu
                val has = current.pointers.any { it.trigger == trigger && !it.negate }
                replace(current.copy(pointers = if (has) current.pointers.filterNot { it.trigger == trigger } else current.pointers + EffectLine.Pointer(trigger, false)))
            }.show()
        }
        set(31, Icon.of(Material.RABBIT_FOOT, "<yellow>줄 확률: <white>" + (line.chance ?: "없음(항상)"), "<gray>수식·변수를 쓸 수 있습니다. 예: <white>%level% * 10", "", "<yellow>▶ 좌클릭: 적기 · 우클릭: 없애기")) { event ->
            if (event.isRightClick) {
                replace(line.copy(chance = null))
                refresh()
                return@set
            }
            Editors.promptText(e.prompts, viewer, "줄 확률(%)", emptyList(), reopen = { show() }) { input -> line()?.let { replace(it.copy(chance = input.trim())) } }
        }
        set(32, Icon.of(Material.COMPARATOR, "<yellow>줄 조건: <white>" + (line.condition?.raw ?: "없음"), "<gray>예: <white>%victim health% < 10 : %allow%", "", "<yellow>▶ 좌클릭: 적기 · 우클릭: 없애기")) { event ->
            if (event.isRightClick) {
                replace(line.copy(condition = null))
                refresh()
                return@set
            }
            Editors.promptText(e.prompts, viewer, "줄 조건", emptyList(), reopen = { show() }) { input ->
                val condition = Condition.parse(input) ?: return@promptText e.messages.send(viewer, "admin-bad-condition", e.ph().value(input))
                line()?.let { replace(it.copy(condition = condition)) }
            }
        }
        set(33, Icon.of(Material.COMPASS, "<yellow>위치: <white>" + (line.location ?: "없음"), "<gray>예: <white>~0|0|-8 <gray>(상대) · <white>%hit location%", "", "<yellow>▶ 좌클릭: 적기 · 우클릭: 없애기")) { event ->
            if (event.isRightClick) {
                replace(line.copy(location = null))
                refresh()
                return@set
            }
            Editors.promptText(e.prompts, viewer, "위치", emptyList(), reopen = { show() }) { input -> line()?.let { replace(it.copy(location = input.trim())) } }
        }
        set(40, Icon.of(Material.WRITABLE_BOOK, "<yellow>원문으로 고치기", "<gray>AE 문법 한 줄 전체를 다시 적습니다.")) {
            Editors.promptText(e.prompts, viewer, "효과 한 줄", listOf("<gray>지금: <white>" + line.raw), reopen = { show() }) { input ->
                val parsed = EffectLine.parse(input.trim(), EffectSpecs::shape)
                val next = parsed.line ?: return@promptText e.messages.send(viewer, "admin-bad-line", e.ph().value(parsed.error ?: input))
                val all = lines.get().toMutableList()
                if (index in all.indices) {
                    all[index] = next
                    lines.set(all)
                }
            }
        }
        set(47, Icon.of(Material.ARROW, "<yellow>위로")) { move(-1) }
        set(48, Icon.of(Material.ARROW, "<yellow>아래로")) { move(1) }
        set(51, Icon.of(Material.LAVA_BUCKET, "<red>이 줄 지우기")) {
            lines.set(lines.get().filterIndexed { i, _ -> i != index })
            back()
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }

    private fun move(delta: Int) {
        val all = lines.get().toMutableList()
        val to = index + delta
        if (index !in all.indices || to !in all.indices) return
        val moved = all.removeAt(index)
        all.add(to, moved)
        lines.set(all)
        EffectLineMenu(e, viewer, ref, to).show()
    }

    private fun editArg(event: InventoryClickEvent, i: Int, arg: ArgSpec, value: String) {
        when (arg.type) {
            ArgType.NUMBER, ArgType.INT -> {
                val current = value.toDoubleOrNull()
                if (!Editors.isPrompt(event) && current != null) {
                    val step = if (arg.type == ArgType.INT) Editors.step(event, 1).toDouble() else Editors.step(event, 0.5)
                    val next = current + step
                    setArg(i, if (arg.type == ArgType.INT) next.toInt().toString() else kr.inmc.core.util.Numbers.chance(next))
                    refresh()
                    return
                }
                Editors.promptText(e.prompts, viewer, arg.label, listOf("<gray>숫자·수식·변수 모두 됩니다. 예: <white>%level% * 2"), reopen = { show() }) { setArg(i, it.trim()) }
            }
            ArgType.BOOL -> {
                setArg(i, (!value.equals("true", ignoreCase = true)).toString())
                refresh()
            }
            ArgType.CHOICE -> {
                setArg(i, Editors.cycle(event, arg.choices, arg.choices.firstOrNull { it.equals(value, ignoreCase = true) } ?: arg.choices.first()))
                refresh()
            }
            ArgType.POTION -> choose(arg.label, Names.POTIONS.map { it.uppercase() }, Material.POTION) { setArg(i, it) }
            ArgType.PARTICLE -> choose(arg.label, Particle.entries.map { it.name }, Material.FIREWORK_STAR) { setArg(i, it) }
            ArgType.COLOR -> choose(arg.label, Names.COLORS, Material.RED_DYE) { setArg(i, it) }
            ArgType.ENCHANT -> choose(arg.label, e.registry.ids(), Material.ENCHANTED_BOOK) { setArg(i, it) }
            ArgType.MATERIAL, ArgType.ITEM -> {
                val held = viewer.inventory.itemInMainHand
                if (event.isShiftClick && !held.type.isAir) {
                    setArg(i, held.type.name)
                    refresh()
                    return
                }
                Editors.promptText(e.prompts, viewer, arg.label, listOf("<gray>재질 이름(예: <white>DIAMOND</white>) 또는 <white>inmc:아이템</white>", "<gray>화면에서 Shift 클릭하면 손에 든 것의 재질"), reopen = { show() }) { setArg(i, it.trim()) }
            }
            ArgType.SOUND, ArgType.ENTITY_TYPE, ArgType.TEXT, ArgType.WORD ->
                Editors.promptText(e.prompts, viewer, arg.label, listOf("<gray>지금: <white>$value"), reopen = { show() }) { setArg(i, it.trim()) }
        }
    }

    private fun choose(label: String, options: List<String>, material: Material, apply: (String) -> Unit) {
        PickMenu(e, viewer, "<dark_red>$label</dark_red>", options, icon = { Icon.of(material, "<yellow>$it") }, back = { show() }) {
            apply(it)
            show()
        }.show()
    }

    /** 대상 고르기 → 옵션이 있는 대상이면 채팅으로 옵션(`radius=5,target=MOBS`). */
    private fun pickTarget(apply: (TargetSpec) -> Unit) {
        PickMenu(e, viewer, "<dark_red>대상</dark_red>", TargetKind.entries,
            icon = { Icon.of(Material.TARGET, "<yellow>" + it.label, "<gray>@" + it.token, "<gray>옵션: <white>" + it.optionKeys.joinToString(", ").ifEmpty { "없음" }) },
            back = { show() },
        ) { kind ->
            if (kind.optionKeys.isEmpty()) {
                apply(TargetSpec(kind))
                show()
                return@PickMenu
            }
            Editors.promptText(e.prompts, viewer, "@" + kind.token + " 옵션", listOf(
                "<gray>모양: <white>" + kind.optionKeys.joinToString(",") { "$it=값" },
                "<gray>비우려면 <white>-", "<gray>대상 종류(target): <white>" + Names.AOE_TARGETS.joinToString(", "),
            ), reopen = { show() }) { input ->
                val options = if (input.trim() == "-") emptyMap() else input.split(',').mapNotNull { pair ->
                    val key = pair.substringBefore('=', "").trim().lowercase()
                    val value = pair.substringAfter('=', "").trim()
                    if (key.isEmpty() || value.isEmpty()) null else kind.canonical(key) to value
                }.toMap()
                apply(TargetSpec(kind, options))
            }
        }.show()
    }
}
