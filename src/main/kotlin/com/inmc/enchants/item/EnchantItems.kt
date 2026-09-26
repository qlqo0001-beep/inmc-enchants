package com.inmc.enchants.item

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.enchant.Group
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.Random

/** 이 플러그인이 만드는 아이템. PDC [Keys.ITEM_TYPE] 에 [id] 를 적어 알아본다. */
enum class ItemKind(val id: String, val label: String) {
    BOOK("book", "부여서"),
    UNOPENED_BOOK("unopened-book", "미확인 부여서"),
    MAGIC_DUST("magic-dust", "마법 가루"),
    SECRET_DUST("secret-dust", "비밀 가루"),
    MYSTERY_DUST("mystery-dust", "신비한 가루"),
    WHITE_SCROLL("white-scroll", "화이트 스크롤"),
    BLACK_SCROLL("black-scroll", "블랙 스크롤"),
    RANDOM_SCROLL("randomization-scroll", "무작위 스크롤"),
    TRANSMOG_SCROLL("transmog-scroll", "변환 스크롤"),
    SLOT_INCREASER("slot-increaser", "칸 확장기"),
    ORB("orb", "오브"),
    NAMETAG("item-nametag", "아이템 이름표"),
    STATTRAK("stattrak", "처치 추적기"),
    MOBTRAK("mobtrak", "몹 처치 추적기"),
    BLOCKTRAK("blocktrak", "채굴 추적기"),
    FISHTRAK("fishtrak", "낚시 추적기"),
    SOUL_TRACKER("soul-tracker", "영혼 추적기"),
    SOUL_GEM("soul-gem", "영혼석"),
    ;

    companion object {
        fun byId(id: String?): ItemKind? = entries.firstOrNull { it.id == id }

        /** 추적기 아이템 → 로어·수를 담당하는 [Trackers.Tracker]. */
        val TRACKERS: Map<ItemKind, Trackers.Tracker> by lazy {
            mapOf(STATTRAK to Trackers.STAT, MOBTRAK to Trackers.MOB, BLOCKTRAK to Trackers.BLOCK, FISHTRAK to Trackers.FISH)
        }
    }
}

/** 오브가 붙는 아이템 종류. */
enum class OrbKind(val id: String, val label: String, val applies: List<String>) {
    WEAPON("weapon", "무기", listOf("ALL_WEAPONS", "BOW", "CROSSBOW", "TRIDENT", "MACE")),
    ARMOR("armor", "방어구", listOf("ALL_ARMOR", "ELYTRA")),
    TOOL("tool", "도구", listOf("ALL_TOOLS", "FISHING_ROD", "SHEARS")),
    ;

    companion object {
        fun byId(id: String?): OrbKind? = entries.firstOrNull { it.id == id }
    }
}

/** `items.yml` 의 한 칸. */
data class ItemLook(
    val material: Material,
    val name: String,
    val lore: List<String>,
    val modelData: Int = 0,
    val glow: Boolean = false,
) {
    fun save(section: ConfigurationSection) {
        section.set("material", material.name)
        if (modelData > 0) section.set("custom-model-data", modelData)
        if (glow) section.set("glow", true)
        section.set("name", name)
        section.set("lore", lore)
    }

    companion object {
        fun load(section: ConfigurationSection, fallback: ItemLook): ItemLook = ItemLook(
            material = Material.matchMaterial(section.getString("material").orEmpty()) ?: fallback.material,
            name = section.getString("name") ?: fallback.name,
            lore = section.getStringList("lore").ifEmpty { fallback.lore },
            modelData = section.getInt("custom-model-data", 0),
            glow = section.getBoolean("glow", false),
        )
    }
}

/**
 * 아이템을 만들고 알아본다. 모양은 `items.yml`(부여서는 `config.yml`·등급)에서, 뜻은 PDC 에서 온다 —
 * **이름·로어로 알아보지 않는다.** 관리자가 모양을 바꿔도 이미 나간 아이템이 계속 동작해야 한다.
 */
class EnchantItems(private val e: Enchants) {

    private val random = Random()

    @Volatile
    var looks: Map<ItemKind, ItemLook> = emptyMap()
        private set

    /** 커스텀아이템이 정한 겉모습(재질·모델·모델 번호). [EnchantRoles] 참조. */
    data class Appearance(val material: Material, val itemModel: String, val modelData: Int)

    @Volatile
    private var appearances: Map<ItemKind, Appearance> = emptyMap()

    /** 등급 id → 부여서 겉모습. 빈 id 는 "모든 등급". */
    @Volatile
    private var bookAppearances: Map<String, Appearance> = emptyMap()

    /** 커스텀아이템의 역할에서 겉모습을 다시 읽는다. 역할이 바뀔 때마다(core `ItemRoles.listen`). */
    fun refreshAppearances() {
        fun look(holder: kr.inmc.core.integration.ItemRoles.Holder) = Appearance(holder.material, holder.itemModel, holder.modelData)
        appearances = kr.inmc.core.integration.ItemRoles.holders(EnchantRoles.ITEM)
            .mapNotNull { holder -> ItemKind.byId(holder.values["kind"])?.let { it to look(holder) } }.toMap()
        bookAppearances = kr.inmc.core.integration.ItemRoles.holders(EnchantRoles.BOOK)
            .associate { holder -> holder.values["group"].orEmpty() to look(holder) }
    }

    private fun applyModel(stack: ItemStack, appearance: Appearance?) {
        val model = appearance?.itemModel?.takeIf { it.isNotBlank() }?.let(org.bukkit.NamespacedKey::fromString) ?: return
        stack.editMeta { runCatching { it.setItemModel(model) } }
    }

    fun load(section: ConfigurationSection?) {
        looks = ItemKind.entries.filter { it != ItemKind.BOOK }.associateWith { kind ->
            val fallback = ItemLook(Material.PAPER, kind.label, emptyList())
            section?.getConfigurationSection(kind.id)?.let { ItemLook.load(it, fallback) } ?: fallback
        }
    }

    fun kindOf(stack: ItemStack?): ItemKind? {
        if (stack == null || stack.type.isAir || !stack.hasItemMeta()) return null
        return ItemKind.byId(stack.itemMeta.persistentDataContainer.get(Keys.ITEM_TYPE, PersistentDataType.STRING))
    }

    // --- 부여서 ---------------------------------------------------------------------------

    /** 성공률·파괴율 한 쌍. 설정이 무작위면 범위에서 뽑는다. */
    fun rates(): Pair<Int, Int> {
        val config = e.config
        if (!config.randomRates) return config.fixedSuccess to config.fixedDestroy
        return roll(config.randomSuccess) to roll(config.randomDestroy)
    }

    fun book(def: EnchantDefinition, level: Int, success: Int, destroy: Int): ItemStack {
        val config = e.config
        val group = e.groups.get(def.group)
        val override = def.level(level)?.book
        val appearance = bookAppearances[group.id] ?: bookAppearances[""]
        val material = appearance?.material ?: Material.matchMaterial(override?.material ?: group.bookMaterial ?: config.bookMaterial) ?: Material.ENCHANTED_BOOK
        val placeholders = mapOf(
            "{color}" to group.color,
            "{name}" to e.lore.plainName(def),
            "{level}" to e.lore.levelText(def, level).ifEmpty { "" },
            "{success}" to success.toString(),
            "{destroy}" to destroy.toString(),
            "{applies}" to def.appliesTo,
            "{max-level}" to def.maxLevel.toString(),
            "{group}" to group.name,
        )
        val description = def.descriptionFor(level)
        val lore = (override?.lore?.takeIf { it.isNotEmpty() } ?: config.bookLore).flatMap { line ->
            if (line.contains("{description}")) description.map { line.replace("{description}", it) } else listOf(line)
        }.map { fill(it, placeholders) }
        val stack = decorate(
            ItemStack(material),
            fill(override?.name ?: config.bookName, placeholders),
            lore,
            appearance?.modelData?.takeIf { it > 0 } ?: override?.customModelData ?: group.bookModelData,
            glow = true,
        )
        applyModel(stack, appearance)
        stack.editMeta { meta ->
            val pdc = meta.persistentDataContainer
            pdc.set(Keys.ITEM_TYPE, PersistentDataType.STRING, ItemKind.BOOK.id)
            pdc.set(Keys.BOOK_ENCHANT, PersistentDataType.STRING, def.id)
            pdc.set(Keys.BOOK_LEVEL, PersistentDataType.INTEGER, level)
            pdc.set(Keys.BOOK_SUCCESS, PersistentDataType.INTEGER, success.coerceIn(0, 100))
            pdc.set(Keys.BOOK_DESTROY, PersistentDataType.INTEGER, destroy.coerceIn(0, 100))
        }
        return stack
    }

    /** 성공률·파괴율을 설정대로 뽑은 부여서. */
    fun randomBook(def: EnchantDefinition, level: Int): ItemStack {
        val (success, destroy) = rates()
        return book(def, level, def.settings.bookSuccess?.let(::roll) ?: success, destroy)
    }

    data class BookInfo(val def: EnchantDefinition, val level: Int, val success: Int, val destroy: Int)

    fun bookInfo(stack: ItemStack?): BookInfo? {
        if (kindOf(stack) != ItemKind.BOOK) return null
        val pdc = stack!!.itemMeta.persistentDataContainer
        val def = e.registry.get(pdc.get(Keys.BOOK_ENCHANT, PersistentDataType.STRING).orEmpty()) ?: return null
        return BookInfo(
            def,
            pdc.get(Keys.BOOK_LEVEL, PersistentDataType.INTEGER) ?: 1,
            pdc.get(Keys.BOOK_SUCCESS, PersistentDataType.INTEGER) ?: 100,
            pdc.get(Keys.BOOK_DESTROY, PersistentDataType.INTEGER) ?: 0,
        )
    }

    /** 이 등급의 인첸트 하나를 무작위로(레벨도 무작위). 인챈터에서 안 나오게 한 것은 뺀다. */
    fun randomEnchant(group: Group): Pair<EnchantDefinition, Int>? {
        val pool = e.registry.all().filter { it.group.equals(group.id, ignoreCase = true) && !it.settings.disableInEnchanter }
        if (pool.isEmpty()) return null
        val def = pool[random.nextInt(pool.size)]
        return def to drawLevel(def)
    }

    /** 뽑기에서 나오는 레벨 — 1 부터 최대(또는 `draw-max-level`)까지 고르게. */
    fun drawLevel(def: EnchantDefinition): Int {
        val cap = def.maxLevel.coerceAtLeast(1).let { if (def.settings.drawMaxLevel > 0) minOf(it, def.settings.drawMaxLevel) else it }
        return 1 + random.nextInt(cap)
    }

    /** 등급 가중치(`sources.group-weights`)로 등급 하나. */
    fun randomGroup(): Group? {
        val weights = e.config.groupWeights.mapNotNull { (id, weight) -> e.groups.find(id)?.let { it to weight } }.filter { it.second > 0 }
        if (weights.isEmpty()) return e.groups.all().firstOrNull()
        var roll = random.nextInt(weights.sumOf { it.second })
        for ((group, weight) in weights) {
            if (roll < weight) return group
            roll -= weight
        }
        return weights.last().first
    }

    // --- 그 밖의 아이템 ----------------------------------------------------------------------

    fun unopened(group: Group, amount: Int = 1): ItemStack = make(ItemKind.UNOPENED_BOOK, group, amount = amount) {
        it.set(Keys.GROUP, PersistentDataType.STRING, group.id)
    }

    fun magicDust(group: Group, success: Int): ItemStack = make(ItemKind.MAGIC_DUST, group, value = success) {
        it.set(Keys.GROUP, PersistentDataType.STRING, group.id)
        it.set(Keys.AMOUNT, PersistentDataType.INTEGER, success)
    }

    fun secretDust(group: Group, amount: Int = 1): ItemStack = make(ItemKind.SECRET_DUST, group, amount = amount) {
        it.set(Keys.GROUP, PersistentDataType.STRING, group.id)
    }

    fun mysteryDust(amount: Int = 1): ItemStack = make(ItemKind.MYSTERY_DUST, amount = amount)

    fun whiteScroll(amount: Int = 1): ItemStack = make(ItemKind.WHITE_SCROLL, amount = amount)

    fun blackScroll(success: Int): ItemStack = make(ItemKind.BLACK_SCROLL, value = success, extra = mapOf("{success}" to success.toString())) {
        it.set(Keys.AMOUNT, PersistentDataType.INTEGER, success)
    }

    fun randomScroll(group: Group, amount: Int = 1): ItemStack = make(ItemKind.RANDOM_SCROLL, group, amount = amount) {
        it.set(Keys.GROUP, PersistentDataType.STRING, group.id)
    }

    fun transmogScroll(amount: Int = 1): ItemStack = make(ItemKind.TRANSMOG_SCROLL, amount = amount)

    fun slotIncreaser(slots: Int, group: Group? = null): ItemStack = make(ItemKind.SLOT_INCREASER, group, value = slots) {
        it.set(Keys.AMOUNT, PersistentDataType.INTEGER, slots)
    }

    fun orb(kind: OrbKind, slots: Int): ItemStack = make(ItemKind.ORB, value = slots, extra = mapOf("{kind}" to kind.label)) {
        it.set(Keys.ORB_KIND, PersistentDataType.STRING, kind.id)
        it.set(Keys.AMOUNT, PersistentDataType.INTEGER, slots)
    }

    fun nametag(amount: Int = 1): ItemStack = make(ItemKind.NAMETAG, amount = amount)

    fun tracker(kind: ItemKind, amount: Int = 1): ItemStack {
        require(kind in ItemKind.TRACKERS) { "추적기가 아니다: $kind" }
        return make(kind, amount = amount)
    }

    fun soulTracker(amount: Int = 1): ItemStack = make(ItemKind.SOUL_TRACKER, amount = amount)

    fun soulGem(souls: Int): ItemStack = make(ItemKind.SOUL_GEM, extra = mapOf("{souls}" to souls.toString())) {
        it.set(Keys.SOULS, PersistentDataType.INTEGER, souls)
        // 영혼석끼리 한 칸에 쌓이면 수가 섞인다. 하나하나 다르게.
        it.set(Keys.UNIQUE, PersistentDataType.STRING, java.util.UUID.randomUUID().toString())
    }

    /** 아이템에 적힌 값(가루 성공률·확장기 칸·스크롤 성공률·영혼석 영혼). */
    fun amount(stack: ItemStack?): Int = EnchantStorage.int(stack, Keys.AMOUNT)

    fun group(stack: ItemStack?): Group? =
        stack?.itemMeta?.persistentDataContainer?.get(Keys.GROUP, PersistentDataType.STRING)?.let { e.groups.find(it) }

    fun orbKind(stack: ItemStack?): OrbKind? =
        OrbKind.byId(stack?.itemMeta?.persistentDataContainer?.get(Keys.ORB_KIND, PersistentDataType.STRING))

    fun roll(range: IntRange): Int = if (range.first >= range.last) range.first else range.first + random.nextInt(range.last - range.first + 1)

    // --- 도움 ----------------------------------------------------------------------------

    private fun make(
        kind: ItemKind,
        group: Group? = null,
        amount: Int = 1,
        value: Int? = null,
        extra: Map<String, String> = emptyMap(),
        tag: (org.bukkit.persistence.PersistentDataContainer) -> Unit = {},
    ): ItemStack {
        val look = looks[kind] ?: ItemLook(Material.PAPER, kind.label, emptyList())
        val placeholders = buildMap {
            put("{group}", group?.name ?: "")
            put("{color}", group?.color ?: "")
            put("{amount}", value?.toString() ?: "")
            put("{slots}", value?.toString() ?: "")
            putAll(extra)
        }
        val appearance = appearances[kind]
        val stack = decorate(
            ItemStack(appearance?.material ?: look.material, amount.coerceIn(1, 64)),
            fill(look.name, placeholders), look.lore.map { fill(it, placeholders) },
            appearance?.modelData?.takeIf { it > 0 } ?: look.modelData, look.glow,
        )
        applyModel(stack, appearance)
        stack.editMeta { meta ->
            meta.persistentDataContainer.set(Keys.ITEM_TYPE, PersistentDataType.STRING, kind.id)
            tag(meta.persistentDataContainer)
        }
        return stack
    }

    private fun decorate(stack: ItemStack, name: String, lore: List<String>, modelData: Int, glow: Boolean): ItemStack {
        stack.editMeta { meta ->
            meta.displayName(Text.renderFlat(name))
            meta.lore(Text.renderLore(lore))
            @Suppress("DEPRECATION")
            if (modelData > 0) meta.setCustomModelData(modelData)
            if (glow) meta.setEnchantmentGlintOverride(true)
        }
        return stack
    }

    private fun fill(text: String, placeholders: Map<String, String>): String =
        placeholders.entries.fold(text) { acc, (key, value) -> acc.replace(key, value) }
}
