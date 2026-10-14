package com.inmc.enchants.item

import com.inmc.enchants.Enchants
import kr.inmc.core.integration.Mail
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * 플레이어에게 아이템을 준다 — 가방에 넣고, 넘친 것은 core [Mail] 우편으로(2026-10-10 사용자 결정: 가방이 차면 바닥 대신 우편).
 * 우편 공급처가 없거나 받지 않으면 전처럼 발밑에 떨군다. **한 번만 준다** — 우편이 받았으면 떨구지 않는다.
 *
 * 산 것·되돌려 받는 것·여는 것처럼 "플레이어 몫"에만 쓴다. 효과가 떨구는 드랍(배수·제련·텔레키네시스)은 그대로 바닥이다.
 *
 * @param source 우편에 적히는 출처(사람이 읽는 말).
 */
fun deliver(e: Enchants, player: Player, stack: ItemStack, source: String) {
    val left = player.inventory.addItem(stack).values.toList()
    if (left.isEmpty()) return
    if (Mail.available && Mail.send(player.uniqueId, e.plugin.name, source, left)) {
        e.messages.send(player, "overflow-mailed", e.ph())
        return
    }
    for (rest in left) player.world.dropItemNaturally(player.location, rest)
}
