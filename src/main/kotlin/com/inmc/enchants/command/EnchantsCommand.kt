package com.inmc.enchants.command

import com.inmc.enchants.Enchants
import com.inmc.enchants.EnchantsPlugin
import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.verify.Verifier
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.suggestion.SuggestionProvider
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

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

    /** `해제` 는 손에 든 것에 붙은 것만. */
    private val heldIds = SuggestionProvider<CommandSourceStack> { ctx, builder ->
        val held = (ctx.source.sender as? Player)?.inventory?.itemInMainHand
        EnchantStorage.read(held).keys.filter { it.startsWith(builder.remainingLowerCase) }.forEach { builder.suggest(it) }
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
            .then(
                Commands.literal("부여").requires { it.sender.hasPermission(ADMIN) }
                    .then(
                        Commands.argument("인첸트", StringArgumentType.word()).suggests(enchantIds)
                            .executes { ctx -> enchant(ctx.source.sender, StringArgumentType.getString(ctx, "인첸트"), 1) }
                            .then(
                                Commands.argument("레벨", IntegerArgumentType.integer(1, 100))
                                    .executes { ctx ->
                                        enchant(ctx.source.sender, StringArgumentType.getString(ctx, "인첸트"), IntegerArgumentType.getInteger(ctx, "레벨"))
                                    },
                            ),
                    ),
            )
            .then(verifyTree())
            .then(
                Commands.literal("해제").requires { it.sender.hasPermission(ADMIN) }
                    .then(
                        Commands.argument("인첸트", StringArgumentType.word()).suggests(heldIds)
                            .executes { ctx -> unenchant(ctx.source.sender, StringArgumentType.getString(ctx, "인첸트")) },
                    ),
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

    /** 손에 든 것에 바로 붙인다(성공률·칸 무시, 진화 사슬의 아래 단계는 지운다). 관리자용 — `내 아이템` 화면과 같은 길. */
    private fun enchant(sender: CommandSender, id: String, level: Int): Int {
        val player = sender as? Player ?: return fail(sender, "player-only")
        val def = e.registry.get(id) ?: return fail(sender, "unknown-enchant", id)
        if (player.inventory.itemInMainHand.type.isAir) return fail(sender, "hand-empty")
        e.uses.adminSet(player, def, level)
        return 1
    }

    private fun unenchant(sender: CommandSender, id: String): Int {
        val player = sender as? Player ?: return fail(sender, "player-only")
        if (player.inventory.itemInMainHand.type.isAir) return fail(sender, "hand-empty")
        if (!e.uses.adminRemove(player, id)) return fail(sender, "enchant-not-on-item", id)
        return 1
    }

    private fun fail(sender: CommandSender, key: String, enchant: String = ""): Int {
        e.messages.send(sender, key, e.ph().enchant(enchant))
        return 0
    }

    companion object {
        const val USE = "inmcenchant.use"
        const val ADMIN = "inmcenchant.admin"
    }
}
