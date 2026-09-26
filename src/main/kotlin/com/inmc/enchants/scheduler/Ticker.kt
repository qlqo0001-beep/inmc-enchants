package com.inmc.enchants.scheduler

import com.inmc.enchants.Enchants
import com.inmc.enchants.listener.ActionListener
import kr.inmc.core.scheduler.TickerBase

/**
 * 이 플러그인의 반복 작업, 1Hz. 지속 효과 맞추기·반복 인첸트·만료된 상태 청소·저장.
 */
class Ticker(private val e: Enchants, private val actions: ActionListener) : TickerBase(e.plugin) {

    override val periodTicks = 20L

    override fun ready(): Boolean = e.ready

    override fun tick(now: Long) {
        step("prompts") { e.prompts.tick(now) }
        step("statics") { e.statics.tick(now) }
        step("state") { e.state.sweep(now) }
        step("support") { e.support.sweep(now) }
        step("projectiles") { e.projectiles.sweep(now) }
        step("actions") { actions.sweep() }
        step("flush") {
            e.registry.flush()
            e.groups.flush()
        }
    }
}
