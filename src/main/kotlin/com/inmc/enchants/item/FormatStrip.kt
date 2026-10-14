package com.inmc.enchants.item

import com.inmc.enchants.config.EnchantConfig
import kr.inmc.core.util.Text

/**
 * 아이템 이름·로어의 장식(굵음·밑줄·기울임·취소선·난독화)을 뺀다.
 *
 * 개인 폰트(리소스팩)는 바닐라처럼 굵음·밑줄을 그리지 못해 글자가 뭉개진다. `config.yml` 의
 * `formatting.strip-*` 가 true인 장식의 태그를 렌더 직전에 지운다. 레거시 색코드(`&l`·`&n`·`&m`·`&o`·`&k`)는
 * core `Text.legacyToMiniMessage` 로 MiniMessage 로 먼저 바꾼 뒤 지우니 둘 다 걸린다.
 *
 * 색·그라데이션·자리표시자(`{color}`·`%…%`)는 건드리지 않고, 채팅 메시지·GUI 화면도 건드리지 않는다 —
 * **아이템 이름·로어만**이다.
 */
object FormatStrip {

    private fun tags(vararg names: String): Regex =
        Regex("(?<!\\\\)<[!/]?(?:${names.joinToString("|")})(?::[^<>]*)?>", RegexOption.IGNORE_CASE)

    private val BOLD = tags("bold", "b")
    private val UNDERLINE = tags("underlined", "u")
    private val ITALIC = tags("italic", "i", "em")
    private val STRIKETHROUGH = tags("strikethrough", "st")
    private val OBFUSCATED = tags("obfuscated", "obf")

    /**
     * 켜진 장식만 뺀다. 전부 꺼져 있으면 입력을 그대로(같은 객체) 돌려준다 — 기본 동작은 1바이트도 안 바뀐다.
     */
    fun apply(raw: String, config: EnchantConfig): String {
        if (!config.stripBold && !config.stripUnderline && !config.stripItalic && !config.stripStrikethrough && !config.stripObfuscated) return raw
        var text = Text.legacyToMiniMessage(raw)
        if (config.stripBold) text = BOLD.replace(text, "")
        if (config.stripUnderline) text = UNDERLINE.replace(text, "")
        if (config.stripItalic) text = ITALIC.replace(text, "")
        if (config.stripStrikethrough) text = STRIKETHROUGH.replace(text, "")
        if (config.stripObfuscated) text = OBFUSCATED.replace(text, "")
        return text
    }
}
