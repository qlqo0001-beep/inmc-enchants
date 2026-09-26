package com.inmc.enchants

import com.inmc.enchants.engine.Condition
import com.inmc.enchants.engine.EffectLine
import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.engine.Functions
import com.inmc.enchants.engine.MathExpr
import com.inmc.enchants.engine.TargetKind
import com.inmc.enchants.engine.TargetSpec
import com.inmc.enchants.engine.Trigger
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 효과 줄·조건·함수 문법. **AE 문서의 예제를 그대로** 넣어 같은 뜻으로 읽히는지 본다 —
 * AE 설정을 가져다 쓸 수 있다는 약속이 여기서 지켜진다.
 */
class EngineParseTest {

    private fun line(raw: String): EffectLine {
        val parsed = EffectLine.parse(raw, EffectSpecs::shape)
        assertNull(parsed.error, "파싱 실패: $raw")
        return parsed.line!!
    }

    // --- 효과 줄 -----------------------------------------------------------------------

    @Test
    fun `AE 기본 줄을 읽는다`() {
        val l = line("POTION:SLOW_DIGGING:0:60 @Attacker")
        assertEquals("POTION", l.effect)
        assertEquals(listOf("SLOW_DIGGING", "0", "60"), l.args)
        assertEquals(listOf(TargetSpec(TargetKind.ATTACKER)), l.targets)
    }

    @Test
    fun `대상 옵션과 별칭을 정식 이름으로 읽는다`() {
        val l = line("BREAK_BLOCK @Aoe{r=3,t=mobs,p=2}")
        val target = l.targets.single()
        assertEquals(TargetKind.AOE, target.kind)
        assertEquals(mapOf("radius" to "3", "target" to "mobs", "limit" to "2"), target.options)
    }

    @Test
    fun `여러 대상과 포인터를 떼어 낸다`() {
        val l = line("DECREASE_DAMAGE:40 @Attacker @Victim ~DEFENSE ~!ATTACK")
        assertEquals(2, l.targets.size)
        assertTrue(l.allows(Trigger.DEFENSE))
        assertFalse(l.allows(Trigger.ATTACK))
        assertFalse(l.allows(Trigger.MINING), "긍정 포인터가 있으면 그것만 허용")
    }

    @Test
    fun `메시지의 콜론과 골뱅이는 글자로 남는다`() {
        val l = line("MESSAGE:남은 시간: 5초 @naver 아님 @Self")
        assertEquals(listOf("남은 시간: 5초 @naver 아님"), l.args)
        assertEquals(TargetKind.SELF, l.targets.single().kind)
    }

    @Test
    fun `위치 옵션은 공백 든 변수도 받는다`() {
        assertEquals("~0|0|-8", line("TELEPORT location=~0|0|-8").location)
        assertEquals("%hit location%", line("LIGHTNING location=%hit location%").location)
    }

    @Test
    fun `줄 확률과 줄 조건을 떼어 낸다`() {
        val l = line("ADD_HEALTH:5 <condition>%victim health% < 10 : %allow%</condition> <chance>50</chance>")
        assertEquals("50", l.chance)
        assertNotNull(l.condition)
        assertEquals(listOf("5"), l.args)
    }

    @Test
    fun `함수 안의 콜론은 자르지 않는다`() {
        val l = line("INCREASE_DAMAGE:<math>%level% * 2</math>")
        assertEquals(listOf("<math>%level% * 2</math>"), l.args)
    }

    @Test
    fun `모르는 효과와 대상은 이유와 함께 거부한다`() {
        assertEquals("모르는 효과: NOPE", EffectLine.parse("NOPE:1", EffectSpecs::shape).error)
        assertTrue(EffectLine.parse("BURN:20 @Nobody", EffectSpecs::shape).error!!.contains("모르는 대상"))
    }

    @Test
    fun `저장한 줄을 다시 읽으면 같다`() {
        val l = line("POTION:SPEED:1:100 @Aoe{radius=5,target=PLAYERS} ~ATTACK <chance>30</chance>")
        val again = line(l.serialize())
        assertEquals(l.copy(raw = ""), again.copy(raw = ""))
    }

    // --- 조건 ----------------------------------------------------------------------------

    private fun decide(vararg raw: String, vars: Map<String, String>) =
        Condition.decide(raw.map { Condition.parse(it)!! }) { operand ->
            vars.entries.fold(operand) { acc, (k, v) -> acc.replace(k, v) }
        }

    @Test
    fun `AE 문서의 조건 예제`() {
        val low = mapOf("%victim health%" to "4")
        val high = mapOf("%victim health%" to "8")
        assertTrue(decide("%victim health% > 5 : %stop%", vars = low).activate)
        assertFalse(decide("%victim health% > 5 : %stop%", vars = high).activate)
    }

    @Test
    fun `allow 는 문지기다 - 거짓이면 막는다`() {
        val ok = decide("%player world% = my_world : %allow%", vars = mapOf("%player world%" to "my_world"))
        val no = decide("%player world% = my_world : %allow%", vars = mapOf("%player world%" to "world"))
        assertTrue(ok.activate)
        assertFalse(no.activate)
        // AE 기본 팩의 콤보 조건이 정확히 이렇게 쓴다
        assertFalse(decide("%attacker combo% > 2 : %allow%", vars = mapOf("%attacker combo%" to "2")).activate)
    }

    @Test
    fun `괄호와 AND OR 를 읽는다`() {
        val raw = "(%victim max health% > 20 AND %victim world% != dungeon) OR %victim world% = prison : %allow%"
        assertTrue(decide(raw, vars = mapOf("%victim max health%" to "30", "%victim world%" to "world")).activate)
        assertFalse(decide(raw, vars = mapOf("%victim max health%" to "30", "%victim world%" to "dungeon")).activate)
        assertTrue(decide(raw, vars = mapOf("%victim max health%" to "10", "%victim world%" to "prison")).activate)
    }

    @Test
    fun `확률 조정은 쌓이고 force 는 확률을 무시한다`() {
        val d = decide("%player y% < 30 && %player health% > 10 = : %chance%+10", "%player y% < 30 : %chance%-3",
            vars = mapOf("%player y%" to "5", "%player health%" to "20"))
        assertEquals(7.0, d.chanceDelta)
        assertTrue(decide("%is sneaking% = true : %force%", vars = mapOf("%is sneaking%" to "true")).force)
    }

    @Test
    fun `변수 값에 연산자가 들어도 식의 모양이 안 바뀐다`() {
        // 통째로 치환한 뒤 파싱했다면 "A = B" 이름이 비교식으로 둔갑한다
        val d = decide("%victim name% = boss : %stop%", vars = mapOf("%victim name%" to "A = B"))
        assertTrue(d.activate)
    }

    @Test
    fun `숫자가 아닌데 크기 비교면 거짓`() {
        assertFalse(Condition.compare("abc", Condition.Op.GT, "1"))
        assertTrue(Condition.compare("Nether_World", Condition.Op.CONTAINS, "nether"))
        assertTrue(Condition.compare(".dot", Condition.Op.REGEX, "^\\.\\w+"))
    }

    @Test
    fun `결과가 없거나 식이 비면 거부한다`() {
        assertNull(Condition.parse("%victim health% > 5"))
        assertNull(Condition.parse(": %stop%"))
    }

    // --- 함수 ----------------------------------------------------------------------------

    @Test
    fun `함수를 안쪽부터 푼다`() {
        val r = Functions.apply("<round><math>7 * 2.5</math></round>", Random(1))
        assertEquals("18", r.text)
        assertEquals("3 개", Functions.apply("<int>3.9</int> 개", Random(1)).text)
    }

    @Test
    fun `무작위 수는 범위 안이고 마지막 값을 기억한다`() {
        repeat(50) { seed ->
            val r = Functions.apply("<random number>1-5</random number>", Random(seed.toLong()))
            val n = r.text.toInt()
            assertTrue(n in 1..5, "범위 밖: $n")
            assertEquals(n.toDouble(), r.lastRandom)
        }
        val word = Functions.apply("<random word>가,나,다</random word>", Random(3)).text
        assertTrue(word in listOf("가", "나", "다"))
    }

    @Test
    fun `식 계산`() {
        assertEquals(14.0, MathExpr.eval("2 + 3 * 4"))
        assertEquals(20.0, MathExpr.eval("(2 + 3) * 4"))
        assertEquals(8.0, MathExpr.eval("2 ^ 3"))
        assertEquals(-3.0, MathExpr.eval("-3"))
        assertEquals(5.0, MathExpr.eval("max(1, 5, 3)"))
        assertEquals(0.0, MathExpr.eval("5 / 0"), "0 으로 나누면 0 - 예외로 발동이 죽지 않게")
        assertNull(MathExpr.eval("2 +"))
        assertNull(MathExpr.eval("Runtime.exec()"))
    }

    // --- 발동 조건 ------------------------------------------------------------------------

    @Test
    fun `옛 발동 조건 이름을 새 이름으로 읽는다`() {
        val unknown = mutableListOf<String>()
        val list = Trigger.parseList("BOW;DEFENSE_BOW_MOB;ATTACK;NOPE", unknown)
        assertEquals(listOf(Trigger.SHOOT, Trigger.DEFENSE_MOB_PROJECTILE, Trigger.ATTACK), list)
        assertEquals(listOf("NOPE"), unknown)
    }
}
