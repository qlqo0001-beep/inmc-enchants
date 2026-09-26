package com.inmc.enchants.util

import kr.inmc.core.util.TokenBag

/** 메시지 한 번 렌더링에 쓰는 토큰 주머니. 한글·영문 둘 다 받는다(다른 INMC 플러그인과 같은 관례). */
class Ph : TokenBag<Ph>() {

    override val aliases: Map<String, List<String>> get() = ALIASES

    fun player(name: String): Ph = put(PLAYER, name)
    fun enchant(name: String): Ph = put(ENCHANT, name)
    fun level(value: Int): Ph = put(LEVEL, value.toString())
    fun level(text: String): Ph = put(LEVEL, text)
    fun amount(value: Int): Ph = put(AMOUNT, value.toString())
    fun amount(text: String): Ph = put(AMOUNT, text)
    fun count(value: Int): Ph = put(COUNT, value.toString())
    fun group(name: String): Ph = put(GROUP, name)
    fun item(name: String): Ph = put(ITEM, name)
    fun value(text: String): Ph = put(VALUE, text)
    fun rate(value: Int): Ph = put(RATE, value.toString())
    fun seconds(value: Long): Ph = put(SECONDS, value.toString())
    fun passed(value: Int): Ph = put(PASSED, value.toString())
    fun failed(value: Int): Ph = put(FAILED, value.toString())
    fun observed(value: Int): Ph = put(OBSERVED, value.toString())
    fun skipped(value: Int): Ph = put(SKIPPED, value.toString())
    fun file(path: String): Ph = put(FILE, path)

    fun copy(): Ph = copyValuesInto(Ph())

    companion object {
        fun of(): Ph = Ph()

        const val PLAYER = "player"
        const val ENCHANT = "enchant"
        const val LEVEL = "level"
        const val AMOUNT = "amount"
        const val COUNT = "count"
        const val GROUP = "group"
        const val ITEM = "item"
        const val VALUE = "value"
        const val RATE = "rate"
        const val SECONDS = "seconds"
        const val PASSED = "passed"
        const val FAILED = "failed"
        const val OBSERVED = "observed"
        const val SKIPPED = "skipped"
        const val FILE = "file"

        private val ALIASES: Map<String, List<String>> = mapOf(
            PLAYER to listOf("{플레이어}", "{player}"),
            ENCHANT to listOf("{인첸트}", "{enchant}"),
            LEVEL to listOf("{레벨}", "{level}"),
            AMOUNT to listOf("{수량}", "{amount}"),
            COUNT to listOf("{개수}", "{count}"),
            GROUP to listOf("{등급}", "{group}"),
            ITEM to listOf("{아이템}", "{item}"),
            VALUE to listOf("{값}", "{value}"),
            RATE to listOf("{확률}", "{rate}"),
            SECONDS to listOf("{초}", "{seconds}"),
            PASSED to listOf("{통과}", "{passed}"),
            FAILED to listOf("{실패}", "{failed}"),
            OBSERVED to listOf("{확인}", "{observed}"),
            SKIPPED to listOf("{건너뜀}", "{skipped}"),
            FILE to listOf("{파일}", "{file}"),
        )
    }
}
