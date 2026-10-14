package com.inmc.enchants.command

import com.inmc.enchants.Enchants
import com.inmc.enchants.EnchantsPlugin
import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.item.ItemUses
import com.inmc.enchants.item.ScrollEnchant
import com.inmc.enchants.verify.Verifier
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kr.inmc.core.integration.Vanish
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * `/인첸트` 한 트리. **모든 조작의 주 경로는 GUI 다** — 명령어는 GUI 로 가는 문과, 콘솔·자동화가
 * 필요로 하는 것(지급·리로드·검증)만 둔다.
 */
class EnchantsCommand(private val e: Enchants, private val plugin: EnchantsPlugin) {

    fun register() {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(tree().build(), "INMC 커스텀 인첸트", listOf("inmcenchant", "ie"))
        }
    }

    private val enchantIds = SuggestionProvider<CommandSourceStack> { _, builder ->
        e.registry.ids().filter { it.startsWith(builder.remainingLowerCase) }.forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    /**
     * `부여`·`금지` — 우리 인첸트와 바닐라(`minecraft:…`). 바닐라는 콜론 붙은 id 로만 받는다 — `protection`·`multishot`·`piercing` 은
     * 같은 이름의 커스텀 인첸트가 있다(지뢰 15). `sharp` 처럼 이름만 쳐도 `minecraft:sharpness` 를 보여 준다.
     */
    private val anyIds = SuggestionProvider<CommandSourceStack> { _, builder ->
        val typed = builder.remainingLowerCase
        (e.registry.ids() + e.scrolls.vanilla().map { ScrollEnchant.Vanilla(it).id })
            .filter { it.startsWith(typed) || it.substringAfter(':').startsWith(typed) }.forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    /** `해제` 는 손에 든 것에 붙은 것만 — 우리 인첸트와 바닐라. */
    private val heldIds = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val held = (ctx.source.sender as? Player)?.inventory?.itemInMainHand
        val typed = builder.remainingLowerCase
        (EnchantStorage.read(held).keys + held?.enchantments?.keys.orEmpty().map { ScrollEnchant.Vanilla(it).id })
            .filter { it.startsWith(typed) || it.substringAfter(':').startsWith(typed) }.forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    /** 대상 플레이어 — 투명한 사람은 볼 수 있는 사람에게만(core [Vanish]). */
    private val playerNames = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        Vanish.visibleNames(ctx.source.sender).filter { it.lowercase().startsWith(builder.remainingLowerCase) }.forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    private fun tree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("인첸트")
            .requires { it.sender.hasPermission(USE) }
            .executes { ctx -> open(ctx.source) { player -> com.inmc.enchants.gui.MainMenu(e, player).open(player) } }
            .then(
                Commands.literal("관리").requires { it.sender.hasPermission(ADMIN) }
                    .executes { ctx -> open(ctx.source) { player -> com.inmc.enchants.gui.AdminMenu(e, player).open(player) } },
            )
            .then(
                Commands.literal("정보").then(
                    Commands.argument("인첸트", StringArgumentType.word()).suggests(enchantIds).executes { ctx ->
                        val raw = StringArgumentType.getString(ctx, "인첸트")
                        if (e.registry.get(raw) == null) return@executes fail(ctx.source.sender, "unknown-enchant", raw)
                        open(ctx.source) { player -> com.inmc.enchants.gui.EnchantInfoMenu(e, player, raw.lowercase(), back = null).show() }
                    },
                ),
            )
            .then(Commands.literal("리로드").requires { it.sender.hasPermission(ADMIN) }.executes { ctx -> reload(ctx.source.sender) })
            .then(enchantTree())
            .then(disableTree())
            .then(verifyTree())
            .then(
                Commands.literal("해제").requires { it.sender.hasPermission(ENCHANT) }
                    .then(
                        Commands.argument("인첸트", ArgumentTypes.key()).suggests(heldIds)
                            .executes { ctx -> apply(ctx, 0, null, onlyValid = false) }
                            .then(
                                Commands.argument("플레이어", StringArgumentType.word()).suggests(playerNames)
                                    .executes { ctx -> apply(ctx, 0, StringArgumentType.getString(ctx, "플레이어"), onlyValid = false) },
                            ),
                    ),
            )

    /**
     * `/인첸트 부여 <인첸트> [레벨] [플레이어] [-onlyvalid]` — 손에 든 아이템에 바로(CMI `/cmi enchant` 대체, 2026-10-10).
     * 레벨 0 은 떼기. 인첸트 id 는 `ArgumentTypes.key()` 로 받는다 — Brigadier 의 단어 인자는 콜론을 못 읽는다. 글자는 입력 그대로 쓴다
     * ([raw]) — 열쇠로 읽으면 `lifesteal` 이 `minecraft:lifesteal` 이 된다.
     */
    private fun enchantTree(): LiteralArgumentBuilder<CommandSourceStack> {
        val level = { ctx: CommandContext<CommandSourceStack> -> IntegerArgumentType.getInteger(ctx, "레벨") }
        val named = { ctx: CommandContext<CommandSourceStack> -> StringArgumentType.getString(ctx, "플레이어") }
        return Commands.literal("부여").requires { it.sender.hasPermission(ENCHANT) }
            .then(
                Commands.argument("인첸트", ArgumentTypes.key()).suggests(anyIds)
                    .executes { ctx -> apply(ctx, 1, null, onlyValid = false) }
                    .then(
                        Commands.argument("레벨", IntegerArgumentType.integer(0, MAX_LEVEL))
                            .executes { ctx -> apply(ctx, level(ctx), null, onlyValid = false) }
                            .then(Commands.literal(ONLY_VALID).executes { ctx -> apply(ctx, level(ctx), null, onlyValid = true) })
                            .then(
                                Commands.argument("플레이어", StringArgumentType.word()).suggests(playerNames)
                                    .executes { ctx -> apply(ctx, level(ctx), named(ctx), onlyValid = false) }
                                    .then(Commands.literal(ONLY_VALID).executes { ctx -> apply(ctx, level(ctx), named(ctx), onlyValid = true) }),
                            ),
                    ),
            )
    }

    /** `/인첸트 금지 [인첸트]` — 이름 없이는 목록, 있으면 켜고 끈다(CMI `/cmi disableenchant` 대체). 화면은 관리 → 금지 인첸트. */
    private fun disableTree(): LiteralArgumentBuilder<CommandSourceStack> =
        Commands.literal("금지").requires { it.sender.hasPermission(ADMIN) }
            .executes { ctx -> listDisabled(ctx.source.sender) }
            .then(
                Commands.argument("인첸트", ArgumentTypes.key()).suggests(anyIds)
                    .executes { ctx -> toggleDisabled(ctx.source.sender, raw(ctx, "인첸트")) },
            )

    private val effectNames = SuggestionProvider<CommandSourceStack> { _, builder ->
        EffectSpecs.ALL.map { it.name }.filter { it.lowercase().startsWith(builder.remainingLowerCase) }.forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    private val triggerNames = SuggestionProvider<CommandSourceStack> { _, builder ->
        Trigger.entries.map { it.name }.filter { it.lowercase().startsWith(builder.remainingLowerCase) }.forEach { builder.suggest(it) }
        builder.buildFuture()
    }

    /** `/인첸트 검증 [효과|인첸트|발동조건|아이템|화면|전체] [이름]`. 이름을 빼면 그 방식 전부. */
    private fun verifyTree(): LiteralArgumentBuilder<CommandSourceStack> {
        fun mode(word: String, mode: Verifier.Mode, argument: String, suggestions: SuggestionProvider<CommandSourceStack>) =
            Commands.literal(word)
                .executes { ctx -> verify(ctx.source, mode, null) }
                .then(
                    Commands.argument(argument, StringArgumentType.word()).suggests(suggestions)
                        .executes { ctx -> verify(ctx.source, mode, StringArgumentType.getString(ctx, argument)) },
                )
        return Commands.literal("검증").requires { it.sender.hasPermission(ADMIN) }
            .executes { ctx -> verify(ctx.source, Verifier.Mode.ALL, null) }
            .then(mode("효과", Verifier.Mode.EFFECTS, "효과", effectNames))
            .then(mode("인첸트", Verifier.Mode.ENCHANTS, "인첸트", enchantIds))
            .then(mode("발동조건", Verifier.Mode.TRIGGERS, "발동조건", triggerNames))
            .then(Commands.literal("아이템").executes { ctx -> verify(ctx.source, Verifier.Mode.ITEMS, null) })
            .then(Commands.literal("화면").executes { ctx -> verify(ctx.source, Verifier.Mode.MENUS, null) })
            .then(Commands.literal("전체").executes { ctx -> verify(ctx.source, Verifier.Mode.ALL, null) })
    }

    /** `execute as <플레이어> run 인첸트 검증` 도 된다 — 콘솔·명령 블록에서 누군가를 세워 돌릴 때. */
    private fun verify(source: CommandSourceStack, mode: Verifier.Mode, only: String?): Int {
        val player = source.executor as? Player ?: source.sender as? Player ?: return fail(source.sender, "player-only")
        return if (e.verifier.start(player, mode, only)) 1 else 0
    }

    /** 화면도 `execute as <플레이어> run 인첸트` 로 열 수 있다 — NPC·명령 블록이 누군가에게 열어 줄 때. */
    private fun open(source: CommandSourceStack, menu: (Player) -> Unit): Int {
        val player = source.executor as? Player ?: source.sender as? Player ?: return fail(source.sender, "player-only")
        if (!e.ready) return fail(source.sender, "not-ready")
        menu(player)
        return 1
    }

    private fun reload(sender: CommandSender): Int {
        plugin.reload { e.messages.send(sender, "reloaded", e.ph().count(e.registry.size)) }
        return 1
    }

    /** 인자의 입력 글자 그대로(소문자). 열쇠 인자는 이름공간을 채워 넣으므로 원문을 다시 읽는다. */
    private fun raw(ctx: CommandContext<CommandSourceStack>, name: String): String =
        ctx.nodes.firstOrNull { it.node.name == name }?.range?.get(ctx.input)?.trim()?.lowercase().orEmpty()

    /**
     * 붙이기·떼기. 성공률·칸·서버 금지는 보지 않는다(관리자의 명시적인 명령). 우리 인첸트는 정의의 최대 레벨로 자르고, 바닐라는 [vanillaCap] 을
     * 넘으면 거절한다. `-onlyvalid` 면 새로 붙일 때 부여서와 같은 붙이기 검사를 한다.
     */
    private fun apply(ctx: CommandContext<CommandSourceStack>, level: Int, targetName: String?, onlyValid: Boolean): Int {
        val sender = ctx.source.sender
        val id = raw(ctx, "인첸트")
        val target = target(sender, targetName) ?: return 0
        val stack = target.inventory.itemInMainHand
        if (stack.type.isAir) return if (target == sender) fail(sender, "hand-empty") else fail(sender, "target-hand-empty", player = target.name)
        val enchant = e.scrolls.resolve(id)
        if (enchant == null) {
            // 정의가 지워진 우리 인첸트도 뗄 수 있어야 한다(아이템에 id 만 남아 있다).
            if (level <= 0 && ':' !in id && e.uses.removeRaw(stack, id)) return done(sender, target, stack, removed = true, label = id)
            return fail(sender, unknownKey(id), id)
        }
        val cap = (enchant as? ScrollEnchant.Vanilla)?.let { vanillaCap(sender, it) } ?: MAX_LEVEL
        return when (val change = e.uses.adminApply(target, stack, enchant, level, cap, onlyValid)) {
            is ItemUses.AdminChange.Set -> done(sender, target, stack, removed = false, label = e.scrolls.label(enchant, change.level))
            ItemUses.AdminChange.Removed -> done(sender, target, stack, removed = true, label = e.scrolls.label(enchant))
            ItemUses.AdminChange.NotOnItem -> fail(sender, "enchant-not-on-item", e.scrolls.label(enchant))
            is ItemUses.AdminChange.OverLimit -> {
                e.messages.send(sender, "enchant-over-limit", e.ph().enchant(e.scrolls.label(enchant)).level(change.cap))
                0
            }
            is ItemUses.AdminChange.Invalid -> fail(sender, change.outcome.message, e.scrolls.label(enchant))
            is ItemUses.AdminChange.Kept -> {
                target.inventory.setItemInMainHand(stack)
                e.statics.refresh(target)
                e.messages.send(sender, "enchant-kept-by-item", e.ph().enchant(e.scrolls.label(enchant)).level(change.level))
                0
            }
        }
    }

    /** 콜론 없이 바닐라 이름을 적었으면 고쳐 적는 법을 알려 준다. */
    private fun unknownKey(id: String): String =
        if (':' !in id && e.scrolls.resolve("minecraft:" + id) != null) "vanilla-needs-prefix" else "unknown-enchant"

    private fun done(sender: CommandSender, target: Player, stack: ItemStack, removed: Boolean, label: String): Int {
        target.inventory.setItemInMainHand(stack)
        e.statics.refresh(target)
        val key = when {
            target == sender -> if (removed) "admin-enchant-removed" else "admin-enchant-added"
            else -> if (removed) "enchant-removed-other" else "enchant-added-other"
        }
        e.messages.send(sender, key, e.ph().enchant(label).player(target.name))
        return 1
    }

    /** 대상 — 이름이 없으면 자기. 남에게는 `.others` 권한. 볼 수 없는 투명한 사람은 없는 사람으로 친다(콘솔은 다 본다). */
    private fun target(sender: CommandSender, name: String?): Player? {
        if (name == null) return sender as? Player ?: run { fail(sender, "player-only"); null }
        val found = Bukkit.getPlayerExact(name)?.takeIf { sender !is Player || Vanish.canSee(sender, it) }
        if (found == null) {
            fail(sender, "player-not-found", player = name)
            return null
        }
        if (found != sender && !sender.hasPermission(ENCHANT_OTHERS)) {
            fail(sender, "no-permission")
            return null
        }
        return found
    }

    /**
     * 바닐라 인첸트의 최대 레벨 — `scrolls.yml` `vanilla-max-level`(없으면 바닐라 최대)과 `inmcenchant.vanilla.<인첸트>.<레벨>` 중 큰 것.
     * 레벨 권한은 **명시적으로 준 것만** 센다(`effectivePermissions`) — 선언하지 않은 노드는 OP 에게 전부 참이라(지뢰 11) `hasPermission` 으로
     * 물으면 OP 는 늘 255 가 된다. 콘솔·명령 블록과 `inmcenchant.vanilla.bypass` 는 255.
     */
    private fun vanillaCap(sender: CommandSender, enchant: ScrollEnchant.Vanilla): Int {
        if (sender !is Player || sender.hasPermission(VANILLA_BYPASS)) return MAX_LEVEL
        val granted = sender.effectivePermissions.filter { it.value }.map { it.permission }
        return vanillaCap(e.scrolls.maxLevel(enchant), permissionName(enchant), granted)
    }

    private fun listDisabled(sender: CommandSender): Int {
        val ids = e.disabled.all()
        if (ids.isEmpty()) {
            e.messages.send(sender, "disabled-list-empty", e.ph())
            return 1
        }
        val names = ids.map { id -> e.scrolls.resolve(id)?.let { e.scrolls.label(it) } ?: "$id(없음)" }
        e.messages.send(sender, "disabled-list", e.ph().count(ids.size).value(names.joinToString(", ")))
        return 1
    }

    private fun toggleDisabled(sender: CommandSender, id: String): Int {
        val enchant = e.scrolls.resolve(id)
        if (enchant == null) {
            // 목록에만 남은 id(지워진 인첸트·빠진 데이터팩)도 풀 수 있어야 한다.
            if (id in e.disabled.all()) {
                e.disabled.toggle(id)
                e.messages.send(sender, "disabled-off", e.ph().enchant(id))
                return 1
            }
            return fail(sender, unknownKey(id), id)
        }
        val now = e.disabled.toggle(enchant.id)
        e.messages.send(sender, if (now) "disabled-on" else "disabled-off", e.ph().enchant(e.scrolls.label(enchant)))
        return 1
    }

    private fun fail(sender: CommandSender, key: String, enchant: String = "", player: String = ""): Int {
        e.messages.send(sender, key, e.ph().enchant(enchant).player(player))
        return 0
    }

    companion object {
        const val USE = "inmcenchant.use"
        const val ADMIN = "inmcenchant.admin"
        /** `/인첸트 부여·해제` — 손에 든 아이템(관리 권한의 자식). */
        const val ENCHANT = "inmcenchant.enchant"
        const val ENCHANT_OTHERS = "inmcenchant.enchant.others"
        /** 바닐라 인첸트 레벨 한도를 무시한다(255 까지). */
        const val VANILLA_BYPASS = "inmcenchant.vanilla.bypass"
        /** `inmcenchant.vanilla.<인첸트>.<레벨>` — 그 인첸트를 그 레벨까지. 레벨은 명시적으로 준 노드만 센다. */
        const val VANILLA_PREFIX = "inmcenchant.vanilla."
        const val ONLY_VALID = "-onlyvalid"
        const val MAX_LEVEL = 255

        /**
         * 바닐라 인첸트 [name](권한 노드의 이름, [permissionName]) 의 레벨 한도 — [base](`vanilla-max-level`)와 명시적으로 준 노드 [granted] 의
         * `inmcenchant.vanilla.<name>.<레벨>` 중 가장 큰 레벨 가운데 큰 것, [MAX_LEVEL] 까지. 레벨이 숫자가 아닌 노드는 세지 않는다.
         */
        fun vanillaCap(base: Int, name: String, granted: Collection<String>): Int {
            val prefix = VANILLA_PREFIX + name + "."
            val level = granted.filter { it.startsWith(prefix) }.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: 0
            return maxOf(base, level).coerceAtMost(MAX_LEVEL)
        }

        /** 권한 노드의 인첸트 이름 — `minecraft:` 는 빼고(`sharpness`), 다른 이름공간은 점으로(`ns.key`). */
        fun permissionName(enchant: ScrollEnchant.Vanilla): String {
            val key = enchant.enchantment.key
            return if (key.namespace == "minecraft") key.key else key.namespace + "." + key.key
        }
    }
}
