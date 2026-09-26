package com.inmc.enchants.effect.impl

import org.bukkit.entity.Player

/**
 * 경험치 **점수** 계산. 바닐라 API 는 점수를 빼는 방법을 주지 않는다 — `giveExp(-n)` 은 레벨
 * 경계에서 진행도를 음수로 만든다. 총점을 구해 다시 세운다.
 */
object Experience {

    /** 이 레벨까지 모으는 데 드는 총점(바닐라 공식). */
    fun pointsForLevel(level: Int): Int = when {
        level <= 16 -> level * level + 6 * level
        level <= 31 -> (2.5 * level * level - 40.5 * level + 360).toInt()
        else -> (4.5 * level * level - 162.5 * level + 2220).toInt()
    }

    /** 이 레벨에서 다음 레벨까지. */
    fun pointsToNext(level: Int): Int = when {
        level <= 15 -> 2 * level + 7
        level <= 30 -> 5 * level - 38
        else -> 9 * level - 158
    }

    fun total(player: Player): Int = pointsForLevel(player.level) + (player.exp * pointsToNext(player.level)).toInt()

    fun set(player: Player, points: Int) {
        var left = points.coerceAtLeast(0)
        var level = 0
        while (left >= pointsToNext(level)) {
            left -= pointsToNext(level)
            level++
        }
        player.level = level
        player.exp = (left.toFloat() / pointsToNext(level)).coerceIn(0f, 0.9999f)
    }

    /** 최대 [amount] 만큼 빼고, 실제로 뺀 양을 준다. */
    fun take(player: Player, amount: Int): Int {
        val current = total(player)
        val taken = amount.coerceIn(0, current)
        set(player, current - taken)
        return taken
    }
}
