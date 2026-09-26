package com.inmc.enchants.effect.impl

import com.inmc.enchants.effect.EffectExec
import com.inmc.enchants.item.EnchantStorage
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.time.Duration

/** 메시지·명령·권한·경제·경험치·인첸트·영혼·변수. */
internal object MiscEffects {

    fun register(map: MutableMap<String, EffectExec>) {
        map["MESSAGE"] = EffectExec { run -> run.player?.sendMessage(run.enchants.text(run.arg(0))) }
        map["ACTION_BAR"] = EffectExec { run -> run.player?.sendActionBar(run.enchants.text(run.arg(0))) }
        map["TITLE"] = EffectExec { run ->
            run.player?.showTitle(title(run.enchants.text(run.arg(0)), net.kyori.adventure.text.Component.empty()))
        }
        map["SUBTITLE"] = EffectExec { run ->
            run.player?.showTitle(title(net.kyori.adventure.text.Component.empty(), run.enchants.text(run.arg(0))))
        }
        map["BROADCAST"] = EffectExec { run -> Bukkit.broadcast(run.enchants.text(run.arg(0))) }
        map["BROADCAST_PERMISSION"] = EffectExec { run -> Bukkit.broadcast(run.enchants.text(run.arg(1)), run.arg(0)) }
        map["CONSOLE_COMMAND"] = EffectExec { run ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), run.arg(0).trim().removePrefix("/"))
        }
        map["PLAYER_COMMAND"] = EffectExec { run -> run.player?.performCommand(run.arg(0).trim().removePrefix("/")) }
        map["PERMISSION"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            val node = run.arg(0).trim()
            if (node.isEmpty()) return@EffectExec
            // 지속형은 입을 때 주고 벗을 때 뺀다. 한 번짜리는 AE 처럼 뒤집는다.
            val on: Boolean? = when {
                run.removal -> false
                run.static -> true
                else -> null
            }
            run.enchants.support.permission(player, node, on)
        }

        map["ADD_MONEY"] = EffectExec { run -> run.player?.let { run.enchants.economy.deposit(it, run.num(0)) } }
        map["REMOVE_MONEY"] = EffectExec { run -> run.player?.let { run.enchants.economy.withdraw(it, run.num(0)) } }
        map["STEAL_MONEY"] = EffectExec { run ->
            val victim = run.player ?: return@EffectExec
            val thief = run.counterpart(victim) as? Player ?: return@EffectExec
            val economy = run.enchants.economy
            val amount = run.num(0).coerceAtMost(economy.balance(victim))
            if (amount <= 0 || !economy.withdraw(victim, amount)) return@EffectExec
            economy.deposit(thief, amount)
            if (run.enchants.config.stealMoneyMessage) {
                thief.sendMessage(run.enchants.text(run.enchants.messages.raw("money-stolen").replace("{amount}", economy.format(amount)).replace("{player}", victim.name)))
            }
        }
        map["STEAL_EXP"] = EffectExec { run ->
            val victim = run.player ?: return@EffectExec
            val thief = run.counterpart(victim) as? Player ?: return@EffectExec
            val taken = Experience.take(victim, run.int(0))
            if (taken > 0) thief.giveExp(taken)
        }

        map["ADD_ENCHANT"] = EffectExec { run ->
            val stack = run.ctx.item ?: return@EffectExec
            val id = run.arg(0).lowercase()
            if (run.enchants.registry.get(id) == null) return@EffectExec
            val enchants = EnchantStorage.read(stack)
            enchants[id] = run.int(1).coerceAtLeast(1)
            EnchantStorage.write(stack, enchants)
            run.enchants.lore.render(stack)
            run.ctx.slot?.let { run.ctx.self.equipment?.setItem(it, stack) }
        }
        map["REMOVE_ENCHANT"] = EffectExec { run ->
            val stack = run.ctx.item ?: return@EffectExec
            val enchants = EnchantStorage.read(stack)
            if (enchants.remove(run.arg(0).lowercase()) == null) return@EffectExec
            EnchantStorage.write(stack, enchants)
            run.enchants.lore.render(stack)
            run.ctx.slot?.let { run.ctx.self.equipment?.setItem(it, stack) }
        }
        map["ADD_SOULS"] = EffectExec { run -> run.ctx.item?.let { run.enchants.souls.add(it, run.int(0)); resync(run) } }
        map["REMOVE_SOULS"] = EffectExec { run -> run.ctx.item?.let { run.enchants.souls.add(it, -run.int(0)); resync(run) } }
        map["DISABLE_ACTIVATION"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            run.enchants.state.disable(target.uniqueId, run.arg(0), run.num(1), System.currentTimeMillis())
        }
        map["UNSEAL"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            run.enchants.state.clearDisabled(target.uniqueId)
        }
        map["SET_VARIABLE"] = EffectExec { run -> run.enchants.state.variables[run.arg(0)] = run.arg(1) }
        map["MARK"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            run.enchants.state.mark(target.uniqueId, run.arg(0), run.num(1), System.currentTimeMillis())
        }
        map["INVERT_VARIABLE"] = EffectExec { run ->
            val name = run.arg(0)
            val current = run.enchants.state.variables[name]?.equals("true", ignoreCase = true) ?: false
            run.enchants.state.variables[name] = (!current).toString()
        }
        map["RESET_COMBO"] = EffectExec { run -> run.entity?.let { run.enchants.state.resetCombo(it.uniqueId) } }
        // WAIT 는 엔진이 줄 목록을 자르며 직접 처리한다. 여기 있는 것은 "실행 코드가 있다"는 표시다.
        map["WAIT"] = EffectExec { }
    }

    private fun resync(run: com.inmc.enchants.effect.EffectRun) {
        val stack = run.ctx.item ?: return
        run.enchants.lore.render(stack)
        run.ctx.slot?.let { run.ctx.self.equipment?.setItem(it, stack) }
    }

    private fun title(main: net.kyori.adventure.text.Component, sub: net.kyori.adventure.text.Component) =
        net.kyori.adventure.title.Title.title(
            main, sub,
            net.kyori.adventure.title.Title.Times.times(Duration.ofMillis(250), Duration.ofMillis(2000), Duration.ofMillis(500)),
        )
}
