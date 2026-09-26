package com.inmc.enchants.engine

/**
 * 조건 한 줄. `식 : 결과` — AE 와 같은 문법이다.
 *
 * ```
 * %victim health% > 5 : %stop%
 * %player world% = my_world : %allow%
 * (%victim max health% > 20 AND %victim world% != dungeon) OR %victim world% = prison : %allow%
 * %player y% < 30 && %player health% > 10 : %chance%+10
 * ```
 *
 * **식은 적재 때 한 번 구조로 파싱하고, 변수는 실행 때 피연산자마다 치환한다.** 통째로 치환한 뒤에
 * 파싱하면 몹 이름에 `=` 나 `&&` 가 들어간 순간 식의 모양이 바뀐다 — 조용히 틀린 쪽으로.
 */
data class Condition(val raw: String, val expr: Expr, val outcome: Outcome) {

    sealed interface Outcome {
        /** 확률·쿨다운을 무시하고 발동. 뒤 조건은 보지 않는다. */
        data object Force : Outcome

        /** 참이면 발동을 허락하고 뒤 조건은 보지 않는다. **거짓이면 막는다** — 문지기다. */
        data object Allow : Outcome

        /** 참이면 다음 조건으로. **거짓이면 막는다.** */
        data object Continue : Outcome

        /** 참이면 막는다. */
        data object Stop : Outcome

        /** 참이면 확률을 더하거나 뺀다(퍼센트 포인트). */
        data class Chance(val delta: Double) : Outcome
    }

    /** 식의 구조. 잎은 변수 치환 전의 원문 피연산자를 든다. */
    sealed interface Expr {
        data class And(val parts: List<Expr>) : Expr
        data class Or(val parts: List<Expr>) : Expr
        data class Compare(val left: String, val op: Op, val right: String) : Expr

        /** 비교 없이 값 하나. `true` 면 참. */
        data class Truthy(val operand: String) : Expr
    }

    enum class Op(val symbol: String) {
        LE("<="), GE(">="), NE("!="), EQ("="), LT("<"), GT(">"),
        CONTAINS("contains"), REGEX("matchesregex"),
    }

    /** 이 조건 하나의 참·거짓. [resolve] 는 피연산자 원문 → 치환된 값. */
    fun test(resolve: (String) -> String): Boolean = eval(expr, resolve)

    /** 조건 목록 전체를 본 결론. */
    data class Decision(val activate: Boolean, val force: Boolean, val chanceDelta: Double) {
        companion object {
            val PROCEED = Decision(activate = true, force = false, chanceDelta = 0.0)
            val BLOCKED = Decision(activate = false, force = false, chanceDelta = 0.0)
        }
    }

    companion object {

        private val OUTCOME = Regex("(%force%|%allow%|%continue%|%stop%|%chance%\\s*([+-])\\s*(\\d+(?:\\.\\d+)?))\\s*$", RegexOption.IGNORE_CASE)

        /**
         * 읽는다. 결과를 못 알아보거나 식이 비었으면 null — **적재 때 거부해야** 관리자가 안다.
         * 결과 앞의 `:` 는 선택이다(AE 예제에 `... = 50 %allow%` 도 있다).
         */
        fun parse(raw: String): Condition? {
            val text = raw.trim()
            val match = OUTCOME.find(text) ?: return null
            val outcome = outcomeOf(match) ?: return null
            var body = text.substring(0, match.range.first).trim()
            // AE 문서 예제 하나가 `... > 10 = : %chance%+10` 처럼 꼬리에 `=` 를 달고 있다. 너그럽게.
            body = body.trimEnd(':', ' ').trimEnd()
            if (body.endsWith(" =")) body = body.dropLast(2).trimEnd()
            if (body.isEmpty()) return null
            val expr = parseExpr(body) ?: return null
            return Condition(text, expr, outcome)
        }

        private fun outcomeOf(match: MatchResult): Outcome? {
            val token = match.groupValues[1].lowercase()
            return when {
                token == "%force%" -> Outcome.Force
                token == "%allow%" -> Outcome.Allow
                token == "%continue%" -> Outcome.Continue
                token == "%stop%" -> Outcome.Stop
                token.startsWith("%chance%") -> {
                    val sign = if (match.groupValues[2] == "-") -1.0 else 1.0
                    val amount = match.groupValues[3].toDoubleOrNull() ?: return null
                    Outcome.Chance(sign * amount)
                }
                else -> null
            }
        }

        /**
         * 목록을 차례로 본다.
         *
         * | 결과 | 참 | 거짓 |
         * |---|---|---|
         * | `%force%` | 무조건 발동, 끝 | 다음 |
         * | `%allow%` | 발동 허락, 끝 | **막음** |
         * | `%continue%` | 다음 | **막음** |
         * | `%stop%` | **막음** | 다음 |
         * | `%chance%±x` | 확률 조정, 다음 | 다음 |
         */
        fun decide(conditions: List<Condition>, resolve: (String) -> String): Decision {
            var delta = 0.0
            for (condition in conditions) {
                val truth = condition.test(resolve)
                when (val outcome = condition.outcome) {
                    Outcome.Force -> if (truth) return Decision(activate = true, force = true, chanceDelta = delta)
                    Outcome.Allow -> return if (truth) Decision(true, false, delta) else Decision.BLOCKED
                    Outcome.Continue -> if (!truth) return Decision.BLOCKED
                    Outcome.Stop -> if (truth) return Decision.BLOCKED
                    is Outcome.Chance -> if (truth) delta += outcome.delta
                }
            }
            return Decision(activate = true, force = false, chanceDelta = delta)
        }

        // --- 식 파싱 ------------------------------------------------------------------

        private val OR_TOKENS = listOf(" || ", " OR ")
        private val AND_TOKENS = listOf(" && ", " AND ")

        fun parseExpr(text: String): Expr? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null

            splitTop(trimmed, OR_TOKENS)?.let { parts ->
                return Expr.Or(parts.map { parseExpr(it) ?: return null })
            }
            splitTop(trimmed, AND_TOKENS)?.let { parts ->
                return Expr.And(parts.map { parseExpr(it) ?: return null })
            }
            if (trimmed.startsWith("(") && matchingParen(trimmed, 0) == trimmed.length - 1) {
                return parseExpr(trimmed.substring(1, trimmed.length - 1))
            }
            return comparison(trimmed)
        }

        /** 괄호 밖에서만 자른다. 한 번도 안 잘리면 null. */
        private fun splitTop(text: String, tokens: List<String>): List<String>? {
            val parts = mutableListOf<String>()
            var depth = 0
            var last = 0
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> depth = (depth - 1).coerceAtLeast(0)
                }
                if (depth == 0) {
                    val token = tokens.firstOrNull { text.startsWith(it, i) }
                    if (token != null) {
                        parts += text.substring(last, i)
                        i += token.length
                        last = i
                        continue
                    }
                }
                i++
            }
            if (parts.isEmpty()) return null
            parts += text.substring(last)
            return parts.map { it.trim() }.takeIf { list -> list.none { it.isEmpty() } }
        }

        private fun matchingParen(text: String, open: Int): Int {
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) return i }
                }
            }
            return -1
        }

        /** 공백으로 둘러싼 연산자를 먼저 찾는다. 없으면 기호 연산자를 붙여 쓴 경우를 본다. */
        private fun comparison(text: String): Expr {
            for (op in listOf(Op.CONTAINS, Op.REGEX, Op.LE, Op.GE, Op.NE, Op.EQ, Op.LT, Op.GT)) {
                val spaced = " " + op.symbol + " "
                val at = text.indexOf(spaced)
                if (at > 0) {
                    return Expr.Compare(text.substring(0, at).trim(), op, text.substring(at + spaced.length).trim())
                }
            }
            for (op in listOf(Op.LE, Op.GE, Op.NE, Op.EQ, Op.LT, Op.GT)) {
                val at = indexOutsideVariables(text, op.symbol)
                if (at > 0) {
                    return Expr.Compare(text.substring(0, at).trim(), op, text.substring(at + op.symbol.length).trim())
                }
            }
            return Expr.Truthy(text)
        }

        /** `%...%` 안의 기호는 연산자가 아니다. */
        private fun indexOutsideVariables(text: String, symbol: String): Int {
            var inside = false
            var i = 0
            while (i < text.length) {
                if (text[i] == '%') inside = !inside
                if (!inside && text.startsWith(symbol, i)) return i
                i++
            }
            return -1
        }

        // --- 평가 --------------------------------------------------------------------

        private fun eval(expr: Expr, resolve: (String) -> String): Boolean = when (expr) {
            is Expr.And -> expr.parts.all { eval(it, resolve) }
            is Expr.Or -> expr.parts.any { eval(it, resolve) }
            is Expr.Truthy -> resolve(expr.operand).trim().equals("true", ignoreCase = true)
            is Expr.Compare -> compare(resolve(expr.left).trim(), expr.op, resolve(expr.right).trim())
        }

        /** 둘 다 숫자면 숫자로, 아니면 문자열로(대소문자 무시) 비교한다. */
        fun compare(left: String, op: Op, right: String): Boolean {
            val a = left.toDoubleOrNull()
            val b = right.toDoubleOrNull()
            if (a != null && b != null) {
                return when (op) {
                    Op.EQ -> a == b
                    Op.NE -> a != b
                    Op.LT -> a < b
                    Op.LE -> a <= b
                    Op.GT -> a > b
                    Op.GE -> a >= b
                    Op.CONTAINS -> left.contains(right, ignoreCase = true)
                    Op.REGEX -> runCatching { Regex(right).containsMatchIn(left) }.getOrDefault(false)
                }
            }
            return when (op) {
                Op.EQ -> left.equals(right, ignoreCase = true)
                Op.NE -> !left.equals(right, ignoreCase = true)
                Op.CONTAINS -> left.contains(right, ignoreCase = true)
                Op.REGEX -> runCatching { Regex(right).containsMatchIn(left) }.getOrDefault(false)
                // 숫자가 아닌데 크기 비교면 거짓이다. 조용히 참이 되는 쪽보다 낫다.
                Op.LT, Op.LE, Op.GT, Op.GE -> false
            }
        }
    }
}
