package com.inmc.enchants.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * `<math>` 안의 식을 계산한다. 변수는 이미 숫자로 바뀐 뒤에 온다.
 *
 * 지원: `+ - * / % ^`, 괄호, 단항 `-`, 함수 `min max abs sqrt floor ceil round`.
 * **스크립트 엔진을 쓰지 않는다** — 설정 파일의 문자열이 곧 코드가 되면 관리자 실수 하나가
 * 서버에서 임의 코드를 돌린다. 여기서 못 읽는 식은 예외 대신 null 이다.
 */
object MathExpr {

    fun eval(text: String): Double? = try {
        Parser(text).parse()
    } catch (_: IllegalArgumentException) {
        null
    }

    /** 정수로 떨어지면 소수점 없이. 설정 문자열로 되돌릴 때 쓴다. */
    fun format(value: Double): String =
        if (value == floor(value) && !value.isInfinite() && abs(value) < 1e15) value.toLong().toString() else value.toString()

    private class Parser(private val src: String) {
        private var pos = 0

        fun parse(): Double {
            val value = expression()
            skipSpaces()
            if (pos != src.length) fail()
            if (value.isNaN()) fail()
            return value
        }

        private fun expression(): Double {
            var value = term()
            while (true) {
                skipSpaces()
                value = when (peek()) {
                    '+' -> { pos++; value + term() }
                    '-' -> { pos++; value - term() }
                    else -> return value
                }
            }
        }

        private fun term(): Double {
            var value = power()
            while (true) {
                skipSpaces()
                value = when (peek()) {
                    '*' -> { pos++; value * power() }
                    '/' -> { pos++; val d = power(); if (d == 0.0) 0.0 else value / d }
                    '%' -> { pos++; val d = power(); if (d == 0.0) 0.0 else value % d }
                    else -> return value
                }
            }
        }

        private fun power(): Double {
            val base = unary()
            skipSpaces()
            if (peek() == '^') {
                pos++
                return base.pow(power())
            }
            return base
        }

        private fun unary(): Double {
            skipSpaces()
            return when (peek()) {
                '-' -> { pos++; -unary() }
                '+' -> { pos++; unary() }
                else -> atom()
            }
        }

        private fun atom(): Double {
            skipSpaces()
            val c = peek() ?: fail()
            if (c == '(') {
                pos++
                val value = expression()
                expect(')')
                return value
            }
            if (c.isDigit() || c == '.') return number()
            if (c.isLetter()) return function()
            fail()
        }

        private fun number(): Double {
            val start = pos
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '.')) pos++
            return src.substring(start, pos).toDoubleOrNull() ?: fail()
        }

        private fun function(): Double {
            val start = pos
            while (pos < src.length && src[pos].isLetter()) pos++
            val name = src.substring(start, pos).lowercase()
            expect('(')
            val args = mutableListOf(expression())
            while (true) {
                skipSpaces()
                if (peek() == ',') { pos++; args += expression() } else break
            }
            expect(')')
            return when (name) {
                "min" -> args.reduce { a, b -> min(a, b) }
                "max" -> args.reduce { a, b -> max(a, b) }
                "abs" -> abs(args[0])
                "sqrt" -> sqrt(args[0])
                "floor" -> floor(args[0])
                "ceil" -> ceil(args[0])
                "round" -> round(args[0])
                else -> fail()
            }
        }

        private fun expect(c: Char) {
            skipSpaces()
            if (peek() != c) fail()
            pos++
        }

        private fun peek(): Char? = if (pos < src.length) src[pos] else null

        private fun skipSpaces() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun fail(): Nothing = throw IllegalArgumentException("식을 읽을 수 없습니다: $src")
    }
}
