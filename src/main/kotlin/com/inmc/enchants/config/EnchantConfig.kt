package com.inmc.enchants.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `config.yml` 한 벌. 리로드는 이 객체를 통째로 바꾼다 — 필드를 하나씩 덮으면 반쯤 바뀐 설정을
 * 읽는 순간이 생긴다(인벤키퍼에서 실제로 그랬다).
 *
 * 키 이름은 한국어 주석과 함께 배포 파일에 있다. AE 의 같은 설정은 괄호에 AE 이름을 적었다.
 */
data class EnchantConfig(
    val disabledWorlds: Set<String> = emptySet(),
    val comboWindowMillis: Long = 3000L,
    val comboForMobs: Boolean = true,
    val activationActionBar: String = "<gray>발동: {enchant}",

    // --- 로어 ---
    val romanNumerals: Boolean = true,
    /** 최대 레벨이 1 인 인첸트는 레벨 숫자를 안 붙인다(AE hideIfOnlyOneLevel). */
    val hideLevelIfOnlyOne: Boolean = true,
    val enchantLine: String = "{color}{name} {level}",
    val descriptionsInLore: Boolean = false,
    val descriptionLine: String = "{color}* <dark_gray>{description}",
    /** GROUP 이면 그룹 순, APPLIED 면 붙인 순(AE organizeEnchantsOnItems). */
    val sortByGroup: Boolean = true,
    val soulsLine: String = "<red>영혼: <white>{souls}",
    val slotsInLore: Boolean = false,
    val slotsLine: String = "<gray>인첸트 칸: <white>{used}<gray>/<white>{max}",
    val whiteScrollLine: String = "<white><bold>보호됨</bold> <gray>(화이트 스크롤)",
    val customEnchantsGlow: Boolean = true,

    // --- 칸 ---
    val slotsEnabled: Boolean = true,
    val maxSlots: Int = 9,
    /** 확장기·오브로 늘릴 수 있는 최대. */
    val maxExtraSlots: Int = 5,

    // --- 부여서 ---
    val dragDropApply: Boolean = true,
    val anvilApply: Boolean = true,
    val randomRates: Boolean = true,
    val fixedSuccess: Int = 100,
    val fixedDestroy: Int = 0,
    /** 실패하면 파괴율을 굴리는가(AE destroy-if-fail). */
    val destroyOnFail: Boolean = true,
    /** 파괴되면 아이템이 사라지는가. false 면 부여서만 사라진다(AE destroy.destroy-item). */
    val destroyItem: Boolean = true,
    val bookMaterial: String = "ENCHANTED_BOOK",
    /** 무작위일 때 성공률·파괴율을 뽑는 범위. */
    val randomSuccess: IntRange = 25..100,
    val randomDestroy: IntRange = 0..60,
    val bookName: String = "{color}<bold><u>{name} {level}",
    val bookLore: List<String> = listOf(
        "<green>성공률 {success}%",
        "<red>파괴율 {destroy}%",
        "<yellow>{description}",
        "<gray>{applies} 인첸트",
        "<gray>아이템 위에 끌어다 놓으면 붙습니다.",
    ),

    /** 미확인 부여서·인챈터·드랍이 등급을 고르는 가중치. */
    val groupWeights: Map<String, Int> = mapOf("SIMPLE" to 40, "UNIQUE" to 25, "ELITE" to 15, "ULTIMATE" to 10, "LEGENDARY" to 6, "FABLED" to 3, "HEROIC" to 1),
    /** 블랙 스크롤로 뽑은 부여서의 성공률 범위. */
    val blackScrollSuccess: IntRange = 60..100,

    // --- 바닐라에서 인첸트가 나오는 곳. 확률 0 이면 끔 ---
    /** 부여대에서 쓴 레벨 1 당 커스텀 인첸트가 함께 붙을 확률(%). */
    val tableChancePerLevel: Double = 1.5,
    /** 상자 전리품에 미확인 부여서가 섞일 확률(%). */
    val lootChance: Double = 15.0,
    /** 사서 주민이 새 거래를 얻을 때 커스텀 부여서를 팔 확률(%). */
    val villagerChance: Double = 20.0,
    val villagerPrice: IntRange = 10..40,

    /** 땜장이에서 바꾼 것을 되돌릴 수 있는 시간(시간). 0 이면 되돌리기를 끈다. */
    val tinkerRestoreHours: Int = 72,

    // --- 합치기 (같은 부여서 두 장 → 레벨 +1) ---
    val combineEnabled: Boolean = true,
    val combineUseChances: Boolean = true,

    // --- 효과 ---
    val breakBlockDamagesTool: Boolean = true,
    val stealMoneyMessage: Boolean = true,
    val grindstoneRemoves: Boolean = true,

    // --- 영혼 ---
    val soulsPerKill: Int = 1,
    val soulsFromPlayers: Boolean = true,
    val soulsFromMobs: Boolean = false,
    val miningSoulsChance: Double = 0.0,
    val fishingSoulsChance: Double = 0.0,

    val appliesGroups: Map<String, List<String>> = emptyMap(),
    /** 조건에서 `%sword types%` 처럼 쓰는 목록 변수(AE abilities.yml). */
    val listVariables: Map<String, String> = emptyMap(),

    /** 이 글이 설명에 있는 아이템에는 커스텀 인첸트를 못 붙인다(AE enchantLimitation). 비우면 안 본다. */
    val lockLore: String = "수정 불가",
    /** 이 PDC 표식(`네임스페이스:키`)이 있는 아이템도. 다른 플러그인의 특수 아이템을 지킬 때. */
    val lockTag: String = "",
) {

    companion object {

        fun load(config: YamlConfiguration): EnchantConfig {
            val d = EnchantConfig()
            val lore = config.getConfigurationSection("lore")
            val slots = config.getConfigurationSection("slots")
            val books = config.getConfigurationSection("books")
            val combine = config.getConfigurationSection("combining")
            val effects = config.getConfigurationSection("effects")
            val souls = config.getConfigurationSection("souls")
            return EnchantConfig(
                disabledWorlds = config.getStringList("disabled-worlds").toSet(),
                comboWindowMillis = (config.getDouble("combo.window-seconds", 3.0) * 1000).toLong(),
                comboForMobs = config.getBoolean("combo.mobs", d.comboForMobs),
                activationActionBar = config.getString("activation-action-bar") ?: d.activationActionBar,

                romanNumerals = lore?.getBoolean("roman-numerals", d.romanNumerals) ?: d.romanNumerals,
                hideLevelIfOnlyOne = lore?.getBoolean("hide-level-if-only-one", d.hideLevelIfOnlyOne) ?: d.hideLevelIfOnlyOne,
                enchantLine = lore?.getString("enchant-line") ?: d.enchantLine,
                descriptionsInLore = lore?.getBoolean("descriptions.enabled", d.descriptionsInLore) ?: d.descriptionsInLore,
                descriptionLine = lore?.getString("descriptions.line") ?: d.descriptionLine,
                sortByGroup = (lore?.getString("sort") ?: "GROUP").equals("GROUP", ignoreCase = true),
                soulsLine = lore?.getString("souls-line") ?: d.soulsLine,
                slotsInLore = slots?.getBoolean("display-in-lore", d.slotsInLore) ?: d.slotsInLore,
                slotsLine = slots?.getString("line") ?: d.slotsLine,
                whiteScrollLine = lore?.getString("white-scroll-line") ?: d.whiteScrollLine,
                customEnchantsGlow = lore?.getBoolean("glow", d.customEnchantsGlow) ?: d.customEnchantsGlow,

                slotsEnabled = slots?.getBoolean("enabled", d.slotsEnabled) ?: d.slotsEnabled,
                maxSlots = (slots?.getInt("max", d.maxSlots) ?: d.maxSlots).coerceAtLeast(1),
                maxExtraSlots = (slots?.getInt("max-increase", d.maxExtraSlots) ?: d.maxExtraSlots).coerceAtLeast(0),

                dragDropApply = books?.getBoolean("drag-drop", d.dragDropApply) ?: d.dragDropApply,
                anvilApply = books?.getBoolean("anvil", d.anvilApply) ?: d.anvilApply,
                randomRates = books?.getBoolean("random-rates", d.randomRates) ?: d.randomRates,
                fixedSuccess = (books?.getInt("success", d.fixedSuccess) ?: d.fixedSuccess).coerceIn(0, 100),
                fixedDestroy = (books?.getInt("destroy", d.fixedDestroy) ?: d.fixedDestroy).coerceIn(0, 100),
                destroyOnFail = books?.getBoolean("destroy-on-fail", d.destroyOnFail) ?: d.destroyOnFail,
                destroyItem = books?.getBoolean("destroy-item", d.destroyItem) ?: d.destroyItem,
                bookMaterial = books?.getString("item") ?: d.bookMaterial,
                randomSuccess = range(books?.getString("random-success"), d.randomSuccess),
                randomDestroy = range(books?.getString("random-destroy"), d.randomDestroy),
                groupWeights = config.getConfigurationSection("group-weights")?.let { section ->
                    section.getKeys(false).associate { it.uppercase() to section.getInt(it).coerceAtLeast(0) }
                } ?: d.groupWeights,
                blackScrollSuccess = range(config.getString("black-scroll.success"), d.blackScrollSuccess),
                tableChancePerLevel = config.getDouble("sources.enchanting-table.chance-per-level", d.tableChancePerLevel).coerceIn(0.0, 100.0),
                lootChance = config.getDouble("sources.loot.chance", d.lootChance).coerceIn(0.0, 100.0),
                villagerChance = config.getDouble("sources.villagers.chance", d.villagerChance).coerceIn(0.0, 100.0),
                villagerPrice = range(config.getString("sources.villagers.price"), d.villagerPrice),
                tinkerRestoreHours = config.getInt("tinkerer.restore-hours", d.tinkerRestoreHours).coerceIn(0, 720),
                bookName = books?.getString("name") ?: d.bookName,
                bookLore = books?.getStringList("lore")?.takeIf { it.isNotEmpty() } ?: d.bookLore,

                combineEnabled = combine?.getBoolean("enabled", d.combineEnabled) ?: d.combineEnabled,
                combineUseChances = combine?.getBoolean("use-chances", d.combineUseChances) ?: d.combineUseChances,

                breakBlockDamagesTool = effects?.getBoolean("break-block-damages-tool", d.breakBlockDamagesTool) ?: d.breakBlockDamagesTool,
                stealMoneyMessage = effects?.getBoolean("steal-money-message", d.stealMoneyMessage) ?: d.stealMoneyMessage,
                grindstoneRemoves = effects?.getBoolean("grindstone-removes", d.grindstoneRemoves) ?: d.grindstoneRemoves,

                soulsPerKill = (souls?.getInt("per-kill", d.soulsPerKill) ?: d.soulsPerKill).coerceAtLeast(0),
                soulsFromPlayers = souls?.getBoolean("from-players", d.soulsFromPlayers) ?: d.soulsFromPlayers,
                soulsFromMobs = souls?.getBoolean("from-mobs", d.soulsFromMobs) ?: d.soulsFromMobs,
                miningSoulsChance = souls?.getDouble("mining-chance", d.miningSoulsChance) ?: d.miningSoulsChance,
                fishingSoulsChance = souls?.getDouble("fishing-chance", d.fishingSoulsChance) ?: d.fishingSoulsChance,

                appliesGroups = readGroups(config.getConfigurationSection("applies-groups")),
                listVariables = readStrings(config.getConfigurationSection("list-variables")),
                lockLore = config.getString("limitation.lore", d.lockLore).orEmpty().trim(),
                lockTag = config.getString("limitation.tag", d.lockTag).orEmpty().trim(),
            )
        }

        /** `25-100` · `40`. 못 읽으면 [fallback]. 0~100 으로 자르지 않는다 — 가격 범위에도 쓴다. */
        fun range(raw: String?, fallback: IntRange): IntRange {
            val parts = raw?.split('-')?.mapNotNull { it.trim().toIntOrNull() } ?: return fallback
            return when (parts.size) {
                1 -> parts[0]..parts[0]
                2 -> minOf(parts[0], parts[1])..maxOf(parts[0], parts[1])
                else -> fallback
            }
        }

        private fun readGroups(section: ConfigurationSection?): Map<String, List<String>> {
            if (section == null) return emptyMap()
            return section.getKeys(false).associate { key -> key.uppercase() to section.getStringList(key).map { it.uppercase() } }
        }

        private fun readStrings(section: ConfigurationSection?): Map<String, String> {
            if (section == null) return emptyMap()
            return section.getKeys(false).associate { key -> key to section.getString(key).orEmpty() }
        }
    }
}
