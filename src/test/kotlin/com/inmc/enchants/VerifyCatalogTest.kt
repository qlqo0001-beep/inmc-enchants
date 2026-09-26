package com.inmc.enchants

import com.inmc.enchants.engine.EffectLine
import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.verify.Probes
import com.inmc.enchants.verify.Wiring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 검증기(`/인첸트 검증`)가 **빠짐없이** 검사하는지. 검증기 자체는 서버에서만 돌지만, 무엇을
 * 검사할지의 목록은 서버 없이 맞춰 볼 수 있다 — 효과를 하나 더했는데 검사식을 잊으면 검증기가
 * 그 효과를 "검사식 없음" 으로 떨어뜨리긴 하지만, 그건 서버를 띄워야 보인다.
 */
class VerifyCatalogTest {

    private val names = EffectSpecs.ALL.map { it.name }.toSet()

    @Test
    fun `모든 효과에 검사식과 표본 줄이 있다`() {
        assertEquals(names, Probes.ALL.keys, "검사식 목록과 효과 목록이 다르다")
        assertEquals(names, Probes.SAMPLES.keys, "표본 줄 목록과 효과 목록이 다르다")
    }

    @Test
    fun `표본 줄은 그 효과로 읽힌다`() {
        val bad = Probes.SAMPLES.mapNotNull { (name, raw) ->
            val parsed = EffectLine.parse(raw, EffectSpecs::shape)
            when {
                parsed.line == null -> "$name: ${parsed.error}"
                EffectSpecs.of(parsed.line!!.effect)?.name != name -> "$name: 다른 효과로 읽힌다(${parsed.line!!.effect})"
                else -> null
            }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun `되돌릴 수 있는 효과는 전부 들었다 놓기 검사를 받는다`() {
        val reversible = EffectSpecs.ALL.filter { it.reversible }.map { it.name }.toSet()
        assertEquals(reversible, Probes.STATIC_SAMPLES.keys)
        for ((name, raw) in Probes.STATIC_SAMPLES) {
            val line = EffectLine.parse(raw, EffectSpecs::shape).line
            assertEquals(name, line?.effect, "$name 의 들기 표본이 읽히지 않는다: $raw")
            assertTrue(line!!.targets.isEmpty(), "$name: 지속형은 자기에게 건다 - 대상 표시를 빼야 한다")
        }
    }

    @Test
    fun `모든 발동 조건에 배선 검사가 있다`() {
        val covered = Wiring.CASES.map { it.trigger }.toSet()
        val missing = Trigger.entries.filterNot { it in covered }
        assertTrue(missing.isEmpty(), "배선 검사가 없는 발동 조건: $missing")
    }
}
