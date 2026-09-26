package com.inmc.enchants.listener

import com.inmc.enchants.Enchants
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.engine.TriggerContext
import kr.inmc.core.event.InmcSignalEvent
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * inmc-fishing 의 낚기를 CATCH_FISH 로 받는다.
 *
 * inmc-fishing 은 바닐라 낚기를 가로채 스스로 지급한다 — 그래서 바닐라 `PlayerFishEvent` 의
 * CAUGHT_FISH 는 취소된 채로 지나간다. 대신 core 신호 `fishing/catch` 를 쏜다. **inmc-fishing 을
 * 컴파일 시점에 알지 않는다** — core 의 신호만 본다(업적과 같은 방식).
 *
 * 신호의 `data`(물고기·등급·트로피·대물·쌍걸이·크기)를 변수로 그대로 넘긴다. 조건에서
 * `%grade% = s : %allow%` 처럼 쓸 수 있다.
 */
class FishingSignalListener(private val e: Enchants, private val actions: ActionListener) : Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    fun onSignal(event: InmcSignalEvent) {
        if (!e.ready || event.source != "fishing" || event.type != "catch") return
        val player = event.player ?: return
        val values: MutableMap<String, String> = HashMap(event.data)
        values["caught"] = event.subject
        values["fish amount"] = event.amount.toString()
        e.engine.fire(TriggerContext(Trigger.CATCH_FISH, player, player, null, event, values = values))
        actions.fishCaught(player)
    }
}
