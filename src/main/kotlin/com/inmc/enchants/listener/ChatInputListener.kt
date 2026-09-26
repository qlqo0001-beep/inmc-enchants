package com.inmc.enchants.listener

import com.inmc.enchants.Enchants
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener

/**
 * 편집 화면의 채팅 입력을 core [kr.inmc.core.input.ChatPrompt] 로 넘긴다.
 * 이게 없으면 화면이 값을 물어본 뒤 **영영 기다린다**(오류 없이 입력만 채팅에 찍힌다).
 */
class ChatInputListener(private val e: Enchants) : Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!e.prompts.isWaiting(player.uniqueId)) return
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (e.prompts.submit(player, text)) event.isCancelled = true
    }
}
