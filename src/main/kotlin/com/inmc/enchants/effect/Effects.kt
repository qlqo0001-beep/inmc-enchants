package com.inmc.enchants.effect

import com.inmc.enchants.effect.impl.BlockEffects
import com.inmc.enchants.effect.impl.CombatEffects
import com.inmc.enchants.effect.impl.ItemEffects
import com.inmc.enchants.effect.impl.MiscEffects
import com.inmc.enchants.effect.impl.MovementEffects
import com.inmc.enchants.effect.impl.StateEffects
import com.inmc.enchants.effect.impl.WorldEffects

/**
 * 효과 이름 → 실행 코드. [com.inmc.enchants.engine.EffectSpecs] 의 모든 이름에 짝이 있어야 한다
 * (`EffectCoverageTest` 가 본다) — 모양만 있고 실행 코드가 없으면 적재는 통과하는데 발동하면
 * 아무 일도 안 일어난다.
 */
class Effects {

    private val map: Map<String, EffectExec> = buildMap {
        CombatEffects.register(this)
        StateEffects.register(this)
        MovementEffects.register(this)
        BlockEffects.register(this)
        ItemEffects.register(this)
        WorldEffects.register(this)
        MiscEffects.register(this)
    }

    fun of(name: String): EffectExec? = map[name.uppercase()]

    val names: Set<String> get() = map.keys
}
