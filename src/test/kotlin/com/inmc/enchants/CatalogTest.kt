package com.inmc.enchants

import com.inmc.enchants.config.EnchantConfig
import com.inmc.enchants.config.Messages
import com.inmc.enchants.effect.Effects
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.enchant.Group
import com.inmc.enchants.engine.ArgType
import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.engine.Names
import com.inmc.enchants.engine.Trigger
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 배포 파일과 코드가 서로 맞는지. **코드가 읽는 방식 그대로** 읽어 본다 — 한 줄만 어긋나도 그
 * 항목이 통째로 사라지는데 오류는 안 나는 종류라 여기서 잡아야 한다.
 */
class CatalogTest {

    private fun resource(path: String): YamlConfiguration {
        val stream = javaClass.classLoader.getResourceAsStream(path)
        assertNotNull(stream, "리소스를 찾을 수 없습니다: $path")
        return stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
    }

    @Test
    fun `모든 효과 모양에 실행 코드가 있다`() {
        val missing = EffectSpecs.ALL.map { it.name }.filterNot { it in Effects().names }
        assertTrue(missing.isEmpty(), "실행 코드 없는 효과: $missing - 적재는 되는데 발동하면 아무 일도 안 일어난다")
        val orphan = Effects().names.filterNot { EffectSpecs.of(it) != null }
        assertTrue(orphan.isEmpty(), "모양 없는 실행 코드: $orphan - 적재 때 거부되어 쓸 수 없다")
    }

    @Test
    fun `배포 인첸트가 전부 문제없이 읽힌다`() {
        val config = resource("enchantments.yml")
        val problems = mutableListOf<String>()
        val defs = config.getKeys(false).mapNotNull { EnchantDefinition.load(it, config.getConfigurationSection(it)!!, problems) }
        assertTrue(problems.isEmpty(), "배포 인첸트 문제:\n" + problems.joinToString("\n"))
        assertEquals(config.getKeys(false).size, defs.size)
    }

    @Test
    fun `배포 인첸트의 등급·발동 조건·효과 인자가 올바르다`() {
        val config = resource("enchantments.yml")
        val groups = resource("groups.yml").getConfigurationSection("groups")!!.getKeys(false)
        val bad = mutableListOf<String>()
        for (key in config.getKeys(false)) {
            val def = EnchantDefinition.load(key, config.getConfigurationSection(key)!!, mutableListOf()) ?: continue
            if (def.group !in groups) bad += "${def.id}: 모르는 등급 ${def.group}"
            if (def.triggers.isEmpty()) bad += "${def.id}: 발동 조건이 없다"
            if (def.applies.isEmpty()) bad += "${def.id}: 붙을 아이템(applies)이 없다"
            for ((level, lvl) in def.levels) for (line in lvl.effects) {
                val spec = EffectSpecs.of(line.effect)!!
                val where = "${def.id} $level: ${line.raw}"
                if (line.args.size < spec.requiredArgs) bad += "$where → 인자 ${spec.requiredArgs}개 필요"
                for ((index, arg) in line.args.withIndex()) {
                    val argSpec = spec.args.getOrNull(index) ?: run { bad += "$where → 인자가 너무 많다"; null } ?: continue
                    if (arg.contains('%') || arg.contains('<')) continue
                    when (argSpec.type) {
                        ArgType.NUMBER, ArgType.INT -> if (arg.isNotBlank() && arg.toDoubleOrNull() == null) bad += "$where → '$arg' 는 숫자가 아니다"
                        ArgType.POTION -> if (Names.potion(arg) == null) bad += "$where → 모르는 물약 '$arg'"
                        ArgType.CHOICE -> if (arg.isNotBlank() && argSpec.choices.none { it.equals(arg, true) }) bad += "$where → '$arg' 는 보기에 없다"
                        ArgType.BOOL -> if (arg.isNotBlank() && arg.lowercase() !in setOf("true", "false")) bad += "$where → '$arg' 는 참/거짓이 아니다"
                        else -> Unit
                    }
                }
                // 이 효과가 뜻을 갖는 발동 조건이 정해져 있는데 하나도 안 겹치면 영영 아무 일도 안 한다.
                if (spec.triggers.isNotEmpty() && def.triggers.none { it in spec.triggers || it == Trigger.KILL }) {
                    bad += "$where → ${spec.name} 은(는) ${spec.triggers} 에서만 뜻이 있다"
                }
            }
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun `배포 메시지와 코드 기본값의 키가 정확히 같다`() {
        val file = resource("messages.yml").getKeys(false)
        val code = Messages.DEFAULTS.keys
        assertEquals(code.sorted(), file.sorted(), "파일에만 있거나 코드에만 있는 키가 있다")
        for (key in listOf("prompt-enter", "prompt-cancelled", "prompt-timeout", "prompt-invalid-number")) {
            assertTrue(key in code, "core ChatPrompt 가 요구하는 키 $key 가 없다 - 프롬프트가 조용히 무음이 된다")
        }
    }

    @Test
    fun `배포 설정을 읽으면 기본값과 같은 뜻이다`() {
        val loaded = EnchantConfig.load(resource("config.yml"))
        val defaults = EnchantConfig()
        // 목록·묶음은 파일에만 있다. 나머지 스칼라는 파일과 코드 기본값이 같아야 키를 지워도 동작이 안 바뀐다.
        assertEquals(defaults.copy(appliesGroups = loaded.appliesGroups, listVariables = loaded.listVariables), loaded)
        assertTrue(loaded.listVariables.containsKey("%sword types%"))
    }

    @Test
    fun `배포 등급은 순서가 겹치지 않는다`() {
        val section = resource("groups.yml").getConfigurationSection("groups")!!
        val groups = section.getKeys(false).mapIndexed { i, key -> Group.load(key, section.getConfigurationSection(key)!!, i + 1) }
        assertEquals(groups.size, groups.map { it.order }.toSet().size, "등급 순서가 겹치면 로어 정렬이 흔들린다")
        assertTrue(groups.all { it.enchanterPrice.isNotBlank() }, "인챈터 가격이 빈 등급이 있다")
    }
}
