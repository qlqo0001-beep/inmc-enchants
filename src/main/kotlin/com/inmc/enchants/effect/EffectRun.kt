package com.inmc.enchants.effect

import com.inmc.enchants.Enchants
import com.inmc.enchants.engine.EffectLine
import com.inmc.enchants.engine.EffectSpec
import com.inmc.enchants.engine.Target
import com.inmc.enchants.engine.TriggerContext
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

/**
 * 효과 한 번 실행. 인자는 **이미 풀린 값**이다(변수·함수 치환 끝).
 *
 * 빈 칸은 [EffectSpec] 의 기본값으로 채운다 — `BOOST` 처럼 인자를 생략하는 AE 줄이 많다.
 */
class EffectRun(
    val enchants: Enchants,
    val ctx: TriggerContext,
    val line: EffectLine,
    val spec: EffectSpec,
    val args: List<String>,
    /** 사건형(EVENT) 효과면 null. */
    val target: Target?,
    /** `location=` 을 풀어 둔 곳. 없으면 null. */
    val locationOverride: Location?,
) {

    fun arg(index: Int): String =
        args.getOrNull(index)?.takeIf { it.isNotBlank() } ?: spec.args.getOrNull(index)?.default ?: ""

    fun num(index: Int): Double = arg(index).trim().replace(',', '.').toDoubleOrNull() ?: 0.0

    fun int(index: Int): Int = num(index).toInt()

    fun bool(index: Int): Boolean = arg(index).trim().lowercase().let { it == "true" || it == "yes" || it == "1" }

    val entity: Entity? get() = (target as? Target.OfEntity)?.entity

    val living: LivingEntity? get() = entity as? LivingEntity

    val player: Player? get() = entity as? Player

    /** 효과가 일어날 곳. `location=` 이 있으면 그것. */
    val location: Location get() = locationOverride ?: target?.location ?: ctx.self.location

    val block: Block? get() = (target as? Target.OfBlock)?.block ?: locationOverride?.block ?: entity?.location?.block

    /** 지속형을 벗는 중. 되돌릴 수 있는 효과만 되돌리고 나머지는 아무것도 안 한다. */
    val removal: Boolean get() = ctx.removal

    /** 착용·들기처럼 "동안" 형인가. 시간 인자를 비우면 무한으로 건다. */
    val static: Boolean get() = ctx.trigger.static

    /** 발동한 쪽 반대편. 대상이 발동한 쪽이면 상대, 아니면 발동한 쪽. 훔치기·끌어당기기의 기준이다. */
    fun counterpart(of: Entity): Entity? = if (of == ctx.self) ctx.opponent else ctx.self
}

/** 효과 실행 코드. [EffectRun] 하나를 받는다. */
fun interface EffectExec {
    fun run(run: EffectRun)
}
