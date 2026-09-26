package com.inmc.enchants.item

import com.inmc.enchants.Enchants
import kr.inmc.core.integration.ItemRoles
import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * 인첸트가 커스텀아이템에 내놓는 역할(core [ItemRoles]) — **인첸트 아이템**(가루·스크롤·칸 확장기·오브·추적기 …)과 **부여서 모양**.
 *
 * 인첸트 아이템은 제 표식(PDC — 종류·등급·성공률)이 있어야 동작하므로 **만드는 것은 인첸트다**([ItemRoles.Role.factory]).
 * 커스텀아이템은 목록에 보이고 지급하면 인첸트가 만든 진짜를 준다. 커스텀아이템에서 정하는 것은 **겉모습**(재질·모델·모델 번호) —
 * 이름·설명·값은 인첸트의 `items.yml`(부여서는 `config.yml`·등급) 그대로다(자리표시자 `{group}` `{success}` … 가 들어 있다).
 * 사용자 결정(2026-09-25): 특수 아이템만 목록에, 부여서는 모양만 정할 수 있게.
 */
object EnchantRoles {

    const val OWNER = "인첸트"

    val ITEM = "enchants.item"
    val BOOK = "enchants.book"

    /** 커스텀아이템 목록에 올리는 것 — 부여서는 인첸트 373종 × 레벨마다라 빼고, 모양만 [BOOK] 으로. */
    val LISTED = ItemKind.entries.filter { it != ItemKind.BOOK }

    fun roles(e: Enchants): List<ItemRoles.Role> {
        val groups = { listOf("" to "무작위") + e.groups.all().map { it.id to it.name } }
        return listOf(
            ItemRoles.Role(
                ITEM, OWNER, "인첸트 아이템", Material.GLOWSTONE_DUST,
                listOf("가루·스크롤·칸 확장기·오브·추적기 … 가 이 아이템의 모양(재질·모델)으로 만들어집니다.", "이름·설명·값은 인첸트 관리의 것입니다."),
                listOf(
                    ItemRoles.Choice("kind", "종류", { LISTED.map { it.id to it.label } }, ItemKind.MAGIC_DUST.id),
                    ItemRoles.Choice("group", "등급(지급할 때)", groups, visible = { it["kind"] in GROUPED }),
                    ItemRoles.Choice("orb", "오브 종류", { OrbKind.entries.map { it.id to it.label } }, OrbKind.WEAPON.id, visible = { it["kind"] == ItemKind.ORB.id }),
                    ItemRoles.Number("slots", "오브 칸 수", 1.0, 54.0, 1.0, "1", visible = { it["kind"] == ItemKind.ORB.id }),
                    ItemRoles.Number("souls", "영혼 수", 1.0, 1_000_000.0, 100.0, "100", visible = { it["kind"] == ItemKind.SOUL_GEM.id }),
                    ItemRoles.Choice("enchant", "강화할 인첸트", { scrollChoices(e) }, visible = { it["kind"] == ItemKind.LEVEL_SCROLL.id }),
                    ItemRoles.Number("success", "강화 성공 확률(%)", 0.0, 100.0, 5.0, "50", visible = { it["kind"] == ItemKind.LEVEL_SCROLL.id }),
                    ItemRoles.Number("downgrade", "실패 시 하락 확률(%)", 0.0, 100.0, 5.0, "0", visible = { it["kind"] == ItemKind.LEVEL_SCROLL.id }),
                ),
                factory = { values, amount -> make(e, values, amount) },
            ),
            ItemRoles.Role(
                BOOK, OWNER, "부여서 모양", Material.ENCHANTED_BOOK,
                listOf("부여서가 이 아이템의 모양(재질·모델)으로 만들어집니다.", "등급을 고르면 그 등급의 부여서만."),
                listOf(ItemRoles.Choice("group", "등급", groups)),
                factory = { values, amount -> (e.groups.all().firstOrNull { it.id == values["group"] } ?: e.items.randomGroup())?.let { e.items.unopened(it, amount) } },
            ),
        )
    }

    private val GROUPED = setOf(ItemKind.UNOPENED_BOOK, ItemKind.MAGIC_DUST, ItemKind.SECRET_DUST, ItemKind.RANDOM_SCROLL, ItemKind.SLOT_INCREASER).map { it.id }.toSet()

    /** 커스텀아이템에서 지급할 때 — 인첸트 관리의 "아이템 지급"과 같은 값으로. */
    private fun make(e: Enchants, values: Map<String, String>, amount: Int): ItemStack? {
        val kind = ItemKind.byId(values["kind"]) ?: return null
        val group = e.groups.all().firstOrNull { it.id == values["group"] } ?: e.items.randomGroup()
        val items = e.items
        return when (kind) {
            ItemKind.BOOK -> null
            ItemKind.UNOPENED_BOOK -> group?.let { items.unopened(it, amount) }
            ItemKind.MAGIC_DUST -> group?.let { items.magicDust(it, items.roll(it.dustMin..it.dustMax)) }
            ItemKind.SECRET_DUST -> group?.let { items.secretDust(it, amount) }
            ItemKind.RANDOM_SCROLL -> group?.let { items.randomScroll(it, amount) }
            ItemKind.SLOT_INCREASER -> group?.let { items.slotIncreaser(it.slotIncreaser, it) }
            ItemKind.MYSTERY_DUST -> items.mysteryDust(amount)
            ItemKind.WHITE_SCROLL -> items.whiteScroll(amount)
            ItemKind.TRANSMOG_SCROLL -> items.transmogScroll(amount)
            ItemKind.NAMETAG -> items.nametag(amount)
            ItemKind.SOUL_TRACKER -> items.soulTracker(amount)
            ItemKind.STATTRAK, ItemKind.MOBTRAK, ItemKind.BLOCKTRAK, ItemKind.FISHTRAK -> items.tracker(kind, amount)
            ItemKind.BLACK_SCROLL -> items.blackScroll(items.roll(e.config.blackScrollSuccess))
            ItemKind.ORB -> items.orb(OrbKind.byId(values["orb"]) ?: OrbKind.WEAPON, values["slots"]?.toDoubleOrNull()?.toInt()?.coerceIn(1, 54) ?: 1)
            ItemKind.SOUL_GEM -> items.soulGem(values["souls"]?.toDoubleOrNull()?.toInt()?.coerceAtLeast(1) ?: 100)
            // 인첸트를 고르기 전에는 만들 것이 없다(목록에 한 번 올리기도 건너뛴다 — 스크롤은 관리자가 인첸트마다 만든다).
            ItemKind.LEVEL_SCROLL -> e.scrolls.resolve(values["enchant"])?.let { target ->
                fun percent(key: String, fallback: Int) = values[key]?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100) ?: fallback
                items.levelScroll(target, percent("success", 50), percent("downgrade", 0), amount)
            }
        }
    }

    /** 강화 스크롤의 인첸트 보기 — (id, 보이는 이름). 우리 인첸트 다음 바닐라. */
    private fun scrollChoices(e: Enchants): List<Pair<String, String>> = e.scrolls.all().map { target ->
        target.id to when (target) {
            is ScrollEnchant.Custom -> e.lore.plainName(target.def) + " (" + target.id + ")"
            is ScrollEnchant.Vanilla -> "바닐라 " + target.id.removePrefix("minecraft:")
        }
    }

    /**
     * 커스텀아이템 목록에 인첸트 아이템을 한 번 올린다(종류마다 하나, 모양은 지금 인첸트가 만드는 그대로). 이미 올린 종류는 건너뛴다 —
     * 관리자가 역할을 뗀 것을 다시 붙이지 않도록 한 번 올린 종류는 `items.yml.linked` 에 적어 둔다.
     */
    fun link(e: Enchants) {
        if (!ItemRoles.active) return
        val marker = e.io.file("items.yml.linked")
        val done = if (marker.isFile) marker.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet() else mutableSetOf()
        val held = ItemRoles.holders(ITEM).mapNotNull { it.values["kind"] }.toSet()
        var added = 0
        for (kind in LISTED) {
            if (kind.id in done || kind.id in held) continue
            val sample = make(e, mapOf("kind" to kind.id), 1) ?: continue
            val ref = ItemRoles.adopt(sample, "인첸트_" + kind.id) ?: continue
            ItemRoles.assign(ref, ITEM, mapOf("kind" to kind.id))
            done += kind.id
            added++
        }
        done += held
        marker.parentFile.mkdirs()
        marker.writeText(done.sorted().joinToString("\n"), Charsets.UTF_8)
        if (added > 0) e.logger.info("인첸트 아이템 ${added}종을 커스텀아이템 목록에 올렸습니다")
    }
}
