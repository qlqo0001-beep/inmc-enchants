package com.inmc.enchants.engine

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 엔진이 발동 사이에 기억하는 것들. 전부 메모리다 — AE 와 같이 재시작하면 비워진다
 * (쿨다운·콤보·봉인은 몇 초짜리라 저장할 이유가 없고, 변수는 AE 가 원래 그렇다).
 *
 * 전부 메인 스레드에서만 만진다. 동시 맵을 쓰는 것은 틱커가 청소하는 사이 리스너가
 * 끼어들어도 예외가 나지 않게 하려는 것이지 스레드를 넘나들기 위해서가 아니다.
 */
class EngineState {

    // --- 쿨다운 -------------------------------------------------------------------------

    private val cooldowns = ConcurrentHashMap<String, Long>()

    fun onCooldown(entity: UUID, enchant: String, now: Long): Boolean =
        (cooldowns["$entity|$enchant"] ?: 0L) > now

    fun cooldownLeft(entity: UUID, enchant: String, now: Long): Long =
        ((cooldowns["$entity|$enchant"] ?: 0L) - now).coerceAtLeast(0L)

    fun startCooldown(entity: UUID, enchant: String, seconds: Double, now: Long) {
        if (seconds <= 0) return
        cooldowns["$entity|$enchant"] = now + (seconds * 1000).toLong()
    }

    // --- 콤보(연속 타격) -----------------------------------------------------------------

    private class Combo(var target: UUID, var count: Int, var last: Long)

    private val combos = ConcurrentHashMap<UUID, Combo>()

    /** 같은 상대를 [windowMillis] 안에 다시 때리면 +1, 아니면 1 부터. */
    fun hit(attacker: UUID, victim: UUID, now: Long, windowMillis: Long = 3000L): Int {
        val combo = combos[attacker]
        if (combo == null || combo.target != victim || now - combo.last > windowMillis) {
            combos[attacker] = Combo(victim, 1, now)
            return 1
        }
        combo.count++
        combo.last = now
        return combo.count
    }

    fun combo(entity: UUID, now: Long, windowMillis: Long = 3000L): Int {
        val combo = combos[entity] ?: return 0
        return if (now - combo.last > windowMillis) 0 else combo.count
    }

    fun resetCombo(entity: UUID) {
        combos.remove(entity)
    }

    // --- 발동 봉인(DISABLE_ACTIVATION) ---------------------------------------------------

    private val disabled = ConcurrentHashMap<UUID, MutableMap<String, Long>>()

    fun disable(entity: UUID, enchant: String, seconds: Double, now: Long) {
        disabled.getOrPut(entity) { ConcurrentHashMap() }[enchant.lowercase()] = now + (seconds * 1000).toLong()
    }

    /** 걸린 봉인을 전부 푼다(UNSEAL). */
    fun clearDisabled(entity: UUID) {
        disabled.remove(entity)
    }

    fun isDisabled(entity: UUID, enchant: String, now: Long): Boolean {
        val map = disabled[entity] ?: return false
        val all = map["all"] ?: 0L
        val one = map[enchant.lowercase()] ?: 0L
        return all > now || one > now
    }

    // --- 표식(MARK) --------------------------------------------------------------------

    /** 사람 → 표식 이름 → 끝나는 시각. 헥스·야수의 표식처럼 "몇 초 동안 걸린 상태"를 조건이 읽는다. */
    private val marks = ConcurrentHashMap<UUID, MutableMap<String, Long>>()

    fun mark(entity: UUID, name: String, seconds: Double, now: Long) {
        marks.getOrPut(entity) { ConcurrentHashMap() }[name.lowercase()] = now + (seconds * 1000).toLong()
    }

    fun isMarked(entity: UUID, name: String, now: Long): Boolean = (marks[entity]?.get(name.lowercase()) ?: 0L) > now

    // --- 사용자 변수(SET_VARIABLE) -------------------------------------------------------

    val variables = ConcurrentHashMap<String, String>()

    // --- 이동 능력(물·용암 위 걷기, 거미줄) ----------------------------------------------

    enum class Walker { WATER, LAVA, WEB }

    private val walkers = ConcurrentHashMap<UUID, MutableSet<Walker>>()

    fun setWalker(entity: UUID, walker: Walker, on: Boolean) {
        if (on) walkers.getOrPut(entity) { ConcurrentHashMap.newKeySet() }.add(walker)
        else walkers[entity]?.remove(walker)
    }

    fun hasWalker(entity: UUID, walker: Walker): Boolean = walkers[entity]?.contains(walker) == true

    // --- 지속형 속도 더하기(ADD_WALK_SPEED·ADD_FLY_SPEED) ----------------------------------

    /**
     * "사람|종류|칸|인첸트" → 실제로 더한 값. 벗을 때 이만큼 뺀다. [forget] 이 지우지 않는다 —
     * 나갈 때 지속 효과를 벗기는 것이 이 값을 쓴다(순서가 뒤집히면 속도가 영영 남는다).
     */
    val speedDeltas = ConcurrentHashMap<String, Float>()

    // --- 넉백 무효 -----------------------------------------------------------------------

    private val noKnockback = ConcurrentHashMap<UUID, Long>()

    fun blockKnockback(entity: UUID, until: Long) {
        noKnockback[entity] = until
    }

    fun knockbackBlocked(entity: UUID, now: Long): Boolean = (noKnockback[entity] ?: 0L) > now

    // --- 이번 틱 방어구 내구도 보호(IGNORE_ARMOR_DAMAGE) ---------------------------------

    private val armorSafe = ConcurrentHashMap<UUID, Long>()

    fun protectArmor(entity: UUID, until: Long) {
        armorSafe[entity] = until
    }

    fun armorProtected(entity: UUID, now: Long): Boolean = (armorSafe[entity] ?: 0L) >= now

    // --- 액션바 알림 쓰로틀(인첸트 설정 showActionBar, 1초) -------------------------------

    private val actionBar = ConcurrentHashMap<UUID, Long>()

    fun mayShowActionBar(entity: UUID, now: Long): Boolean {
        val last = actionBar[entity] ?: 0L
        if (now - last < 1000L) return false
        actionBar[entity] = now
        return true
    }

    /** 오래된 것을 치운다. 1초 틱커가 부른다. */
    fun sweep(now: Long) {
        cooldowns.entries.removeIf { it.value <= now }
        combos.entries.removeIf { now - it.value.last > 10_000L }
        for (map in disabled.values) map.entries.removeIf { it.value <= now }
        disabled.entries.removeIf { it.value.isEmpty() }
        for (map in marks.values) map.entries.removeIf { it.value <= now }
        marks.entries.removeIf { it.value.isEmpty() }
        noKnockback.entries.removeIf { it.value <= now }
        armorSafe.entries.removeIf { it.value < now }
        actionBar.entries.removeIf { now - it.value > 5000L }
    }

    /** 나간 사람의 흔적. 이동 능력은 지속형이 다시 걸어준다. */
    fun forget(entity: UUID) {
        combos.remove(entity)
        marks.remove(entity)
        walkers.remove(entity)
        noKnockback.remove(entity)
        armorSafe.remove(entity)
        actionBar.remove(entity)
    }
}
