package com.inmc.enchants.verify

import com.inmc.enchants.Enchants
import com.inmc.enchants.effect.impl.Experience
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.enchant.EnchantLevel
import com.inmc.enchants.enchant.Group
import com.inmc.enchants.engine.EffectLine
import com.inmc.enchants.engine.EffectSpecs
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.gui.AdminMenu
import com.inmc.enchants.gui.AlchemistMenu
import com.inmc.enchants.gui.CatalogMenu
import com.inmc.enchants.gui.ConditionListMenu
import com.inmc.enchants.gui.EffectLineMenu
import com.inmc.enchants.gui.EffectListMenu
import com.inmc.enchants.gui.EnchantEditMenu
import com.inmc.enchants.gui.EnchantInfoMenu
import com.inmc.enchants.gui.EnchantLevelRef
import com.inmc.enchants.gui.EnchanterMenu
import com.inmc.enchants.gui.GiveMenu
import com.inmc.enchants.gui.GroupEditMenu
import com.inmc.enchants.gui.GroupListMenu
import com.inmc.enchants.gui.HeldItemMenu
import com.inmc.enchants.gui.LevelEditMenu
import com.inmc.enchants.gui.LevelListMenu
import com.inmc.enchants.gui.MainMenu
import com.inmc.enchants.gui.PickMenu
import com.inmc.enchants.gui.RuleMenu
import com.inmc.enchants.gui.ScrollAdminMenu
import com.inmc.enchants.gui.EventListMenu
import com.inmc.enchants.gui.BonusEffectsMenu
import com.inmc.enchants.gui.EffectsHolder
import com.inmc.enchants.set.BonusEffects
import kr.inmc.core.integration.CustomEnchantHook
import kr.inmc.core.gui.Icon
import com.inmc.enchants.gui.SettingsMenu
import com.inmc.enchants.gui.TinkererMenu
import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.item.ItemKind
import com.inmc.enchants.item.Keys
import com.inmc.enchants.item.Price
import com.inmc.enchants.verify.ItemChecks.Check
import com.inmc.enchants.verify.ItemChecks.Missing
import kr.inmc.core.util.Text
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.ItemStack

/**
 * 화면 검사. 화면을 실제로 열고 **진짜 클릭 사건**을 서버에 쏜다 — core 의 `MenuListener` 가 그걸
 * 받아 화면에 넘기므로, 플레이어가 누른 것과 같은 길을 지난다. 누른 뒤 어떤 화면이 열려 있는지,
 * 가방에 무엇이 들어왔는지를 본다.
 *
 * 화면이 그리다 던지면 `MenuListener` 가 삼키고 콘솔에 남긴다. 여기서는 "기대한 화면이 안 열렸다"로
 * 드러난다.
 *
 * 되돌릴 수 없는 버튼(지우기·리로드·검증 시작)은 누르지 않는다. 고치는 버튼은 검사용 인첸트·등급에만
 * 누르고 끝에 지운다.
 */
object MenuChecks {

    private fun ok(condition: Boolean, why: String): String? = if (condition) null else why

    private fun top(p: Player): Any? = p.openInventory.topInventory.holder

    private fun name(p: Player): String = top(p)?.javaClass?.simpleName ?: p.openInventory.topInventory.type.name

    /** 누른다. 막혔는지(바닐라가 아이템을 못 옮기게 했는지)를 돌려준다. */
    private fun click(p: Player, slot: Int, type: ClickType = ClickType.LEFT): Boolean {
        val action = if (type.isRightClick) InventoryAction.PICKUP_HALF else InventoryAction.PICKUP_ALL
        val event = InventoryClickEvent(p.openInventory, InventoryType.SlotType.CONTAINER, slot, type, action)
        Bukkit.getPluginManager().callEvent(event)
        return event.isCancelled
    }

    /** [slot] 을 누르면 [T] 가 열린다. 아니면 무엇이 열렸는지. */
    private inline fun <reified T> goes(p: Player, slot: Int, what: String): String? {
        click(p, slot)
        return ok(top(p) is T, "$slot 번을 누르면 $what 이어야 하는데 ${name(p)}")
    }

    private fun kinds(e: Enchants, p: Player): List<ItemKind?> = p.inventory.contents.map { e.items.kindOf(it) }

    private fun book(e: Enchants, id: String, level: Int): ItemStack {
        val def = e.registry.get(id) ?: throw Missing("배포 인첸트 '$id' 가 없다")
        return e.items.book(def, level, 100, 0)
    }

    /** 고치는 버튼을 누를 검사용 인첸트. 효과 한 줄을 갖는다. 끝에 [e].registry.remove 로 지운다. */
    private fun scratch(e: Enchants): EnchantDefinition {
        val line = EffectLine.parse("POTION:SPEED:1:60 @Self", EffectSpecs::shape).line ?: error("표본 줄을 못 읽었다")
        return EnchantDefinition(
            id = SCRATCH, display = SCRATCH, description = listOf("검증용"), appliesTo = "검",
            triggers = listOf(Trigger.ATTACK_MOB), group = e.groups.all().first().id, applies = listOf("ALL_SWORD"),
            levels = mapOf(1 to EnchantLevel(effects = listOf(line))),
        ).also(e.registry::put)
    }

    private const val SCRATCH = "zz_verify_menu"

    val ALL: List<Check> = listOf(
        Check("메인 → 일곱 화면 → 뒤로") { e, p ->
            val targets = listOf(
                20 to CatalogMenu::class, 21 to EnchanterMenu::class, 22 to AlchemistMenu::class,
                23 to TinkererMenu::class, 24 to HeldItemMenu::class, 31 to AdminMenu::class,
            )
            targets.firstNotNullOfOrNull { (slot, kind) ->
                MainMenu(e, p).show()
                click(p, slot)
                ok(kind.isInstance(top(p)), "$slot 번 → ${kind.simpleName} 대신 ${name(p)}")
                    ?: goes<MainMenu>(p, 45, "메인")
            }
        },
        Check("잠긴 칸은 막히고 닫기는 닫는다") { e, p ->
            MainMenu(e, p).show()
            ok(click(p, 0), "장식 칸 클릭이 막히지 않았다(유리판을 집는다)")
                ?: run { click(p, 53); ok(top(p) !is com.inmc.enchants.gui.Menu, "닫기 뒤에도 ${name(p)}") }
        },
        Check("도감: 페이지·등급 거르기·정보 화면과 되돌아오기") { e, p ->
            val catalog = CatalogMenu(e, p, back = null).also { it.show() }
            val first = catalog.inventory.getItem(0)?.let { Text.plain(it.effectiveName()) }
            click(p, 47)
            val second = catalog.inventory.getItem(0)?.let { Text.plain(it.effectiveName()) }
            click(p, 46)
            val group = e.groups.all().first()
            click(p, 48)
            val counted = catalog.inventory.getItem(48)?.lore()?.firstOrNull()?.let(Text::plain)
            val expected = e.registry.all().count { it.group.equals(group.id, ignoreCase = true) }.toString() + "개"
            ok(e.registry.size <= 45 || first != second, "다음 쪽을 눌러도 첫 칸이 그대로다")
                ?: ok(counted == expected, "등급 '${group.id}' 로 거른 수 $counted (기대 $expected)")
                ?: goes<EnchantInfoMenu>(p, 0, "인첸트 정보")
                ?: run { click(p, 45); ok(top(p) === catalog, "정보 화면의 뒤로가 같은 도감(거른 상태)으로 안 돌아왔다: ${name(p)}") }
        },
        Check("관리 → 인첸트·등급·지급·설정·검증 방식") { e, p ->
            fun from(check: () -> String?): String? {
                AdminMenu(e, p).show()
                return check()
            }
            from { goes<CatalogMenu>(p, 19, "편집용 도감") ?: goes<EnchantEditMenu>(p, 0, "인첸트 편집") ?: goes<CatalogMenu>(p, 45, "편집용 도감") }
                ?: from { goes<GroupListMenu>(p, 20, "등급 목록") ?: goes<GroupEditMenu>(p, 0, "등급 편집") ?: goes<GroupListMenu>(p, 45, "등급 목록") }
                ?: from { goes<GiveMenu>(p, 21, "아이템 지급") ?: goes<AdminMenu>(p, 45, "관리") }
                ?: from { goes<SettingsMenu>(p, 22, "설정") ?: goes<AdminMenu>(p, 45, "관리") }
                ?: from { goes<PickMenu<*>>(p, 23, "검증 방식 고르기") ?: goes<AdminMenu>(p, 45, "관리") }
                ?: from { goes<ScrollAdminMenu>(p, 26, "강화 스크롤") ?: goes<AdminMenu>(p, 45, "관리") }
        },
        Check("편집 화면을 끝까지 내려갔다 올라온다") { e, p ->
            val id = e.registry.get("lifesteal")?.id ?: throw Missing("배포 인첸트 'lifesteal' 이 없다")
            EnchantEditMenu(e, p, id).show()
            goes<RuleMenu>(p, 24, "규칙") ?: goes<EnchantEditMenu>(p, 45, "인첸트 편집")
                ?: goes<PickMenu<*>>(p, 22, "발동 조건 고르기") ?: goes<EnchantEditMenu>(p, 45, "인첸트 편집")
                ?: goes<LevelListMenu>(p, 25, "레벨 목록") ?: goes<LevelEditMenu>(p, 0, "레벨 편집")
                ?: goes<EffectListMenu>(p, 28, "효과 목록") ?: goes<EffectLineMenu>(p, 0, "효과 줄 편집")
                ?: goes<EffectListMenu>(p, 45, "효과 목록") ?: goes<LevelEditMenu>(p, 45, "레벨 편집")
                ?: goes<ConditionListMenu>(p, 29, "조건 목록") ?: goes<LevelEditMenu>(p, 45, "레벨 편집")
                ?: goes<LevelListMenu>(p, 45, "레벨 목록") ?: goes<EnchantEditMenu>(p, 45, "인첸트 편집")
        },
        Check("편집이 정의에 들어간다: 규칙(커스텀아이템 전용·뽑기 최대 레벨 포함)·레벨·효과 추가와 지우기") { e, p ->
            try {
                scratch(e)
                fun def() = e.registry.get(SCRATCH)!!
                RuleMenu(e, p, SCRATCH).show()
                val before = def().settings.removeable
                click(p, 23)
                click(p, 32)
                click(p, 33)
                val rule = ok(def().settings.removeable != before, "블랙 스크롤 규칙이 안 바뀌었다")
                    ?: ok(def().settings.customItemsOnly && def().settings.drawMaxLevel == 1,
                        "커스텀아이템 전용·뽑기 최대 레벨: ${def().settings.customItemsOnly} · ${def().settings.drawMaxLevel}")
                LevelListMenu(e, p, SCRATCH).show()
                click(p, 48)
                val added = def().levels.size
                click(p, 50)
                val levels = ok(added == 2 && def().levels.size == 1, "레벨 추가·지우기: $added → ${def().levels.size}")
                EffectListMenu(e, p, EnchantLevelRef(e, SCRATCH, 1)).show()
                click(p, 48)
                click(p, 0)
                click(p, 0)
                val lines = def().levels.getValue(1).effects.size
                val picked = ok(top(p) is EffectLineMenu && lines == 2, "효과 추가 뒤 ${name(p)} · ${lines}줄")
                click(p, 51)
                rule ?: levels ?: picked
                    ?: ok(def().levels.getValue(1).effects.size == 1 && top(p) is EffectListMenu, "줄 지우기 뒤 ${def().levels.getValue(1).effects.size}줄 · ${name(p)}")
            } finally {
                e.registry.remove(SCRATCH)
            }
        },
        Check("세트 효과 편집(커스텀아이템 세트 화면이 여는 것): 발동 조건 추가·지우기가 돌려진다") { e, p ->
            var saved: Map<String, Any?>? = null
            var backed = false
            val opened = CustomEnchantHook.editEffects(p, "zz_verify_set", emptyMap(), save = {}, back = {}) && top(p) is BonusEffectsMenu
            val holder = EffectsHolder("zz_verify_set", BonusEffects(), save = { saved = it }, back = { backed = true })
            BonusEffectsMenu(e, p, holder).show()
            goes<EventListMenu>(p, 20, "발동 조건 목록") ?: goes<PickMenu<*>>(p, 49, "발동 조건 고르기")
                ?: goes<LevelEditMenu>(p, 0, "효과 편집")
                ?: run {
                    val added = saved?.let { BonusEffects.of(it).events.keys }
                    EventListMenu(e, p, holder).show()
                    click(p, 0, ClickType.RIGHT)
                    val removed = saved?.let { BonusEffects.of(it).events.isEmpty() }
                    BonusEffectsMenu(e, p, holder).show()
                    click(p, 45)
                    ok(opened, "core 로 연 화면이 ${name(p)}")
                        ?: ok(added?.size == 1, "추가한 발동 조건이 안 돌아왔다: $added")
                        ?: ok(removed == true, "우클릭으로 안 지워졌다: $saved")
                        ?: ok(backed, "뒤로가 커스텀아이템 화면으로 안 갔다")
                }
        },
        Check("등급 편집: 순서 넛지가 등급에 들어간다") { e, p ->
            val id = "ZZ_VERIFY_MENU"
            try {
                e.groups.put(Group(id, id, "&7", 5))
                GroupEditMenu(e, p, id).show()
                click(p, 12)
                ok(e.groups.find(id)?.order == 6, "순서 ${e.groups.find(id)?.order} (기대 6)")
            } finally {
                e.groups.remove(id)
            }
        },
        Check("설정: 켜고 끄면 파일과 설정이 같이 바뀐다") { e, p ->
            val file = e.io.file("config.yml")
            val before = e.io.load(file).getBoolean("books.drag-drop")
            SettingsMenu(e, p).show()
            click(p, 3)
            val flipped = e.io.load(file).getBoolean("books.drag-drop")
            val live = e.config.dragDropApply
            click(p, 3)
            ok(flipped != before && live == flipped, "눌렀는데 파일 $before → $flipped · 설정 $live")
                ?: ok(e.io.load(file).getBoolean("books.drag-drop") == before && e.config.dragDropApply == before, "되돌리지 못했다")
        },
        Check("지급: 신비한 가루 버튼") { e, p ->
            GiveMenu(e, p).show()
            val index = ItemKind.entries.indexOf(ItemKind.MYSTERY_DUST)
            click(p, 10 + index % 7 + (index / 7) * 9)
            ok(ItemKind.MYSTERY_DUST in kinds(e, p), "가방에 신비한 가루가 없다")
        },
        Check("인챈터: 값을 치르고 미확인 부여서를 산다") { e, p ->
            val group = e.groups.all().firstOrNull { g -> e.registry.all().any { it.group.equals(g.id, ignoreCase = true) && !it.settings.disableInEnchanter } }
                ?: throw Missing("인챈터가 팔 등급이 없다")
            val price = Price.parse(group.enchanterPrice) ?: throw Missing("등급 '${group.id}' 의 인챈터 가격을 못 읽었다")
            if (price is Price.Money && !e.economy.isEnabled) throw Missing("가격이 돈인데 Vault 가 없다")
            Experience.set(p, 1_000_000)
            p.level = 1000
            val exp = Experience.total(p)
            EnchanterMenu(e, p).show()
            click(p, 10)
            ok(ItemKind.UNOPENED_BOOK in kinds(e, p), "미확인 부여서가 들어오지 않았다")
                ?: ok(price !is Price.Exp || Experience.total(p) == exp - price.points, "경험치 ${exp} → ${Experience.total(p)}")
        },
        Check("연금술사: 같은 부여서 두 장 → 한 단계 위") { e, p ->
            if (!e.config.combineEnabled) throw Missing("설정에서 부여서 합치기가 꺼져 있다")
            val menu = AlchemistMenu(e, p).also { it.show() }
            val blocked = click(p, AlchemistMenu.LEFT)
            menu.inventory.setItem(AlchemistMenu.LEFT, book(e, "lifesteal", 1))
            menu.inventory.setItem(AlchemistMenu.RIGHT, book(e, "lifesteal", 1))
            menu.refresh()
            val preview = e.items.bookInfo(menu.inventory.getItem(AlchemistMenu.OUT))?.level
            click(p, AlchemistMenu.OUT)
            ok(!blocked, "넣는 칸 클릭이 막혔다")
                ?: ok(preview == 2, "미리보기 레벨 $preview")
                ?: ok(p.inventory.contents.any { e.items.bookInfo(it)?.level == 2 }, "2레벨 부여서가 가방에 없다")
                ?: ok(menu.inventory.getItem(AlchemistMenu.LEFT) == null && menu.inventory.getItem(AlchemistMenu.RIGHT) == null, "재료가 남았다")
        },
        Check("땜장이: 부여서 → 비밀 가루") { e, p ->
            val menu = TinkererMenu(e, p).also { it.show() }
            menu.inventory.setItem(TinkererMenu.INPUT.first(), book(e, "lifesteal", 1))
            menu.refresh()
            click(p, 49)
            ok(ItemKind.SECRET_DUST in kinds(e, p), "비밀 가루가 들어오지 않았다")
                ?: ok(menu.inventory.getItem(TinkererMenu.INPUT.first()) == null, "넣은 부여서가 남았다")
        },
        Check("땜장이 되돌리기: 받은 가루를 내면 부여서가 돌아오고, 가루가 없으면 안 된다") { e, p ->
            if (e.config.tinkerRestoreHours <= 0) throw Missing("되돌리기가 꺼져 있다(tinkerer.restore-hours)")
            val menu = TinkererMenu(e, p).also { it.show() }
            menu.inventory.setItem(TinkererMenu.INPUT.first(), book(e, "lifesteal", 1))
            menu.refresh()
            click(p, 49)
            p.closeInventory()
            val trade = e.tinkerLog.trades(p.uniqueId).firstOrNull() ?: return@Check "기록이 남지 않았다"
            try {
                p.inventory.removeItem(*trade.dusts.map { it.clone() }.toTypedArray())
                val refused = e.tinkerLog.restore(p, trade.at)
                for (dust in trade.dusts) p.inventory.addItem(dust.clone())
                val result = e.tinkerLog.restore(p, trade.at)
                ok(refused == com.inmc.enchants.item.TinkerLog.Result.NO_DUST, "가루 없이 되돌려짐: $refused")
                    ?: ok(result == com.inmc.enchants.item.TinkerLog.Result.RESTORED, "되돌리기: $result")
                    ?: ok(ItemKind.SECRET_DUST !in kinds(e, p), "받은 가루가 남았다")
                    ?: ok(ItemKind.BOOK in kinds(e, p), "부여서가 안 돌아왔다")
                    ?: ok(e.tinkerLog.trades(p.uniqueId).none { it.at == trade.at }, "기록이 남았다")
            } finally {
                e.tinkerLog.discard(p.uniqueId, trade.at)
            }
        },
        Check("강화 스크롤: 종류 묶음 규칙에서 금지 인첸트를 켜면 규칙에 들어간다") { e, p ->
            // 종류 묶음 고르기의 0번은 첫 보기(ALL_SWORD), 금지 고르기의 0번은 첫 인첸트다(종류 묶음은 견본이 없어 전부 보인다).
            val key = Applicability.PRESETS.first().first
            val first = e.scrolls.all().firstOrNull() ?: throw Missing("인첸트가 없다")
            fun on() = first.id in e.scrolls.rules()[key].orEmpty()
            val existed = key in e.scrolls.rules()
            val had = on()
            try {
                ScrollAdminMenu(e, p).show()
                goes<PickMenu<*>>(p, 50, "종류 묶음 고르기")
                    ?: goes<PickMenu<*>>(p, 0, "금지 인첸트 고르기")
                    ?: run { click(p, 0); ok(on() != had, "누른 인첸트가 규칙에 안 들어갔다") }
            } finally {
                if (!existed) e.scrolls.removeRule(key) else if (on() != had) e.scrolls.toggle(key, first.id)
            }
        },
        Check("내 아이템: 관리자 붙이기·떼기") { e, p ->
            val first = e.registry.all().firstOrNull() ?: throw Missing("인첸트가 없다")
            p.inventory.setItemInMainHand(ItemStack(Material.DIAMOND_SWORD))
            HeldItemMenu(e, p).show()
            goes<PickMenu<*>>(p, 48, "붙일 인첸트") ?: goes<PickMenu<*>>(p, 0, "레벨")
                ?: run {
                    click(p, 0)
                    ok(EnchantStorage.level(p.inventory.itemInMainHand, first.id) == 1 && top(p) is HeldItemMenu, "붙이기 뒤 ${EnchantStorage.read(p.inventory.itemInMainHand)} · ${name(p)}")
                }
                ?: run {
                    click(p, 18)
                    click(p, 11)
                    ok(!EnchantStorage.has(p.inventory.itemInMainHand), "떼기 뒤 ${EnchantStorage.read(p.inventory.itemInMainHand)}")
                }
        },
        Check("내 아이템: 영혼 꺼내기") { e, p ->
            val sword = ItemStack(Material.DIAMOND_SWORD).also {
                EnchantStorage.setFlag(it, Keys.SOUL_TRACKER, true)
                EnchantStorage.setInt(it, Keys.SOULS, 50)
            }
            p.inventory.setItemInMainHand(sword)
            HeldItemMenu(e, p).show()
            click(p, 50)
            ok(e.souls.souls(p.inventory.itemInMainHand) == 0, "손의 영혼 ${e.souls.souls(p.inventory.itemInMainHand)}")
                ?: ok(p.inventory.contents.any { e.items.kindOf(it) == ItemKind.SOUL_GEM && EnchantStorage.int(it, Keys.SOULS) == 50 }, "영혼석(50)이 없다")
        },
    )
}
