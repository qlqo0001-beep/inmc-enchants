package com.inmc.enchants.engine

/**
 * 효과 한 줄. AE 와 같은 문법이다.
 *
 * ```
 * POTION:SLOW_DIGGING:0:60 @Attacker
 * BREAK_BLOCK @Trench{radius=3}
 * DECREASE_DAMAGE:40 @Attacker ~DEFENSE
 * TELEPORT location=~0|0|-8
 * INCREASE_DAMAGE:<random number>1-5</random number> <chance>50</chance>
 * ADD_HEALTH:5 <condition>%victim health% < 10 : %allow%</condition>
 * ```
 *
 * 인자는 **원문 그대로** 들고 있다. 변수와 함수는 발동할 때마다 풀어야 하기 때문이다.
 */
data class EffectLine(
    val raw: String,
    val effect: String,
    val args: List<String>,
    val targets: List<TargetSpec>,
    val pointers: List<Pointer> = emptyList(),
    /** `location=` 의 값. `~0|0|-8`(상대) · `10|64|10`(절대) · `%hit location%`. */
    val location: String? = null,
    /** `<chance>` 안의 원문. */
    val chance: String? = null,
    /** `<condition>` 안의 조건. */
    val condition: Condition? = null,
) {

    /** `~ATTACK` · `~!DEFENSE`. 인첸트의 발동 조건 중 **어떤 것일 때만** 이 줄을 쓸지. */
    data class Pointer(val trigger: Trigger, val negate: Boolean) {
        fun serialize(): String = (if (negate) "~!" else "~") + trigger.name
    }

    fun allows(trigger: Trigger): Boolean {
        if (pointers.isEmpty()) return true
        if (pointers.any { it.negate && it.trigger == trigger }) return false
        val positives = pointers.filter { !it.negate }
        return positives.isEmpty() || positives.any { it.trigger == trigger }
    }

    /** 설정 파일에 쓸 모양. 편집 화면이 바꾼 줄을 저장할 때 쓴다. */
    fun serialize(): String = buildString {
        append(effect)
        for (arg in args) append(':').append(arg)
        for (target in targets) append(' ').append(target.serialize())
        for (pointer in pointers) append(' ').append(pointer.serialize())
        location?.let { append(" location=").append(it) }
        chance?.let { append(" <chance>").append(it).append("</chance>") }
        condition?.let { append(" <condition>").append(it.raw).append("</condition>") }
    }

    /** 파싱 결과. 실패면 [error] 에 이유가 있다 — 적재 로그와 편집 화면이 그대로 보여준다. */
    data class Parsed(val line: EffectLine?, val error: String?)

    companion object {

        private val CHANCE_TAG = Regex("<chance>(.*?)</chance>")
        private val CONDITION_TAG = Regex("<condition>(.*?)</condition>")
        private val LOCATION_TAIL = Regex("\\s+location=(%[^%]+%|\\S+)\\s*$")
        private val NAME = Regex("^[A-Za-z_]+$")

        /**
         * 읽는다.
         *
         * @param argCount 효과 이름 → (인자 칸 수, 마지막 칸이 나머지 전부를 먹는가). 모르는 효과면 null.
         *   `MESSAGE:시간: 5초` 처럼 글자에 `:` 가 들어가는 효과를 바르게 자르려면 칸 수를 알아야 한다.
         */
        fun parse(raw: String, argCount: (String) -> Pair<Int, Boolean>?): Parsed {
            var text = raw.trim()
            if (text.isEmpty()) return Parsed(null, "빈 줄")

            var chance: String? = null
            CHANCE_TAG.find(text)?.let {
                chance = it.groupValues[1].trim()
                text = text.removeRange(it.range).trim()
            }
            var condition: Condition? = null
            CONDITION_TAG.find(text)?.let {
                condition = Condition.parse(it.groupValues[1])
                    ?: return Parsed(null, "줄 조건을 읽을 수 없습니다: " + it.groupValues[1])
                text = text.removeRange(it.range).trim()
            }

            // 꼬리에서 대상·포인터·위치를 떼어 낸다. 첫 번째로 안 맞는 토큰에서 멈춘다 —
            // 메시지 글자 안의 '@' 를 대상으로 먹으면 안 되기 때문이다.
            val targets = ArrayDeque<TargetSpec>()
            val pointers = ArrayDeque<Pointer>()
            var location: String? = null
            while (true) {
                val loc = LOCATION_TAIL.find(" $text")
                if (loc != null) {
                    location = loc.groupValues[1]
                    text = (" $text").removeRange(loc.range).trim()
                    continue
                }
                val cut = text.lastIndexOf(' ')
                if (cut < 0) break
                val token = text.substring(cut + 1)
                when {
                    token.startsWith("@") -> {
                        val target = TargetSpec.parse(token) ?: return Parsed(null, "모르는 대상: $token")
                        targets.addFirst(target)
                    }
                    token.startsWith("~") -> {
                        val negate = token.startsWith("~!")
                        val name = token.removePrefix("~!").removePrefix("~")
                        val trigger = Trigger.of(name) ?: return Parsed(null, "모르는 포인터: $token")
                        pointers.addFirst(Pointer(trigger, negate))
                    }
                    else -> break
                }
                text = text.substring(0, cut).trim()
            }

            val name = text.substringBefore(':').trim()
            if (!NAME.matches(name)) return Parsed(null, "효과 이름이 아닙니다: $name")
            val effect = name.uppercase()
            val shape = argCount(effect) ?: return Parsed(null, "모르는 효과: $effect")
            val rest = if (text.contains(':')) text.substringAfter(':') else ""
            val args = splitArgs(rest, shape.first, shape.second)

            return Parsed(
                EffectLine(
                    raw = raw.trim(),
                    effect = effect,
                    args = args,
                    targets = targets.toList(),
                    pointers = pointers.toList(),
                    location = location,
                    chance = chance,
                    condition = condition,
                ),
                null,
            )
        }

        /**
         * `:` 로 자르되 `<...>` 함수와 `%...%` 변수 안은 자르지 않는다.
         * [restLast] 면 [count] 번째 칸이 나머지를 전부 먹는다.
         */
        fun splitArgs(text: String, count: Int, restLast: Boolean): List<String> {
            if (text.isEmpty()) return emptyList()
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var angle = 0
            var percent = false
            for (ch in text) {
                if (restLast && parts.size == count - 1) {
                    current.append(ch)
                    continue
                }
                when (ch) {
                    '<' -> angle++
                    '>' -> angle = (angle - 1).coerceAtLeast(0)
                    '%' -> percent = !percent
                }
                if (ch == ':' && angle == 0 && !percent) {
                    parts += current.toString().trim()
                    current.clear()
                } else {
                    current.append(ch)
                }
            }
            parts += if (restLast && parts.size == count - 1) current.toString().trimStart() else current.toString().trim()
            return parts
        }
    }
}
