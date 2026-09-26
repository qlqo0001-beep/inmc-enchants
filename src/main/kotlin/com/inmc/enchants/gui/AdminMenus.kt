package com.inmc.enchants.gui

import com.inmc.enchants.Enchants
import com.inmc.enchants.EnchantsPlugin
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.enchant.EnchantLevel
import com.inmc.enchants.enchant.Group
import com.inmc.enchants.enchant.percentRange
import com.inmc.enchants.engine.Condition
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.verify.Verifier
import kr.inmc.core.gui.ConfirmMenu
import kr.inmc.core.gui.Editors
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Numbers
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryClickEvent

/** 관리 화면의 입구. */
class AdminMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<dark_red>인첸트 관리</dark_red>") {

    override val back: (() -> Unit) = { MainMenu(e, viewer).show() }

    override fun draw() {
        clear()
        set(19, Icon.of(Material.ENCHANTED_BOOK, "<yellow>인첸트", "<gray>" + e.registry.size + "개 · 만들기·고치기·지우기", "", "<yellow>▶ 클릭")) {
            CatalogMenu(e, viewer, back = { AdminMenu(e, viewer).show() }, admin = true).show()
        }
        set(20, Icon.of(Material.NAME_TAG, "<yellow>등급", "<gray>색·순서·가루·확장기·인챈터 가격", "", "<yellow>▶ 클릭")) {
            GroupListMenu(e, viewer).show()
        }
        set(21, Icon.of(Material.CHEST, "<yellow>아이템 지급", "<gray>부여서·가루·스크롤·오브·추적기·영혼석", "", "<yellow>▶ 클릭")) {
            GiveMenu(e, viewer).show()
        }
        set(22, Icon.of(Material.COMPARATOR, "<yellow>설정", "<gray>칸·부여서·합치기·출처 확률 등", "", "<yellow>▶ 클릭")) {
            SettingsMenu(e, viewer).show()
        }
        set(23, Icon.of(Material.OBSERVER, "<yellow>검증", "<gray>효과·인첸트·발동 조건·아이템을", "<gray>서버 안에서 실제로 돌려 확인합니다.", "<gray>빈 평지에서 누르세요.", "", "<yellow>▶ 클릭")) {
            PickMenu(
                e, viewer, "<dark_red>검증 방식</dark_red>", Verifier.Mode.entries,
                icon = { Icon.of(Material.OBSERVER, "<yellow>" + it.label) },
                back = { AdminMenu(e, viewer).show() },
            ) { mode ->
                viewer.closeInventory()
                e.verifier.start(viewer, mode, null)
            }.show()
        }
        set(24, Icon.of(Material.CLOCK, "<yellow>다시 불러오기", "<gray>파일을 직접 고쳤을 때", "", "<yellow>▶ 클릭")) {
            viewer.closeInventory()
            (e.plugin as EnchantsPlugin).reload { e.messages.send(viewer, "reloaded", e.ph().count(e.registry.size)) }
        }
        // 세트는 커스텀아이템으로 옮겼다(사용자 결정 2026-09-25). 찾는 관리자에게 어디로 갔는지만 알린다.
        set(25, Icon.of(Material.DIAMOND_CHESTPLATE, "<gray>세트·무기", "<gray>커스텀아이템으로 옮겼습니다.",
            "<gray>/커스텀아이템 관리 → 아이템 세트 → 몇 벌 효과", "<gray>→ <light_purple>인첸트 효과</light_purple> 에서 고칩니다."))
        if (e.registry.problems.isNotEmpty()) {
            set(31, Icon.of(Material.RED_DYE, "<red>설정 문제 " + e.registry.problems.size + "건", e.registry.problems.take(10).map { "<gray>$it" }))
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 새 인첸트: id 를 묻고 빈 정의를 만든 뒤 편집 화면을 연다. */
internal fun createEnchant(e: Enchants, viewer: Player) {
    e.prompts.request(viewer, listOf("<yellow>새 인첸트 id 를 적으세요.", "<gray>소문자 영문·숫자·밑줄. 예: <white>frostbite")) { input ->
        val id = input.trim().lowercase()
        if (!EnchantDefinition.ID.matches(id)) {
            e.messages.send(viewer, "admin-bad-id", e.ph().value(id))
            return@request
        }
        if (e.registry.get(id) != null) {
            e.messages.send(viewer, "admin-id-taken", e.ph().value(id))
            return@request
        }
        e.registry.put(
            EnchantDefinition(
                id = id, display = "%group-color%$id", description = listOf("설명을 적어 주세요."), appliesTo = "검",
                triggers = listOf(Trigger.ATTACK_MOB), group = e.groups.all().firstOrNull()?.id ?: "SIMPLE", applies = listOf("ALL_SWORD"),
                levels = mapOf(1 to EnchantLevel(effects = emptyList())),
            ),
        )
        EnchantEditMenu(e, viewer, id).show()
    }
}

/**
 * 인첸트 하나의 편집. **정의가 아니라 id 를 들고 있다** — 정의는 불변이라 한 칸을 고칠 때마다
 * 새 객체가 되는데, 옛 객체를 붙들고 있으면 두 번째 편집이 첫 번째를 지운다.
 */
class EnchantEditMenu(e: Enchants, viewer: Player, private val id: String) :
    Menu(e, viewer, 54, "<dark_red>인첸트 편집 - $id</dark_red>") {

    override val back: (() -> Unit) = { CatalogMenu(e, viewer, back = { AdminMenu(e, viewer).show() }, admin = true).show() }

    private fun def(): EnchantDefinition? = e.registry.get(id)

    private fun mutate(change: (EnchantDefinition) -> EnchantDefinition) {
        val current = def() ?: return
        e.registry.put(change(current))
    }

    private fun ask(label: String, hints: List<String>, apply: (String, EnchantDefinition) -> EnchantDefinition) {
        Editors.promptText(e.prompts, viewer, label, hints, reopen = { show() }) { input -> mutate { apply(input, it) } }
    }

    override fun draw() {
        clear()
        val def = def() ?: return viewer.closeInventory()
        set(4, enchantIcon(e, def))

        set(19, Icon.of(Material.NAME_TAG, "<yellow>이름", listOf("<gray>현재: " + e.lore.name(def), "<gray>%group-color% 는 등급 색이 됩니다.", "", "<yellow>▶ 클릭"))) {
            ask("표시 이름", listOf("<gray>예: <white>%group-color%서리 칼날")) { input, d -> d.copy(display = input) }
        }
        set(20, Icon.of(Material.WRITABLE_BOOK, "<yellow>설명", def.description.map { "<gray>$it" } + listOf("", "<gray>줄은 <white>\\n</white> 으로 나눕니다.", "<yellow>▶ 클릭"))) {
            ask("설명", listOf("<gray>예: <white>공격할 때 확률로 얼린다.\\n레벨이 오를수록 오래.")) { input, d ->
                d.copy(description = input.replace("\\n", "\n").split('\n').map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
        set(21, Icon.of(Material.IRON_SWORD, "<yellow>붙는 곳", def.applies.map { "<gray>· <white>$it" } + listOf("", "<gray>설명: <white>" + def.appliesTo, "<yellow>▶ 좌클릭: 고르기 · 우클릭: 설명 글 고치기"))) { event ->
            if (event.isRightClick) {
                ask("붙는 곳 설명(화면에만 쓰입니다)", listOf("<gray>예: <white>검·도끼")) { input, d -> d.copy(appliesTo = input) }
                return@set
            }
            PickMenu(
                e, viewer, "<dark_red>붙는 곳</dark_red>", Applicability.PRESETS, multi = true,
                icon = { Icon.of(Material.IRON_INGOT, "<yellow>" + it.second, "<gray>" + it.first) },
                selected = { Applicability.PRESETS.filter { p -> def()?.applies?.contains(p.first) == true }.toSet() },
                back = { show() },
            ) { (pattern, _) ->
                mutate { d -> d.copy(applies = if (pattern in d.applies) d.applies - pattern else d.applies + pattern) }
            }.show()
        }
        set(22, Icon.of(Material.REPEATER, "<yellow>발동 조건", def.triggers.map { "<gray>· <white>" + it.label } + listOf("", "<yellow>▶ 클릭: 고르기(여럿)"))) {
            PickMenu(
                e, viewer, "<dark_red>발동 조건</dark_red>", Trigger.entries, multi = true,
                icon = { Icon.of(Material.REPEATER, "<yellow>" + it.label, "<gray>" + it.description, "<dark_gray>" + it.name) },
                selected = { def()?.triggers?.toSet().orEmpty() },
                back = { show() },
            ) { trigger ->
                mutate { d -> d.copy(triggers = if (trigger in d.triggers) d.triggers - trigger else d.triggers + trigger) }
            }.show()
        }
        val groups = e.groups.all()
        set(23, Icon.of(Material.AMETHYST_SHARD, "<yellow>등급: " + e.groups.get(def.group).color + e.groups.get(def.group).name,
            Editors.optionList(groups.map { it.id }, def.group.uppercase()) { g -> e.groups.get(g).name } + Editors.cycleHint)) { event ->
            mutate { d -> d.copy(group = Editors.cycle(event, groups.map { it.id }, d.group.uppercase())) }
            refresh()
        }
        set(24, Icon.of(Material.LEAD, "<yellow>규칙", listOf(
            "<gray>필요: <white>" + def.settings.requiredEnchants.ifEmpty { listOf("없음") }.joinToString(", "),
            "<gray>함께 못 붙음: <white>" + def.settings.notApplyableWith.ifEmpty { listOf("없음") }.joinToString(", "),
            "<gray>붙으면 지움: <white>" + def.settings.removedEnchants.ifEmpty { listOf("없음") }.joinToString(", "),
            "<gray>블랙 스크롤로 떼기: " + Icon.toggle(def.settings.removeable),
            "<gray>인챈터에서 제외: " + Icon.toggle(def.settings.disableInEnchanter),
            "<gray>발동 액션바: " + Icon.toggle(def.settings.showActionBar),
            "<gray>커스텀아이템에만: " + Icon.toggle(def.settings.customItemsOnly),
            "<gray>뽑기 최대 레벨: <white>" + (def.settings.drawMaxLevel.takeIf { it > 0 }?.toString() ?: "제한 없음"),
            "<gray>뽑힌 부여서 성공률: <white>" + (def.settings.bookSuccess?.let { "${it.first}~${it.last}%" } ?: "기본"),
            "<gray>꺼진 월드: <white>" + def.settings.disabledWorlds.ifEmpty { listOf("없음") }.joinToString(", "),
            "", "<yellow>▶ 클릭",
        ))) { RuleMenu(e, viewer, id).show() }
        set(25, Icon.of(Material.EXPERIENCE_BOTTLE, "<yellow>레벨 " + def.levels.size + "개", listOf("<gray>확률·재사용 대기·효과·조건·연동 값", "", "<yellow>▶ 클릭"))) {
            LevelListMenu(e, viewer, id).show()
        }
        set(40, Icon.of(Material.BOOK, "<green>부여서 받기(최대 레벨, 100%)")) {
            for (left in viewer.inventory.addItem(e.items.book(def, def.maxLevel, 100, 0)).values) viewer.world.dropItem(viewer.location, left)
        }
        set(41, Icon.of(Material.OBSERVER, "<yellow>이 인첸트만 검증", "<gray>모든 레벨의 모든 줄을 실제로 돌립니다.")) {
            viewer.closeInventory()
            e.verifier.start(viewer, Verifier.Mode.ENCHANTS, id)
        }
        set(51, Icon.of(Material.LAVA_BUCKET, "<red>지우기", "<gray>되돌릴 수 없습니다.", "<gray>이미 붙은 아이템에서는 효과가 사라집니다.")) {
            ConfirmMenu(e, "<red>" + id + " 을(를) 지울까요?", onConfirm = {
                e.registry.remove(id)
                back()
            }, onCancel = { show() }).open(viewer)
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 인첸트 전체 규칙(AE `settings`). */
class RuleMenu(e: Enchants, viewer: Player, private val id: String) : Menu(e, viewer, 54, "<dark_red>규칙 - $id</dark_red>") {

    override val back: (() -> Unit) = { EnchantEditMenu(e, viewer, id).show() }

    private fun mutate(change: (EnchantDefinition) -> EnchantDefinition) {
        val current = e.registry.get(id) ?: return
        e.registry.put(change(current))
    }

    private fun listPrompt(label: String, hint: String, apply: (List<String>, EnchantDefinition) -> EnchantDefinition) {
        Editors.promptText(e.prompts, viewer, label, listOf("<gray>쉼표로 나눕니다. 비우려면 <white>없음</white>", "<gray>$hint"), reopen = { show() }) { input ->
            val list = if (input.trim() == "없음") emptyList() else input.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            mutate { apply(list, it) }
        }
    }

    override fun draw() {
        clear()
        val s = e.registry.get(id)?.settings ?: return viewer.closeInventory()
        set(19, Icon.of(Material.LEAD, "<yellow>필요한 인첸트", s.requiredEnchants.map { "<gray>· $it" } + listOf("", "<yellow>▶ 클릭"))) {
            listPrompt("필요한 인첸트", "예: lifesteal:5, bleed") { list, d -> d.copy(settings = d.settings.copy(requiredEnchants = list)) }
        }
        set(20, Icon.of(Material.BARRIER, "<yellow>함께 못 붙는 인첸트", s.notApplyableWith.map { "<gray>· $it" } + listOf("", "<yellow>▶ 클릭"))) {
            listPrompt("함께 못 붙는 인첸트", "예: reelmaster, steelline") { list, d -> d.copy(settings = d.settings.copy(notApplyableWith = list)) }
        }
        set(21, Icon.of(Material.SHEARS, "<yellow>붙으면 지울 인첸트", s.removedEnchants.map { "<gray>· $it" } + listOf("", "<yellow>▶ 클릭"))) {
            listPrompt("붙으면 지울 인첸트", "상위 인첸트가 하위를 덮어쓸 때") { list, d -> d.copy(settings = d.settings.copy(removedEnchants = list)) }
        }
        set(23, Icon.of(Icon.toggleMaterial(s.removeable), "<yellow>블랙 스크롤로 뗄 수 있음: " + Icon.toggle(s.removeable), "<gray>끄면 저주처럼 붙어서 안 떨어집니다.")) {
            mutate { d -> d.copy(settings = d.settings.copy(removeable = !d.settings.removeable)) }
            refresh()
        }
        set(24, Icon.of(Icon.toggleMaterial(s.disableInEnchanter), "<yellow>인챈터·부여대·드랍에서 제외: " + Icon.toggle(s.disableInEnchanter))) {
            mutate { d -> d.copy(settings = d.settings.copy(disableInEnchanter = !d.settings.disableInEnchanter)) }
            refresh()
        }
        set(25, Icon.of(Icon.toggleMaterial(s.showActionBar), "<yellow>발동할 때 액션바: " + Icon.toggle(s.showActionBar))) {
            mutate { d -> d.copy(settings = d.settings.copy(showActionBar = !d.settings.showActionBar)) }
            refresh()
        }
        set(31, Icon.of(Material.GRASS_BLOCK, "<yellow>꺼진 월드", s.disabledWorlds.map { "<gray>· $it" } + listOf("", "<yellow>▶ 클릭"))) {
            Editors.promptText(e.prompts, viewer, "꺼진 월드", listOf("<gray>쉼표로 나눕니다. 대소문자 구분. 비우려면 <white>없음"), reopen = { show() }) { input ->
                val list = if (input.trim() == "없음") emptyList() else input.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                mutate { d -> d.copy(settings = d.settings.copy(disabledWorlds = list)) }
            }
        }
        set(32, Icon.of(Icon.toggleMaterial(s.customItemsOnly), "<yellow>커스텀아이템에만 붙음: " + Icon.toggle(s.customItemsOnly),
            "<gray>켜면 같은 재질의 평범한 아이템에는 부여서가 안 붙습니다.", "<gray>상위 도구 전용 인첸트(폭파)에 씁니다.")) {
            mutate { d -> d.copy(settings = d.settings.copy(customItemsOnly = !d.settings.customItemsOnly)) }
            refresh()
        }
        set(33, Editors.intIcon(Material.ENCHANTING_TABLE, "<yellow>뽑기 최대 레벨", s.drawMaxLevel,
            extra = listOf("<gray>인챈터·상자·사서·부여대에서 나오는 가장 높은 레벨.", "<gray>0 = 제한 없음. 그 위는 연금술사에서 합쳐 올립니다."))) { ev ->
            if (Editors.isPrompt(ev)) {
                Editors.promptInt(e.prompts, viewer, "뽑기 최대 레벨", 0, 100, reopen = { show() }) { v ->
                    mutate { d -> d.copy(settings = d.settings.copy(drawMaxLevel = v)) }
                }
                return@set
            }
            mutate { d -> d.copy(settings = d.settings.copy(drawMaxLevel = (d.settings.drawMaxLevel + Editors.step(ev, 1)).coerceIn(0, 100))) }
            refresh()
        }
        set(34, Icon.of(Material.PAPER, "<yellow>뽑힌 부여서 성공률: <white>" + (s.bookSuccess?.let { "${it.first}~${it.last}%" } ?: "기본(설정)"), listOf(
            "<gray>뽑기로 나온 이 인첸트 부여서의 성공률 범위.", "<gray>비우면 설정의 기본 범위.", "", "<yellow>▶ 클릭",
        ))) {
            Editors.promptText(e.prompts, viewer, "뽑힌 부여서 성공률", listOf("<gray>예: <white>5-15</white> 또는 <white>10</white>. 기본으로 돌리려면 <white>없음"), reopen = { show() }) { input ->
                if (input.trim() == "없음") {
                    mutate { d -> d.copy(settings = d.settings.copy(bookSuccess = null)) }
                    return@promptText
                }
                val range = percentRange(input)
                if (range == null) {
                    e.messages.send(viewer, "admin-bad-range", e.ph().value(input))
                    return@promptText
                }
                mutate { d -> d.copy(settings = d.settings.copy(bookSuccess = range)) }
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 레벨 목록. 추가는 마지막 레벨을 복사한다. */
class LevelListMenu(e: Enchants, viewer: Player, private val id: String) : Menu(e, viewer, 54, "<dark_red>레벨 - $id</dark_red>") {

    override val back: (() -> Unit) = { EnchantEditMenu(e, viewer, id).show() }

    override fun draw() {
        clear()
        val def = e.registry.get(id) ?: return viewer.closeInventory()
        for ((index, entry) in def.levels.entries.sortedBy { it.key }.take(36).withIndex()) {
            val (number, level) = entry
            set(index, Icon.of(Material.EXPERIENCE_BOTTLE, "<yellow>" + number + "레벨", listOf(
                "<gray>확률 <white>" + Numbers.chance(level.chance) + "%<gray> · 대기 <white>" + Numbers.chance(level.cooldown) + "초",
                "<gray>효과 <white>" + level.effects.size + "<gray>줄 · 조건 <white>" + level.conditions.size + "<gray>개",
                "", "<yellow>▶ 클릭: 편집",
            ))) { LevelEditMenu(e, viewer, EnchantLevelRef(e, id, number)).show() }
        }
        set(48, Icon.of(Material.LIME_DYE, "<green>레벨 추가", "<gray>마지막 레벨을 복사합니다.")) {
            val d = e.registry.get(id) ?: return@set
            val last = d.levels.maxByOrNull { it.key } ?: return@set
            e.registry.put(d.copy(levels = d.levels + ((last.key + 1) to last.value)))
            refresh()
        }
        set(50, Icon.of(Material.RED_DYE, "<red>마지막 레벨 지우기", "<gray>레벨이 하나면 지울 수 없습니다.")) {
            val d = e.registry.get(id) ?: return@set
            if (d.levels.size <= 1) return@set
            e.registry.put(d.copy(levels = d.levels - d.levels.keys.max()))
            refresh()
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/**
 * 레벨 하나. 확률·대기·영혼·주기·명령어·조건·효과·재질 목록, 인첸트면 레벨 설명·연동 값까지.
 * 세트·무기의 발동 조건도 이 화면으로 고친다([LevelRef]).
 */
class LevelEditMenu(e: Enchants, viewer: Player, private val ref: LevelRef) :
    Menu(e, viewer, 54, "<dark_red>${ref.title}</dark_red>") {

    override val back: (() -> Unit) = { ref.back(viewer) }

    private fun level(): EnchantLevel? = ref.get()

    private fun mutate(change: (EnchantLevel) -> EnchantLevel) {
        ref.get()?.let { ref.set(change(it)) }
    }

    private fun nudge(event: InventoryClickEvent, label: String, value: Double, min: Double, max: Double, step: Double, apply: (Double, EnchantLevel) -> EnchantLevel) {
        if (Editors.isPrompt(event)) {
            Editors.promptDouble(e.prompts, viewer, label, min, max, reopen = { show() }) { v -> mutate { apply(v, it) } }
            return
        }
        mutate { apply((value + Editors.step(event, step)).coerceIn(min, max), it) }
        refresh()
    }

    override fun draw() {
        clear()
        val level = level() ?: return viewer.closeInventory()
        set(10, Editors.numberIcon(Material.RABBIT_FOOT, "<yellow>확률", level.chance, unit = "%")) { ev ->
            nudge(ev, "확률", level.chance, 0.0, 100.0, 1.0) { v, l -> l.copy(chance = v) }
        }
        set(11, Editors.numberIcon(Material.CLOCK, "<yellow>재사용 대기", level.cooldown, unit = "초")) { ev ->
            nudge(ev, "재사용 대기(초)", level.cooldown, 0.0, 3600.0, 0.5) { v, l -> l.copy(cooldown = v) }
        }
        set(12, Editors.intIcon(Material.SOUL_LANTERN, "<yellow>영혼 비용", level.souls)) { ev ->
            nudge(ev, "영혼 비용", level.souls.toDouble(), 0.0, 10_000.0, 1.0) { v, l -> l.copy(souls = v.toInt()) }
        }
        set(13, Editors.intIcon(Material.REPEATER, "<yellow>주기(반복 발동)", level.time, unit = "초", extra = listOf("<gray>REPEATING 발동 조건에서만 씁니다."))) { ev ->
            nudge(ev, "주기(초)", level.time.toDouble(), 0.0, 3600.0, 1.0) { v, l -> l.copy(time = v.toInt()) }
        }
        set(14, Icon.of(Material.COMMAND_BLOCK, "<yellow>명령어", listOf("<gray>현재: <white>" + (level.command?.let { "/$it" } ?: "없음"),
            "<gray>COMMAND 발동 조건에서만 씁니다.", "", "<yellow>▶ 좌클릭: 적기 · 우클릭: 비우기"))) { ev ->
            if (ev.isRightClick) {
                mutate { it.copy(command = null) }
                refresh()
                return@set
            }
            Editors.promptText(e.prompts, viewer, "기다릴 명령어(/ 없이)", listOf("<gray>예: <white>도약"), reopen = { show() }) { input ->
                mutate { it.copy(command = input.trim().removePrefix("/")) }
            }
        }
        if (ref.enchant) set(16, Icon.of(Material.WRITABLE_BOOK, "<yellow>레벨 설명", level.description.map { "<gray>$it" } + listOf("", "<gray>비우면 인첸트 설명을 씁니다.", "<yellow>▶ 좌클릭: 적기 · 우클릭: 비우기"))) { ev ->
            if (ev.isRightClick) {
                mutate { it.copy(description = emptyList()) }
                refresh()
                return@set
            }
            Editors.promptText(e.prompts, viewer, "레벨 설명", listOf("<gray>줄은 <white>\\n</white> 으로 나눕니다."), reopen = { show() }) { input ->
                mutate { it.copy(description = input.replace("\\n", "\n").split('\n').map(String::trim).filter(String::isNotEmpty)) }
            }
        }
        set(28, Icon.of(Material.BLAZE_POWDER, "<yellow>효과 " + level.effects.size + "줄", level.effects.take(8).map { "<gray>· <white>" + it.raw } + listOf("", "<yellow>▶ 클릭"))) {
            EffectListMenu(e, viewer, ref).show()
        }
        set(29, Icon.of(Material.COMPARATOR, "<yellow>조건 " + level.conditions.size + "개", level.conditions.map { "<gray>· <white>" + it.raw } + listOf("", "<yellow>▶ 클릭"))) {
            ConditionListMenu(e, viewer, ref).show()
        }
        if (ref.enchant) set(30, Icon.of(Material.HOPPER, "<yellow>연동 값 " + level.data.size + "개", level.data.map { (k, v) -> "<gray>· <white>$k <gray>= <yellow>$v" } +
            listOf("<gray>다른 플러그인이 읽는 값(fishing.* 등).", "", "<yellow>▶ 좌클릭: 추가·바꾸기(열쇠 값) · 우클릭: 전부 비우기"))) { ev ->
            if (ev.isRightClick) {
                mutate { it.copy(data = emptyMap()) }
                refresh()
                return@set
            }
            Editors.promptText(e.prompts, viewer, "연동 값", listOf("<gray><white>열쇠 값</white> 모양. 값을 <white>-</white> 로 적으면 지웁니다.", "<gray>예: <white>fishing.reel-power 5"), reopen = { show() }) { input ->
                val key = input.substringBefore(' ').trim()
                val value = input.substringAfter(' ', "").trim()
                if (key.isEmpty()) return@promptText
                mutate { l -> l.copy(data = if (value == "-" || value.isEmpty()) l.data - key else l.data + (key to value)) }
            }
        }
        set(32, Icon.of(Material.STONE, "<yellow>재질 허용 목록", level.settings.whitelist.take(10).map { "<gray>· $it" } +
            listOf("<gray>채굴·드랍 효과가 이 재질에서만 돕니다.", "", "<yellow>▶ 좌클릭: 적기 · 우클릭: 비우기"))) { ev ->
            if (ev.isRightClick) {
                mutate { it.copy(settings = it.settings.copy(whitelist = emptyList())) }
                refresh()
                return@set
            }
            Editors.promptText(e.prompts, viewer, "허용 재질", listOf("<gray>쉼표로 나눕니다. 예: <white>IRON_ORE, GOLD_ORE"), reopen = { show() }) { input ->
                mutate { it.copy(settings = it.settings.copy(whitelist = input.split(',').map { s -> s.trim().uppercase() }.filter(String::isNotEmpty))) }
            }
        }
        set(33, Icon.of(Material.COBBLESTONE, "<yellow>재질 금지 목록", level.settings.blacklist.take(10).map { "<gray>· $it" } + listOf("", "<yellow>▶ 좌클릭: 적기 · 우클릭: 비우기"))) { ev ->
            if (ev.isRightClick) {
                mutate { it.copy(settings = it.settings.copy(blacklist = emptyList())) }
                refresh()
                return@set
            }
            Editors.promptText(e.prompts, viewer, "금지 재질", listOf("<gray>쉼표로 나눕니다."), reopen = { show() }) { input ->
                mutate { it.copy(settings = it.settings.copy(blacklist = input.split(',').map { s -> s.trim().uppercase() }.filter(String::isNotEmpty))) }
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 조건 목록. 추가는 채팅으로 한 줄(문법 확인), 지우기는 클릭. */
class ConditionListMenu(e: Enchants, viewer: Player, private val ref: LevelRef) :
    Menu(e, viewer, 54, "<dark_red>${ref.title} 조건</dark_red>") {

    override val back: (() -> Unit) = { LevelEditMenu(e, viewer, ref).show() }

    private fun mutate(change: (List<Condition>) -> List<Condition>) {
        ref.get()?.let { ref.set(it.copy(conditions = change(it.conditions))) }
    }

    override fun draw() {
        clear()
        val conditions = ref.get()?.conditions ?: return viewer.closeInventory()
        for ((index, condition) in conditions.take(36).withIndex()) {
            set(index, Icon.of(Material.COMPARATOR, "<white>" + condition.raw, "<gray>결과: <white>" + condition.outcome, "", "<red>▶ 우클릭: 지우기")) { ev ->
                if (!ev.isRightClick) return@set
                mutate { it.filterIndexed { i, _ -> i != index } }
                refresh()
            }
        }
        set(49, Icon.of(Material.LIME_DYE, "<green>조건 추가", listOf(
            "<gray>모양: <white>식 : 결과",
            "<gray>결과: <white>%allow% %continue% %stop% %force% %chance%+10",
            "<gray>예: <white>%victim health percentage% <= 30 : %allow%",
            "<gray>변수는 도감의 인첸트 설명·README 참고",
        ))) {
            Editors.promptText(e.prompts, viewer, "조건 한 줄", emptyList(), reopen = { show() }) { input ->
                val parsed = Condition.parse(input)
                if (parsed == null) {
                    e.messages.send(viewer, "admin-bad-condition", e.ph().value(input))
                    return@promptText
                }
                mutate { it + parsed }
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

/** 등급 목록 → 등급 편집. */
class GroupListMenu(e: Enchants, viewer: Player) : Menu(e, viewer, 54, "<dark_red>등급</dark_red>") {

    override val back: (() -> Unit) = { AdminMenu(e, viewer).show() }

    override fun draw() {
        clear()
        for ((index, group) in e.groups.all().take(36).withIndex()) {
            val count = e.registry.all().count { it.group.equals(group.id, ignoreCase = true) }
            set(index, Icon.of(Material.NAME_TAG, group.color + group.name, listOf("<gray>" + group.id + " · 순서 " + group.order, "<gray>인첸트 <white>$count<gray>개", "", "<yellow>▶ 클릭: 편집"))) {
                GroupEditMenu(e, viewer, group.id).show()
            }
        }
        set(49, Icon.of(Material.LIME_DYE, "<green>새 등급", "<gray>id 를 적으면 만들어집니다.")) {
            Editors.promptText(e.prompts, viewer, "새 등급 id(영문 대문자)", emptyList(), reopen = { show() }) { input ->
                val id = input.trim().uppercase()
                if (id.isEmpty() || e.groups.find(id) != null) return@promptText
                e.groups.put(Group(id, id, "&7", (e.groups.all().maxOfOrNull { it.order } ?: 0) + 1))
            }
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}

class GroupEditMenu(e: Enchants, viewer: Player, private val id: String) : Menu(e, viewer, 54, "<dark_red>등급 - $id</dark_red>") {

    override val back: (() -> Unit) = { GroupListMenu(e, viewer).show() }

    private fun mutate(change: (Group) -> Group) {
        e.groups.find(id)?.let { e.groups.put(change(it)) }
    }

    private fun nudge(event: InventoryClickEvent, label: String, value: Int, min: Int, max: Int, apply: (Int, Group) -> Group) {
        if (Editors.isPrompt(event)) {
            Editors.promptInt(e.prompts, viewer, label, min, max, reopen = { show() }) { v -> mutate { apply(v, it) } }
            return
        }
        mutate { apply((value + Editors.step(event, 1)).coerceIn(min, max), it) }
        refresh()
    }

    override fun draw() {
        clear()
        val g = e.groups.find(id) ?: return viewer.closeInventory()
        set(10, Icon.of(Material.NAME_TAG, "<yellow>이름: " + g.color + g.name, "", "<yellow>▶ 클릭")) {
            Editors.promptText(e.prompts, viewer, "등급 이름", emptyList(), reopen = { show() }) { input -> mutate { it.copy(name = input.trim()) } }
        }
        set(11, Icon.of(Material.RED_DYE, "<yellow>색: " + g.color + "견본", "<gray>& 색코드나 <white><#ff8800></white> 같은 MiniMessage", "", "<yellow>▶ 클릭")) {
            Editors.promptText(e.prompts, viewer, "등급 색", listOf("<gray>예: <white>&6 <gray>또는 <white><gradient:#ff0:#f80>"), reopen = { show() }) { input -> mutate { it.copy(color = input.trim()) } }
        }
        set(12, Editors.intIcon(Material.LADDER, "<yellow>순서(귀할수록 큼)", g.order)) { ev -> nudge(ev, "순서", g.order, 0, 1000) { v, x -> x.copy(order = v) } }
        set(13, Editors.intIcon(Material.PRISMARINE_CRYSTALS, "<yellow>확장기 칸", g.slotIncreaser)) { ev -> nudge(ev, "확장기 칸", g.slotIncreaser, 1, 20) { v, x -> x.copy(slotIncreaser = v) } }
        set(14, Editors.intIcon(Material.SUGAR, "<yellow>마법 가루 최소 %", g.dustMin)) { ev -> nudge(ev, "마법 가루 최소", g.dustMin, 0, 100) { v, x -> x.copy(dustMin = v) } }
        set(15, Editors.intIcon(Material.SUGAR, "<yellow>마법 가루 최대 %", g.dustMax)) { ev -> nudge(ev, "마법 가루 최대", g.dustMax, 0, 100) { v, x -> x.copy(dustMax = v) } }
        set(16, Editors.numberIcon(Material.FIREWORK_STAR, "<yellow>비밀 가루 → 마법 가루 확률", g.secretDustChance, unit = "%")) { ev ->
            if (Editors.isPrompt(ev)) {
                Editors.promptDouble(e.prompts, viewer, "확률", 0.0, 100.0, reopen = { show() }) { v -> mutate { it.copy(secretDustChance = v) } }
                return@set
            }
            mutate { it.copy(secretDustChance = (g.secretDustChance + Editors.step(ev, 1.0)).coerceIn(0.0, 100.0)) }
            refresh()
        }
        set(22, Icon.of(Material.EMERALD, "<yellow>인챈터 가격: <white>" + g.enchanterPrice.ifBlank { "무료" },
            "<gray>exp:400 · level:10 · money:500 · souls:20 · item:DIAMOND:5", "", "<yellow>▶ 클릭")) {
            Editors.promptText(e.prompts, viewer, "인챈터 가격", emptyList(), reopen = { show() }) { input -> mutate { it.copy(enchanterPrice = input.trim()) } }
        }
        set(23, Icon.of(g.bookMaterial?.let { Material.matchMaterial(it) } ?: Material.ENCHANTED_BOOK, "<yellow>부여서 재질", "<gray>손에 든 아이템의 재질로 바꿉니다.", "", "<yellow>▶ 클릭")) {
            val held = viewer.inventory.itemInMainHand
            if (held.type.isAir) return@set
            mutate { it.copy(bookMaterial = held.type.name) }
            refresh()
        }
        fillEmpty(Icon.FILLER)
        navigation()
    }
}
