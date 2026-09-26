package com.inmc.enchants

import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.gui.AlchemistMenu
import com.inmc.enchants.gui.TinkererMenu
import com.inmc.enchants.item.ItemKind
import kr.inmc.core.gui.Paging
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 화면 슬롯 배치를 못박는다.
 *
 * 슬롯 충돌은 컴파일러가 잡지 못한다 — 두 버튼이 같은 칸에 그려지면 나중에 그린 쪽만 보이고,
 * **안 보이는 버튼의 클릭 핸들러는 그대로 남아** 엉뚱한 동작을 한다. 범위 밖 슬롯은 `Menu.set` 이
 * **조용히 무시**하므로 버튼이 그냥 안 그려진다.
 *
 * 이 플러그인의 화면은 슬롯을 상수가 아니라 `set(19, …)` 처럼 숫자로 적으므로, 소스를 클래스별로
 * 잘라 그 숫자들을 읽는다.
 */
class MenuLayoutTest {

    private val dir = File("src/main/kotlin/com/inmc/enchants/gui")

    /** 클래스 이름 → 그 클래스 본문에 숫자로 적힌 `set(N,` 슬롯들. */
    private val fixed: Map<String, List<Int>> by lazy {
        assertTrue(dir.isDirectory, "소스를 찾을 수 없습니다: ${dir.absolutePath}")
        val header = Regex("""^(?:private |internal )?class (\w+)""", RegexOption.MULTILINE)
        val slot = Regex("""\bset\((\d+),""")
        dir.listFiles { f -> f.extension == "kt" }!!.flatMap { file ->
            val text = file.readText()
            val starts = header.findAll(text).toList()
            starts.mapIndexed { i, m ->
                val body = text.substring(m.range.first, starts.getOrNull(i + 1)?.range?.first ?: text.length)
                m.groupValues[1] to slot.findAll(body).map { it.groupValues[1].toInt() }.toList()
            }
        }.toMap()
    }

    private fun body(name: String): String {
        val file = dir.listFiles()!!.first { it.readText().contains("class $name(") }
        val text = file.readText()
        val from = text.indexOf("class $name(")
        val next = Regex("""^(?:private |internal )?class \w+""", RegexOption.MULTILINE).find(text, from + 1)?.range?.first ?: text.length
        return text.substring(from, next)
    }

    @Test
    fun `모든 화면을 읽었다`() {
        // 파일을 옮기거나 클래스 선언 모양을 바꿔 이 테스트가 아무것도 안 보게 되는 것을 막는다.
        assertTrue(fixed.size >= 17, "화면 클래스를 " + fixed.size + "개밖에 못 읽었습니다: " + fixed.keys)
    }

    @Test
    fun `한 화면 안에서 고정 버튼끼리 겹치지 않는다`() {
        val clashes = fixed.mapNotNull { (name, slots) ->
            val dup = slots.groupBy { it }.filterValues { it.size > 1 }.keys
            if (dup.isEmpty()) null else "$name: $dup"
        }
        assertTrue(clashes.isEmpty(), "슬롯 충돌: $clashes")
    }

    @Test
    fun `고정 버튼이 뒤로·닫기 칸을 덮지 않고 창 안에 있다`() {
        // navigation() 이 모든 화면에 45·53 을 그린다.
        for ((name, slots) in fixed) {
            for (slot in slots) {
                assertTrue(slot in 0 until 54, "$name 의 $slot 이 창을 벗어납니다")
                assertTrue(slot != Paging.SLOT_BACK && slot != Paging.SLOT_CLOSE, "$name 의 $slot 이 뒤로·닫기 버튼을 덮습니다")
            }
        }
    }

    @Test
    fun `목록 화면의 버튼이 항목 칸을 덮지 않는다`() {
        // 0 부터 채우는 목록은 take(36) 으로 0..35 를 쓴다.
        for (name in listOf("LevelListMenu", "ConditionListMenu", "GroupListMenu", "EffectListMenu", "EventListMenu")) {
            assertTrue(body(name).contains("take(36)"), "$name 의 항목 수 상한이 바뀌었습니다")
            for (slot in fixed.getValue(name)) assertTrue(slot >= 36, "$name 의 $slot 이 항목 칸(0..35)을 덮습니다")
        }
        // 18 부터 채우는 목록은 take(27) 로 18..44 를 쓴다.
        // 내 아이템의 22 는 "손에 든 것이 없습니다" — 목록이 빌 때만 그린다.
        for ((name, allowed) in listOf("EnchantInfoMenu" to emptySet(), "HeldItemMenu" to setOf(22))) {
            assertTrue(body(name).contains("take(27)"), "$name 의 항목 수 상한이 바뀌었습니다")
            for (slot in fixed.getValue(name) - allowed) assertTrue(slot !in 18..44, "$name 의 $slot 이 항목 칸(18..44)을 덮습니다")
        }
        // 페이지가 있는 도감은 0..44 가 항목, 46·47 이 페이지 버튼이다.
        for (slot in fixed.getValue("CatalogMenu")) {
            assertTrue(slot >= Paging.PER_PAGE, "CatalogMenu 의 $slot 이 항목 칸을 덮습니다")
            assertTrue(slot != Paging.SLOT_PREV && slot != Paging.SLOT_NEXT, "CatalogMenu 의 $slot 이 페이지 버튼을 덮습니다")
        }
    }

    @Test
    fun `효과 줄 편집 화면이 가장 긴 효과의 값을 전부 보여준다`() {
        // 값 칸은 19..25 의 7칸이다. 넘치면 뒤쪽 값이 화면에 안 나와 원문으로만 고칠 수 있다.
        assertTrue(body("EffectLineMenu").contains("take(7)"), "값 칸 수가 바뀌었습니다")
        val longest = EffectSpecs.ALL.maxBy { it.args.size }
        assertTrue(longest.args.size <= 7, longest.name + " 의 값이 " + longest.args.size + "개라 7칸에 안 들어갑니다")
        for (slot in fixed.getValue("EffectLineMenu")) assertTrue(slot !in 19..25, "EffectLineMenu 의 $slot 이 값 칸을 덮습니다")
    }

    @Test
    fun `지급 화면이 모든 아이템 종류를 담는다`() {
        // 10 + i % 7 + (i / 7) * 9 — 7칸씩 세 줄(10..34)이다. 넘치면 뒤로 버튼 줄로 밀려난다.
        val slots = ItemKind.entries.indices.map { 10 + it % 7 + (it / 7) * 9 }
        assertEquals(slots.size, slots.toSet().size)
        for (slot in slots) assertTrue(slot in 10..34, "지급 버튼 $slot 이 격자(10..34)를 벗어납니다")
    }

    @Test
    fun `설정 화면의 항목이 한 화면에 들어간다`() {
        // 항목은 0 부터 차례로 그린다. 45 를 넘으면 뒤로·닫기 줄에 겹친다.
        val count = Regex("""^\s+(Toggle|Number|Line)\(""", RegexOption.MULTILINE).findAll(body("SettingsMenu")).count()
        assertTrue(count > 0, "설정 항목을 못 읽었습니다")
        assertTrue(count <= Paging.PER_PAGE, "설정 항목 $count 개가 45칸을 넘습니다")
    }

    @Test
    fun `연금술사의 넣는 칸과 결과 칸이 장식·버튼과 겹치지 않는다`() {
        val working = listOf(AlchemistMenu.LEFT, AlchemistMenu.RIGHT, AlchemistMenu.OUT)
        assertEquals(3, working.toSet().size)
        val edge = Regex("""listOf\(([\d, ]+)\)""").find(body("AlchemistMenu"))!!.groupValues[1].split(",").map { it.trim().toInt() }
        for (slot in working) {
            assertTrue(slot !in edge, "연금술사 $slot 이 장식 칸입니다")
            assertTrue(slot < Paging.PER_PAGE, "연금술사 $slot 이 아래 줄 버튼에 겹칩니다")
        }
    }

    @Test
    fun `땜장이의 넣는 칸이 버튼과 겹치지 않는다`() {
        val buttons = fixed.getValue("TinkererMenu") + Paging.SLOT_BACK + Paging.SLOT_CLOSE
        assertEquals(TinkererMenu.INPUT.size, TinkererMenu.INPUT.toSet().size)
        for (slot in TinkererMenu.INPUT) assertTrue(slot !in buttons, "땜장이 넣는 칸 $slot 이 버튼과 겹칩니다")
    }
}
