package com.inmc.enchants.item

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.item.LoreRenderer.Companion.roman
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.core.store.YamlFileStore
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack

/**
 * 강화 스크롤이 올리는 인첸트 — 우리 인첸트 또는 바닐라 인첸트.
 *
 * [id] 는 아이템(PDC [Keys.SCROLL_ENCHANT])·`scrolls.yml` 에 적는 글자다. 우리 것은 `lifesteal`, 바닐라는 `minecraft:sharpness` —
 * 우리 id 에는 콜론이 없으므로([EnchantDefinition.ID]) 콜론으로 갈린다.
 */
sealed interface ScrollEnchant {
    val id: String

    data class Custom(val def: EnchantDefinition) : ScrollEnchant {
        override val id: String get() = def.id
    }

    data class Vanilla(val enchantment: Enchantment) : ScrollEnchant {
        override val id: String get() = enchantment.key.toString()
    }
}

/**
 * `scrolls.yml` — 강화 스크롤의 **금지 목록**과 **바닐라 최대 레벨**. 관리 → 강화 스크롤 화면에서 고친다.
 *
 * 금지 목록의 대상은 셋이다. 콜론이 있으면 커스텀아이템, 없으면 인첸트 "붙는 곳"과 같은 판정([Applicability]).
 *
 * | 대상 | 예 |
 * |---|---|
 * | 재질 | `DIAMOND_SWORD` |
 * | 종류 묶음 | `ALL_SWORD` · 설정의 `applies-groups` 이름 |
 * | 커스텀아이템 | `inmc:mythic_blade` (core [kr.inmc.core.integration.CustomItemHook.identifyAll]) |
 *
 * 금지 목록은 **목록**으로 적는다(`- target: …`). 대상을 열쇠로 쓰면 커스텀아이템 id 의 점을 YAML 이 경로로 읽는다.
 */
class ScrollRules(private val e: Enchants) : YamlFileStore(
    io = e.io,
    path = listOf(FILE),
    header = HEADER,
    what = "강화 스크롤 규칙",
) {

    /** 대상 → 금지 인첸트 id(넣은 순서). 메인 스레드에서만 바꾼다. */
    @Volatile
    private var blacklist: Map<String, Set<String>> = emptyMap()

    /** 바닐라 인첸트 id → 스크롤로 올릴 수 있는 최대 레벨. 없으면 바닐라의 최대 레벨. */
    @Volatile
    private var vanillaMax: Map<String, Int> = emptyMap()

    override fun read(config: YamlConfiguration) {
        blacklist = config.getMapList("blacklist").mapNotNull { entry ->
            val target = entry["target"]?.toString()?.let { normalize(it) }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val enchants = (entry["enchants"] as? List<*>).orEmpty().mapNotNull { it?.toString()?.trim()?.lowercase()?.takeIf(String::isNotEmpty) }
            target to enchants.toCollection(LinkedHashSet())
        }.toMap(LinkedHashMap<String, Set<String>>())
        vanillaMax = config.getConfigurationSection("vanilla-max-level")?.let { section ->
            section.getKeys(false).associate { it.lowercase() to section.getInt(it) }.filterValues { it > 0 }
        }.orEmpty()
    }

    override fun write(config: YamlConfiguration) {
        config.set("blacklist", blacklist.map { (target, enchants) -> linkedMapOf("target" to target, "enchants" to enchants.toList()) })
        config.createSection("vanilla-max-level", vanillaMax)
    }

    // --- 인첸트 ---------------------------------------------------------------------------

    /** 아이템·파일에 적힌 id → 인첸트. 지워진 인첸트면 null. */
    fun resolve(id: String?): ScrollEnchant? {
        val raw = id?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (':' !in raw) return e.registry.get(raw)?.let(ScrollEnchant::Custom)
        val key = NamespacedKey.fromString(raw) ?: return null
        return runCatching { vanillaRegistry().get(key) }.getOrNull()?.let(ScrollEnchant::Vanilla)
    }

    /** 고르기 화면의 보기 — 우리 인첸트 다음 바닐라. */
    fun all(): List<ScrollEnchant> =
        e.registry.all().map(ScrollEnchant::Custom) + vanilla().map(ScrollEnchant::Vanilla)

    fun vanilla(): List<Enchantment> = vanillaRegistry().toList().sortedBy { it.key.toString() }

    /** 메시지·아이템 이름에 쓰는 "이름 레벨". 바닐라는 클라이언트 언어로 나온다(`<lang:…>`). */
    fun label(target: ScrollEnchant, level: Int = 0): String = when (target) {
        is ScrollEnchant.Custom -> if (level > 0) e.display(target.def, level) else e.lore.name(target.def)
        is ScrollEnchant.Vanilla -> {
            val key = target.enchantment.key
            "<lang:enchantment." + key.namespace + "." + key.key + ">" + (if (level > 0) " " + roman(level) else "")
        }
    }

    fun level(stack: ItemStack, target: ScrollEnchant): Int = when (target) {
        is ScrollEnchant.Custom -> EnchantStorage.level(stack, target.def.id)
        is ScrollEnchant.Vanilla -> stack.getEnchantmentLevel(target.enchantment)
    }

    fun maxLevel(target: ScrollEnchant): Int = when (target) {
        is ScrollEnchant.Custom -> target.def.maxLevel.coerceAtLeast(1)
        is ScrollEnchant.Vanilla -> vanillaMax[target.id] ?: target.enchantment.maxLevel
    }

    /** 바닐라 인첸트의 스크롤 최대 레벨을 바꾼다. null 이면 바닐라 값으로 되돌린다. */
    fun setVanillaMax(enchantment: Enchantment, level: Int?) {
        val id = ScrollEnchant.Vanilla(enchantment).id
        vanillaMax = if (level == null || level <= 0) vanillaMax - id else vanillaMax + (id to level)
        markDirty()
    }

    fun vanillaMaxOverride(enchantment: Enchantment): Int? = vanillaMax[ScrollEnchant.Vanilla(enchantment).id]

    // --- 금지 목록 -------------------------------------------------------------------------

    fun rules(): Map<String, Set<String>> = blacklist

    /** [stack] 에 [target] 을 스크롤로 붙이거나 올리면 안 되는가. */
    fun blocked(stack: ItemStack, target: ScrollEnchant): Boolean {
        val id = target.id
        return blacklist.any { (key, enchants) -> id in enchants && matches(key, stack) }
    }

    fun matches(key: String, stack: ItemStack): Boolean {
        if (':' in key) return e.customItems.identifyAll(stack).any { it.serialize() == key }
        return Applicability.matches(key, stack.type.name, e.config.appliesGroups)
    }

    /** 규칙을 만들거나(없으면) 그대로 둔다. 정리된 대상 글자를 돌려준다. */
    fun addRule(target: String): String {
        val key = normalize(target)
        if (key.isNotEmpty() && key !in blacklist) {
            blacklist = LinkedHashMap(blacklist).also { it[key] = emptySet() }
            markDirty()
        }
        return key
    }

    /** 규칙의 인첸트 하나를 켜고 끈다. */
    fun toggle(target: String, enchantId: String) {
        val now = blacklist[target] ?: return
        val next = if (enchantId in now) now - enchantId else LinkedHashSet(now).also { it += enchantId }
        blacklist = LinkedHashMap(blacklist).also { it[target] = next }
        markDirty()
    }

    fun removeRule(target: String) {
        if (target !in blacklist) return
        blacklist = LinkedHashMap(blacklist).also { it.remove(target) }
        markDirty()
    }

    private fun vanillaRegistry() = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)

    companion object {
        const val FILE = "scrolls.yml"

        /** 재질·종류 묶음은 대문자로, 커스텀아이템(콜론)은 그대로. */
        fun normalize(target: String): String {
            val raw = target.trim()
            return if (':' in raw) raw else raw.uppercase()
        }

        private val HEADER = """
            ============================================================
             inmc-enchants 강화 스크롤 규칙
             /인첸트 관리 → 강화 스크롤 에서 고칩니다(화면에서 고치면 이 파일을 통째로 다시 씁니다).

             blacklist            스크롤로 붙이거나 올리면 안 되는 것
               - target           대상. 재질(DIAMOND_SWORD) · 종류 묶음(ALL_SWORD, 설정의 applies-groups)
                                  · 커스텀아이템(inmc:<아이템 id>)
                 enchants         금지 인첸트. 우리 인첸트는 id(lifesteal), 바닐라는 minecraft:sharpness
             vanilla-max-level    바닐라 인첸트를 스크롤로 올릴 수 있는 최대 레벨(없으면 바닐라 최대)
            ============================================================
        """.trimIndent() + "\n"
    }
}
