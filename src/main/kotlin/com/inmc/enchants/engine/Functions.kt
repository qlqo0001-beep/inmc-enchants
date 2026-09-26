package com.inmc.enchants.engine

import java.util.Random
import kotlin.math.roundToLong

/**
 * 효과 줄 안의 값 함수. AE 와 같은 꺾쇠 문법이다.
 *
 * | 함수 | 뜻 |
 * |---|---|
 * | `<math>식</math>` | 계산 ([MathExpr]) |
 * | `<random number>1-5</random number>` | 두 값 사이 무작위 (정수 둘이면 정수) |
 * | `<random word>a,b,c</random word>` | 목록 중 하나 |
 * | `<round>x</round>` · `<int>x</int>` | 반올림 · 소수 버림 |
 * | `<scramble>글자</scramble>` | 글자 섞기 |
 *
 * `<chance>` 와 `<condition>` 은 **값이 아니라 줄의 조건**이라 [EffectLine] 이 파싱 때 떼어낸다.
 *
 * **안쪽부터 푼다.** `<round><math>%exp% * 2.5</math></round>` 는 math 가 먼저다. 변수는
 * 이보다 먼저 치환돼 있어야 한다 — 그래야 `<math>%exp% * 2</math>` 가 숫자 식이 된다.
 */
object Functions {

    private val TAG = Regex("<(math|random number|random word|round|int|scramble)>([^<>]*)</\\1>")

    /** 마지막으로 뽑은 무작위 수. `%random%` 변수가 읽는다. */
    data class Result(val text: String, val lastRandom: Double?)

    fun apply(text: String, random: Random): Result {
        if (!text.contains('<')) return Result(text, null)
        var current = text
        var last: Double? = null
        // 한 번에 가장 안쪽 것들만 맞는다. 바깥은 다음 바퀴에 맞는다.
        repeat(32) {
            var changed = false
            current = TAG.replace(current) { match ->
                changed = true
                val body = match.groupValues[2]
                when (match.groupValues[1]) {
                    "math" -> MathExpr.eval(body)?.let { MathExpr.format(it) } ?: "0"
                    "random number" -> randomNumber(body, random).also { last = it.toDoubleOrNull() }
                    "random word" -> body.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                        .let { if (it.isEmpty()) "" else it[random.nextInt(it.size)] }
                    "round" -> body.trim().toDoubleOrNull()?.roundToLong()?.toString() ?: body
                    "int" -> body.trim().toDoubleOrNull()?.toLong()?.toString() ?: body
                    "scramble" -> body.toList().shuffled(random).joinToString("")
                    else -> match.value
                }
            }
            if (!changed) return Result(current, last)
        }
        return Result(current, last)
    }

    /** `1-5` · `1.5-3` · `-3--1`. 두 끝이 정수면 정수, 아니면 소수 둘째 자리. */
    private fun randomNumber(body: String, random: Random): String {
        val trimmed = body.trim()
        // 음수를 허용하려면 첫 글자 뒤에서부터 '-' 를 찾아야 한다.
        val cut = trimmed.indexOf('-', startIndex = 1).takeIf { it > 0 } ?: return trimmed
        val low = trimmed.substring(0, cut).trim().toDoubleOrNull() ?: return trimmed
        val high = trimmed.substring(cut + 1).trim().toDoubleOrNull() ?: return trimmed
        val (a, b) = if (low <= high) low to high else high to low
        val bothInts = a == Math.floor(a) && b == Math.floor(b)
        return if (bothInts) {
            (a.toLong() + (random.nextDouble() * (b - a + 1)).toLong().coerceAtMost((b - a).toLong())).toString()
        } else {
            // 로캘을 고정한다 - 서버 로캘에 따라 1,50 이 되면 숫자 인자가 조용히 0 이 된다.
            String.format(java.util.Locale.ROOT, "%.2f", a + random.nextDouble() * (b - a))
        }
    }
}
