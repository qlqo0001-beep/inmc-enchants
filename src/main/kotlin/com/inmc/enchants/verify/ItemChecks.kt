package com.inmc.enchants.verify

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.engine.TriggerContext
import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.item.ItemKind
import com.inmc.enchants.item.ItemUses.Outcome
import com.inmc.enchants.item.Keys
import com.inmc.enchants.item.OrbKind
import com.inmc.enchants.set.ArmorSet
import com.inmc.enchants.set.BonusEffects
import com.inmc.enchants.set.SetMigration
import com.inmc.enchants.set.Gear
import com.inmc.enchants.set.Piece
import kr.inmc.core.integration.CustomEnchantHook
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.util.Text
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.block.Action
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffectType
import java.io.InputStreamReader

/**
 * 인첸트 아이템(부여서·가루·스크롤·확장기·오브·추적기·영혼석) 규칙 검사. 아이템을 **실제로 만들어**
 * [com.inmc.enchants.item.ItemUses] 에 넣어 본다 — `ItemStack` 은 서버 없이 못 만들어서 단위 테스트
 * 대신 여기 있다.
 *
 * 배포 인첸트(`drain`·`lifesteal` 등)를 재료로 쓴다. 맨 검에 부여서를 붙이는 검사는 요구 인첸트가 없는 `drain`(진화 사슬의 맨 아래)으로. 관리자가 지웠으면 그 검사는 건너뛴다.
 *
 * 마지막 두 건은 **리스너 배선**이다 — 플레이어가 실제로 하는 조작(커서의 부여서를 아이템 위에 클릭,
 * 미확인 부여서 우클릭)을 가짜 사건으로 우리 리스너에 넘긴다(다른 플러그인은 모른다).
 */
object ItemChecks {

    /** [run] 의 플레이어는 검증하는 사람이다. 가방은 건마다 검증기가 되돌린다. */
    class Check(val name: String, val run: (Enchants, Player) -> String?)

    /** 검사가 재료를 못 찾았다. 실패가 아니라 건너뜀이다. */
    class Missing(what: String) : RuntimeException(what)

    private fun ok(condition: Boolean, why: String): String? = if (condition) null else why

    private fun def(e: Enchants, id: String) = e.registry.get(id) ?: throw Missing("배포 인첸트 '$id' 가 없다")

    private fun sword() = ItemStack(Material.DIAMOND_SWORD)

    private fun group(e: Enchants, id: String) = e.groups.find(id) ?: throw Missing("등급 '$id' 가 없다")


    /** 배포 `sets.yml`(옛 인첸트 세트 — 커스텀아이템으로 옮기는 원본)의 세트 하나. */
    private fun deployed(e: Enchants, id: String): ArmorSet {
        val stream = e.plugin.getResource(SetMigration.FILE) ?: throw Missing("배포 ${SetMigration.FILE} 이 없다")
        val yaml = stream.use { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }
        val section = yaml.getConfigurationSection("sets.$id") ?: throw Missing("배포 세트 '$id' 가 없다")
        return ArmorSet.load(id, section) {} ?: throw Missing("배포 세트 '$id' 를 못 읽었다")
    }

    /**
     * 커스텀아이템이 [set] 의 4벌 단계를 넘긴 것처럼 — 입히지 않고 **엔진 쪽만** 본다. 몇 벌을 입었는지 세는 쪽은 커스텀아이템
     * 검증기가 본다. [block] 안에서 단계를 떼려면 `e.setService.forced = emptyList()`.
     */
    private fun <T> wearing(e: Enchants, set: ArmorSet, block: () -> T): T {
        val tree = BonusEffects(set.events, set.equipped, set.unequipped, set.disabledWorlds).toTree()
        e.setService.forced = listOf(CustomItemHook.SetEffects(set.id, set.name, Piece.entries.size, tree))
        try {
            return block()
        } finally {
            e.setService.forced = null
        }
    }

    val ALL: List<Check> = listOf(
        Check("부여서 → 검 (성공 100%)") { e, _ ->
            val r = e.uses.applyBook(null, e.items.book(def(e, "drain"), 2, 100, 0), sword())
            ok(r.outcome == Outcome.APPLIED && EnchantStorage.level(r.target, "drain") == 2 && r.used, "결과 ${r.outcome}")
                ?: ok(r.target!!.lore().orEmpty().any { Text.plain(it).contains(e.lore.plainName(def(e, "drain"))) }, "로어에 이름이 없다")
        },
        Check("부여서 실패 - 부여서만 사라진다") { e, _ ->
            val r = e.uses.applyBook(null, e.items.book(def(e, "drain"), 1, 0, 0), sword())
            ok(r.outcome == Outcome.FAILED && r.used && r.target != null && !EnchantStorage.has(r.target), "결과 ${r.outcome}")
        },
        Check("부여서 실패 + 파괴") { e, _ ->
            val r = e.uses.applyBook(null, e.items.book(def(e, "drain"), 1, 0, 100), sword())
            val expected = if (e.config.destroyOnFail && e.config.destroyItem) Outcome.DESTROYED else Outcome.FAILED
            ok(r.outcome == expected && (expected != Outcome.DESTROYED || r.target == null), "결과 ${r.outcome} (기대 $expected)")
        },
        Check("화이트 스크롤이 파괴를 한 번 막는다") { e, _ ->
            if (!e.config.destroyOnFail) throw Missing("설정에서 실패 시 파괴가 꺼져 있다")
            val scrolled = e.uses.use(null, e.items.whiteScroll(), sword())!!
            val r = e.uses.applyBook(null, e.items.book(def(e, "drain"), 1, 0, 100), scrolled.target!!)
            ok(scrolled.outcome == Outcome.WHITE_SCROLLED, "스크롤 ${scrolled.outcome}")
                ?: ok(r.outcome == Outcome.PROTECTED && r.target != null && !EnchantStorage.flag(r.target, Keys.WHITE_SCROLL), "결과 ${r.outcome}")
        },
        Check("금지 표시가 있는 아이템에는 붙지 않는다(limitation)") { e, _ ->
            if (e.config.lockLore.isEmpty()) throw Missing("설정에서 limitation.lore 가 비어 있다")
            val sword = sword().also { it.lore(listOf(Text.render("<red>" + e.config.lockLore))) }
            val r = e.uses.applyBook(null, e.items.book(def(e, "drain"), 1, 100, 0), sword)
            ok(r.outcome == Outcome.LOCKED && !r.used, "결과 ${r.outcome}")
        },
        Check("붙을 수 없는 아이템에는 굴리지도 않는다") { e, _ ->
            val r = e.uses.applyBook(null, e.items.book(def(e, "drain"), 1, 0, 100), ItemStack(Material.DIAMOND_PICKAXE))
            ok(r.outcome == Outcome.WRONG_ITEM && !r.used && r.target != null, "결과 ${r.outcome}")
        },
        Check("같거나 낮은 레벨은 붙지 않는다") { e, _ ->
            val first = e.uses.applyBook(null, e.items.book(def(e, "drain"), 2, 100, 0), sword()).target!!
            val r = e.uses.applyBook(null, e.items.book(def(e, "drain"), 2, 100, 0), first)
            ok(r.outcome == Outcome.ALREADY && !r.used, "결과 ${r.outcome}")
        },
        Check("칸이 가득 차면 붙지 않는다") { e, _ ->
            if (!e.config.slotsEnabled) throw Missing("설정에서 칸 제한이 꺼져 있다")
            val swordEnchants = e.registry.all().filter { Applicability.matchesAny(it.applies, "DIAMOND_SWORD", e.config.appliesGroups) }
            if (swordEnchants.size <= e.config.maxSlots) throw Missing("검 인첸트가 칸 수보다 적다")
            val full = sword().also { stack -> EnchantStorage.write(stack, swordEnchants.take(e.config.maxSlots).associate { it.id to 1 }) }
            val extra = swordEnchants[e.config.maxSlots]
            val r = e.uses.applyBook(null, e.items.book(extra, 1, 100, 0), full)
            ok(r.outcome == Outcome.NO_SLOTS || r.outcome == Outcome.CONFLICT || r.outcome == Outcome.MISSING_REQUIRED, "결과 ${r.outcome}")
        },
        Check("상위 인첸트는 하위를 요구하고 덮어쓴다") { e, _ ->
            val heroic = def(e, "demoniclifesteal")
            val missing = e.uses.applyBook(null, e.items.book(heroic, 1, 100, 0), sword())
            val base = sword().also { EnchantStorage.write(it, mapOf("lifesteal" to 5)) }
            val r = e.uses.applyBook(null, e.items.book(heroic, 1, 100, 0), base)
            ok(missing.outcome == Outcome.MISSING_REQUIRED, "하위 없이 ${missing.outcome}")
                ?: ok(r.outcome == Outcome.APPLIED && EnchantStorage.level(r.target, "lifesteal") == 0 && EnchantStorage.level(r.target, heroic.id) == 1, "결과 ${r.outcome}")
        },
        Check("커스텀아이템 전용 인첸트(폭파)는 바닐라 곡괭이에 안 붙는다") { e, _ ->
            val pick = ItemStack(Material.NETHERITE_PICKAXE).also { EnchantStorage.write(it, mapOf(def(e, "blastmining").id to 3)) }
            val r = e.uses.applyBook(null, e.items.book(def(e, "detonate"), 1, 100, 0), pick)
            ok(r.outcome == Outcome.WRONG_ITEM && !r.used, "결과 ${r.outcome}")
        },
        Check("거르는 목록은 캔 블록을 본다(자동 제련: 모래는 그대로, 철광석은 제련)") { e, p ->
            val block = p.location.block.getRelative(0, 3, 0)
            val original = block.blockData.clone()
            try {
                fun smelts(material: Material): Boolean {
                    block.type = material
                    val ctx = TriggerContext(Trigger.MINING, p, null, null, null, block = block)
                    e.engine.fireWith(ctx, ItemStack(Material.DIAMOND_PICKAXE), mapOf(def(e, "autosmelt").id to 1), EquipmentSlot.HAND)
                    return ctx.smeltDrops
                }
                val sand = smelts(Material.SAND)
                val ore = smelts(Material.IRON_ORE)
                ok(!sand, "모래가 제련됐다") ?: ok(ore, "철광석이 제련되지 않았다")
            } finally {
                block.blockData = original
            }
        },
        Check("함께 붙을 수 없는 인첸트") { e, _ ->
            val rod = ItemStack(Material.FISHING_ROD).also { EnchantStorage.write(it, mapOf(def(e, "reelmaster").id to 1)) }
            val r = e.uses.applyBook(null, e.items.book(def(e, "anglersblessing"), 1, 100, 0), rod)
            ok(r.outcome == Outcome.CONFLICT, "결과 ${r.outcome}")
        },
        Check("같은 부여서 두 장을 합치면 한 단계 오른다") { e, _ ->
            if (!e.config.combineEnabled) throw Missing("설정에서 합치기가 꺼져 있다")
            val d = def(e, "lifesteal")
            val r = e.uses.use(null, e.items.book(d, 2, 80, 10), e.items.book(d, 2, 60, 20))!!
            val info = e.items.bookInfo(r.target)
            val max = e.uses.use(null, e.items.book(d, d.maxLevel, 80, 10), e.items.book(d, d.maxLevel, 60, 20))!!
            ok(r.outcome == Outcome.COMBINED && info?.level == 3, "결과 ${r.outcome} 레벨 ${info?.level}")
                ?: ok(max.outcome == Outcome.MAX_LEVEL, "최대 레벨에서 ${max.outcome}")
        },
        Check("마법 가루는 같은 등급 부여서의 성공률을 올린다") { e, _ ->
            val d = def(e, "lifesteal")
            val r = e.uses.use(null, e.items.magicDust(group(e, d.group), 10), e.items.book(d, 1, 50, 0))!!
            val other = e.groups.all().first { !it.id.equals(d.group, ignoreCase = true) }
            val wrong = e.uses.use(null, e.items.magicDust(other, 10), e.items.book(d, 1, 50, 0))!!
            ok(r.outcome == Outcome.DUST_APPLIED && e.items.bookInfo(r.target)?.success == 60, "결과 ${r.outcome} ${e.items.bookInfo(r.target)?.success}")
                ?: ok(wrong.outcome == Outcome.DUST_WRONG_GROUP && !wrong.used, "다른 등급 ${wrong.outcome}")
        },
        Check("무작위 스크롤은 같은 인첸트·레벨로 확률만 새로 뽑는다") { e, _ ->
            val d = def(e, "lifesteal")
            val r = e.uses.use(null, e.items.randomScroll(group(e, d.group)), e.items.book(d, 3, 1, 99))!!
            val info = e.items.bookInfo(r.target)
            ok(r.outcome == Outcome.REROLLED && info?.def?.id == d.id && info.level == 3, "결과 ${r.outcome}")
        },
        Check("블랙 스크롤은 인첸트를 뽑아 부여서로 돌려준다") { e, _ ->
            val item = sword().also { EnchantStorage.write(it, mapOf("lifesteal" to 3)) }
            val r = e.uses.use(null, e.items.blackScroll(70), item)!!
            val book = e.items.bookInfo(r.returned)
            ok(r.outcome == Outcome.BLACK_SCROLLED && !EnchantStorage.has(r.target) && book?.def?.id == "lifesteal" && book.level == 3 && book.success == 70, "결과 ${r.outcome}")
        },
        Check("변환 스크롤") { e, _ ->
            val r = e.uses.use(null, e.items.transmogScroll(), sword().also { EnchantStorage.write(it, mapOf("lifesteal" to 1)) })!!
            ok(r.outcome == Outcome.TRANSMOGGED && EnchantStorage.flag(r.target, Keys.TRANSMOG), "결과 ${r.outcome}")
        },
        Check("칸 확장기는 상한까지만 늘린다") { e, _ ->
            if (e.config.maxExtraSlots <= 0) throw Missing("설정에서 확장 상한이 0 이다")
            val r = e.uses.use(null, e.items.slotIncreaser(1), sword())!!
            val maxed = sword().also { EnchantStorage.setInt(it, Keys.EXTRA_SLOTS, e.config.maxExtraSlots) }
            val over = e.uses.use(null, e.items.slotIncreaser(1), maxed)!!
            ok(r.outcome == Outcome.SLOTS_ADDED && EnchantStorage.int(r.target, Keys.EXTRA_SLOTS) == 1, "결과 ${r.outcome}")
                ?: ok(over.outcome == Outcome.SLOTS_MAXED && !over.used, "상한에서 ${over.outcome}")
        },
        Check("오브는 맞는 종류에만, 더 큰 것만") { e, _ ->
            val r = e.uses.use(null, e.items.orb(OrbKind.WEAPON, 12), sword())!!
            val wrong = e.uses.use(null, e.items.orb(OrbKind.WEAPON, 12), ItemStack(Material.DIAMOND_PICKAXE))!!
            val weaker = e.uses.use(null, e.items.orb(OrbKind.WEAPON, 10), r.target!!)!!
            ok(r.outcome == Outcome.ORB_APPLIED && e.slots.max(r.target) >= 12, "결과 ${r.outcome}")
                ?: ok(wrong.outcome == Outcome.WRONG_ITEM, "곡괭이에 ${wrong.outcome}")
                ?: ok(weaker.outcome == Outcome.ORB_WEAKER, "작은 오브에 ${weaker.outcome}")
        },
        Check("추적기 네 종") { e, _ ->
            ItemKind.TRACKERS.entries.firstNotNullOfOrNull { (kind, tracker) ->
                val r = e.uses.use(null, e.items.tracker(kind), sword())!!
                val again = e.uses.use(null, e.items.tracker(kind), r.target!!)!!
                ok(r.outcome == Outcome.TRACKER_APPLIED && r.target.itemMeta.persistentDataContainer.has(tracker.key), "${kind.label} ${r.outcome}")
                    ?: ok(again.outcome == Outcome.ALREADY, "${kind.label} 두 번째 ${again.outcome}")
            }
        },
        Check("영혼 추적기 · 영혼석 옮기기 · 꺼내기 · 합치기") { e, _ ->
            val tracked = e.uses.use(null, e.items.soulTracker(), sword())!!.target!!
            val filled = e.uses.use(null, e.items.soulGem(30), tracked)!!
            val refused = e.uses.use(null, e.items.soulGem(30), sword())!!
            val (emptied, gem) = e.uses.withdrawSouls(filled.target!!) ?: return@Check "꺼낼 영혼이 없다"
            val merged = e.uses.use(null, e.items.soulGem(10), e.items.soulGem(5))!!
            ok(e.souls.souls(filled.target) == 30, "옮긴 뒤 영혼 ${e.souls.souls(filled.target)}")
                ?: ok(refused.outcome == Outcome.NO_SOUL_TRACKER, "추적기 없는 아이템에 ${refused.outcome}")
                ?: ok(e.souls.souls(emptied) == 0 && EnchantStorage.int(gem, Keys.SOULS) == 30, "꺼내기")
                ?: ok(EnchantStorage.int(merged.target, Keys.SOULS) == 15, "합치기 ${EnchantStorage.int(merged.target, Keys.SOULS)}")
        },
        Check("미확인 부여서를 열면 그 등급의 부여서가 나온다") { e, _ ->
            val g = group(e, def(e, "lifesteal").group)
            val book = e.items.bookInfo(e.uses.open(e.items.unopened(g)))
            ok(book != null && book.def.group.equals(g.id, ignoreCase = true), "나온 것: ${book?.def?.id}")
        },
        Check("비밀 가루를 열면 마법 가루나 신비한 가루가 나온다") { e, _ ->
            val opened = e.uses.openSecret(e.items.secretDust(group(e, "LEGENDARY")))
            ok(e.items.kindOf(opened) in setOf(ItemKind.MAGIC_DUST, ItemKind.MYSTERY_DUST), "나온 것: ${e.items.kindOf(opened)}")
        },
        Check("이름표 - 색은 권한이 있을 때만") { e, _ ->
            val plain = e.uses.rename(sword(), "<red>불칼", colors = false)
            val colored = e.uses.rename(sword(), "<red>불칼", colors = true)
            ok(Text.plain(plain.effectiveName()) == "불칼", "이름 ${Text.plain(plain.effectiveName())}")
                ?: ok(Text.plain(colored.effectiveName()) == "불칼", "색 이름 ${Text.plain(colored.effectiveName())}")
        },
        Check("모루 미리보기는 굴리지 않고 성공한 모습") { e, _ ->
            val r = e.uses.applyBook(null, e.items.book(def(e, "drain"), 1, 0, 100), sword(), certain = true)
            ok(r.outcome == Outcome.APPLIED, "결과 ${r.outcome}")
        },
        Check("끌어다 놓기: 커서의 부여서를 가방의 검 위에 클릭") { e, p ->
            p.inventory.setItem(9, sword())
            p.setItemOnCursor(e.items.book(def(e, "drain"), 2, 100, 0))
            val event = InventoryClickEvent(p.openInventory, InventoryType.SlotType.CONTAINER, 9, ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR)
            e.itemListener.onDrop(event)
            ok(event.isCancelled, "사건이 취소되지 않았다(바닐라가 아이템을 바꿔 쥔다)")
                ?: ok(EnchantStorage.level(p.inventory.getItem(9), "drain") == 2, "검에 붙지 않았다")
                ?: ok(p.itemOnCursor.type.isAir, "커서의 부여서가 남았다")
        },
        Check("우클릭: 미확인 부여서를 열면 손에서 줄고 부여서가 들어온다") { e, p ->
            val group = group(e, def(e, "lifesteal").group)
            p.inventory.setItemInMainHand(e.items.unopened(group, 2))
            val event = PlayerInteractEvent(p, Action.RIGHT_CLICK_AIR, p.inventory.itemInMainHand, null, org.bukkit.block.BlockFace.SELF, EquipmentSlot.HAND)
            e.itemListener.onUse(event)
            ok(p.inventory.itemInMainHand.amount == 1, "손의 미확인 부여서가 ${p.inventory.itemInMainHand.amount}장")
                ?: ok(p.inventory.contents.any { e.items.kindOf(it) == ItemKind.BOOK }, "부여서가 들어오지 않았다")
        },
        Check("세트 효과: 지속 효과가 걸리고, 단계가 떨어지면 풀린다") { e, p ->
            wearing(e, deployed(e, "ember_guard")) {
                e.statics.refresh(p)
                val on = p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE)
                e.setService.forced = emptyList()
                e.statics.refresh(p)
                ok(on, "단계가 붙었는데 화염 저항이 없다") ?: ok(!p.hasPotionEffect(PotionEffectType.FIRE_RESISTANCE), "단계가 떨어졌는데 화염 저항이 남았다")
            }
        },
        Check("세트 효과: 발동 조건 효과가 엔진으로 돈다(웅크리기 → 투명)") { e, p ->
            wearing(e, deployed(e, "shadow_stalker")) {
                val fired = e.engine.fire(TriggerContext(Trigger.SHIFT, p, p, null, null))
                ok(fired && p.hasPotionEffect(PotionEffectType.INVISIBILITY), "발동 ${fired} · 투명 ${p.hasPotionEffect(PotionEffectType.INVISIBILITY)}")
            }
        },
        Check("세트 옮기기: 부위는 인첸트 범위의 가장 높은 값, 바닐라 보호 IV, 로어에 인첸트 줄 없음") { e, _ ->
            val set = deployed(e, "frost_giant")
            val helmet = SetMigration.piece(e, set, Piece.HELMET)
            val levels = EnchantStorage.read(helmet)
            val bad = set.gear(Piece.HELMET).enchants.mapNotNull { Gear.roll(it) }
                .mapNotNull { (id, range) -> e.registry.get(id)?.let { def -> Triple(def.id, range.last.coerceAtMost(def.maxLevel.coerceAtLeast(1)), levels[def.id]) } }
                .filter { (_, expected, actual) -> expected != actual }
            ok(bad.isEmpty(), "가장 높은 값이 아닌 인첸트: " + bad.joinToString { it.first + "=" + it.third + "(기대 " + it.second + ")" })
                ?: ok(helmet.getEnchantmentLevel(Enchantment.PROTECTION) == 4, "바닐라 보호 ${helmet.getEnchantmentLevel(Enchantment.PROTECTION)}")
                ?: ok(helmet.lore().orEmpty().size == set.gear(Piece.HELMET).lore.size, "로어 ${helmet.lore().orEmpty().size}줄 (설명 ${set.gear(Piece.HELMET).lore.size}줄)")
        },
        Check("공급처: 인첸트 연동 값은 더해진다") { e, _ ->
            val rod = ItemStack(Material.FISHING_ROD).also { EnchantStorage.write(it, mapOf(def(e, "twincatch").id to 2, def(e, "bait").id to 1)) }
            val value = CustomEnchantHook.data(rod)["fishing.double-chance"]?.toDoubleOrNull()
            ok(CustomEnchantHook.isEnabled && value == 7.0, "fishing.double-chance = $value")
        },
    )
}
