package com.inmc.enchants

import com.inmc.enchants.config.EnchantConfig
import com.inmc.enchants.item.FormatStrip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `formatting.strip-*` — 켜진 장식만 빠지고, 꺼진 장식·색·자리표시자는 그대로.
 */
class FormatStripTest {

    @Test
    fun `전부 꺼져 있으면 입력을 그대로 돌려준다`() {
        val raw = "{color}<bold><u>{name} {level}"
        assertSame(raw, FormatStrip.apply(raw, EnchantConfig()))
    }

    @Test
    fun `굵음만 뺀다`() {
        val config = EnchantConfig(stripBold = true)
        assertEquals("{name} {level}", FormatStrip.apply("<bold>{name} {level}", config))
        assertEquals("검", FormatStrip.apply("<bold>검</bold>", config))
        assertEquals("검", FormatStrip.apply("&l검", config))
        assertEquals("검", FormatStrip.apply("<b>검</b>", config))
        // 꺼진 장식은 손대지 않는다
        assertEquals("<u>검</u>", FormatStrip.apply("<u>검</u>", config))
        assertEquals("<red>검", FormatStrip.apply("<red>검", config))
    }

    @Test
    fun `밑줄만 뺀다`() {
        val config = EnchantConfig(stripUnderline = true)
        assertEquals("검", FormatStrip.apply("<u>검</u>", config))
        assertEquals("검", FormatStrip.apply("<underlined>검</underlined>", config))
        assertEquals("검", FormatStrip.apply("&n검", config))
        assertEquals("<bold>검</bold>", FormatStrip.apply("<bold>검</bold>", config))
    }

    @Test
    fun `기울임 취소선 난독화를 뺀다`() {
        assertEquals("검", FormatStrip.apply("<italic>검</italic>", EnchantConfig(stripItalic = true)))
        assertEquals("검", FormatStrip.apply("<i>검</i>", EnchantConfig(stripItalic = true)))
        assertEquals("검", FormatStrip.apply("&o검", EnchantConfig(stripItalic = true)))
        assertEquals("검", FormatStrip.apply("<strikethrough>검</strikethrough>", EnchantConfig(stripStrikethrough = true)))
        assertEquals("검", FormatStrip.apply("<st>검</st>", EnchantConfig(stripStrikethrough = true)))
        assertEquals("검", FormatStrip.apply("&m검", EnchantConfig(stripStrikethrough = true)))
        assertEquals("검", FormatStrip.apply("<obfuscated>검</obfuscated>", EnchantConfig(stripObfuscated = true)))
        assertEquals("검", FormatStrip.apply("<obf>검</obf>", EnchantConfig(stripObfuscated = true)))
        assertEquals("검", FormatStrip.apply("&k검", EnchantConfig(stripObfuscated = true)))
    }

    @Test
    fun `색과 자리표시자는 살린다`() {
        val config = EnchantConfig(stripBold = true, stripUnderline = true)
        assertEquals("<red>검", FormatStrip.apply("<red><bold>검", config))
        assertEquals("{color}{group}", FormatStrip.apply("{color}<bold>{group}", config))
        assertEquals("<gradient:#b980ff:#6ec6ff>검", FormatStrip.apply("<gradient:#b980ff:#6ec6ff><bold>검", config))
    }

    @Test
    fun `대소문자와 속성형도 뺀다`() {
        val config = EnchantConfig(stripBold = true)
        assertEquals("검", FormatStrip.apply("<BOLD>검</BOLD>", config))
        assertEquals("검", FormatStrip.apply("<bold:true>검", config))
    }

    @Test
    fun `부여서 기본 이름에서 굵음밑줄이 빠진다`() {
        val config = EnchantConfig(stripBold = true, stripUnderline = true)
        assertEquals("{color}{name} {level}", FormatStrip.apply(EnchantConfig().bookName, config))
    }
}
