package com.inmc.enchants.item

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.Applicability
import com.inmc.enchants.enchant.EnchantDefinition
import kr.inmc.core.util.Text
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.Random

/**
 * 인첸트 아이템을 **다른 아이템 위에 쓰는** 규칙 전부. 끌어다 놓기·모루·연금술사 화면이 모두 여기를 부른다
 * — 경로마다 규칙을 따로 두면 한쪽만 고쳐진다.
 *
 * 입력 아이템을 직접 바꾸지 않는다. [Result] 로 "쓰고 난 뒤의 대상"과 "쓰고 남은 것"을 돌려주고
 * 부르는 쪽이 칸에 넣는다 — 모루·끌어다 놓기·화면은 아이템을 두는 자리가 전부 다르다.
 */
class ItemUses(private val e: Enchants) {

    private val random = Random()

    /**
     * @param target 쓰고 난 대상. null 이면 사라졌다(파괴).
     * @param used 쓴 아이템이 한 개 줄었는가.
     * @param returned 덤으로 돌려줄 아이템(블랙 스크롤이 뽑은 부여서 등).
     */
    data class Result(
        val outcome: Outcome,
        val target: ItemStack?,
        val used: Boolean,
        val returned: ItemStack? = null,
        val enchant: EnchantDefinition? = null,
        val level: Int = 0,
        /** 메시지의 `{enchant}`. 없으면 [enchant]·[level] 로 만든다(바닐라 인첸트는 정의가 없다). */
        val label: String? = null,
    )

    enum class Outcome(val message: String, val applied: Boolean) {
        APPLIED("book-applied", true),
        FAILED("book-failed", false),
        DESTROYED("book-destroyed", false),
        PROTECTED("book-protected", false),
        WRONG_ITEM("book-wrong-item", false),
        LOCKED("book-locked", false),
        ALREADY("book-already", false),
        NO_SLOTS("book-no-slots", false),
        MISSING_REQUIRED("book-missing-required", false),
        CONFLICT("book-conflict", false),
        COMBINED("book-combined", true),
        MAX_LEVEL("book-max-level", false),
        DUST_APPLIED("dust-applied", true),
        DUST_WRONG_GROUP("dust-wrong-group", false),
        DUST_FULL("dust-full", false),
        WHITE_SCROLLED("white-scroll-applied", true),
        BLACK_SCROLLED("black-scroll-applied", true),
        NOTHING_TO_EXTRACT("black-scroll-empty", false),
        REROLLED("random-scroll-applied", true),
        TRANSMOGGED("transmog-applied", true),
        SLOTS_ADDED("slots-added", true),
        SLOTS_MAXED("slots-maxed", false),
        ORB_APPLIED("orb-applied", true),
        ORB_WEAKER("orb-weaker", false),
        TRACKER_APPLIED("tracker-applied", true),
        SOULS_MOVED("souls-moved", true),
        NO_SOUL_TRACKER("souls-no-tracker", false),
        NOT_APPLICABLE("item-not-applicable", false),
        SCROLL_UP("scroll-up", true),
        SCROLL_KEPT("scroll-kept", false),
        SCROLL_DOWN("scroll-down", false),
        SCROLL_LOST("scroll-lost", false),
        SCROLL_PROTECTED("scroll-protected", false),
        SCROLL_BLACKLISTED("scroll-blacklisted", false),
    }

    /** [tool] 을 [target] 위에 쓴다. 쓸 수 없는 짝이면 null(부르는 쪽은 평소대로 둔다). */
    fun use(player: Player?, tool: ItemStack, target: ItemStack): Result? {
        if (target.type.isAir) return null
        val kind = e.items.kindOf(tool) ?: return null
        val targetKind = e.items.kindOf(target)
        // 부여서는 한 장씩 성공률이 다르다. 겹친 더미 위에 쓰면 한 번에 전부가 바뀐다.
        if (targetKind == ItemKind.BOOK && target.amount != 1) return null
        return when (kind) {
            ItemKind.BOOK -> if (targetKind == ItemKind.BOOK) combine(tool, target) else if (targetKind == null) applyBook(player, tool, target) else null
            ItemKind.MAGIC_DUST -> if (targetKind == ItemKind.BOOK) dust(tool, target) else null
            ItemKind.RANDOM_SCROLL -> if (targetKind == ItemKind.BOOK) reroll(tool, target) else null
            ItemKind.LEVEL_SCROLL -> if (targetKind == null) levelUp(player, tool, target) else null
            ItemKind.SOUL_GEM -> when (targetKind) {
                ItemKind.SOUL_GEM -> mergeGems(tool, target)
                null -> soulsIntoItem(tool, target)
                else -> null
            }
            else -> if (targetKind == null) onItem(player, kind, tool, target) else null
        }
    }

    // --- 부여서 ---------------------------------------------------------------------------

    /**
     * 붙이기. 판정 순서가 규칙이다: 붙을 수 있는가 → 이미 있는가 → 칸 → 필요·충돌 → **그 다음에** 성공 굴림.
     * 붙을 수 없는 아이템에 굴림을 하면 실패 판정으로 아이템이 부서질 수 있다.
     *
     * @param certain 굴리지 않고 성공한 모습을 낸다(모루 미리보기).
     */
    /** `limitation` 에 걸린 아이템인가. 설명 글은 색을 떼고 본다. */
    fun locked(target: ItemStack): Boolean {
        val config = e.config
        if (config.lockLore.isNotEmpty() && target.lore().orEmpty().any { Text.plain(it).contains(config.lockLore) }) return true
        val key = config.lockTag.takeIf { it.isNotEmpty() }?.let(NamespacedKey::fromString) ?: return false
        return target.hasItemMeta() && target.itemMeta.persistentDataContainer.has(key)
    }

    fun applyBook(player: Player?, book: ItemStack, target: ItemStack, certain: Boolean = false): Result {
        val info = e.items.bookInfo(book) ?: return Result(Outcome.NOT_APPLICABLE, target, used = false)
        val def = info.def
        val keep = Result(Outcome.WRONG_ITEM, target, used = false, enchant = def, level = info.level)
        if (locked(target)) return keep.copy(outcome = Outcome.LOCKED)
        if (!fits(def, target)) return keep
        val current = EnchantStorage.read(target)
        val have = current[def.id]
        if (have != null && have >= info.level) return keep.copy(outcome = Outcome.ALREADY)
        if (have == null && !e.slots.hasRoom(target, player)) return keep.copy(outcome = Outcome.NO_SLOTS)
        unmet(def, current)?.let { return keep.copy(outcome = it) }

        val result = target.clone()
        if (certain || roll(info.success)) {
            for (removed in def.settings.removedEnchants) current.remove(removed)
            current[def.id] = info.level
            EnchantStorage.write(result, current)
            e.lore.render(result)
            return Result(Outcome.APPLIED, result, used = true, enchant = def, level = info.level)
        }
        if (!e.config.destroyOnFail || !roll(info.destroy)) return Result(Outcome.FAILED, target, used = true, enchant = def, level = info.level)
        if (EnchantStorage.flag(result, Keys.WHITE_SCROLL)) {
            EnchantStorage.setFlag(result, Keys.WHITE_SCROLL, false)
            e.lore.render(result)
            return Result(Outcome.PROTECTED, result, used = true, enchant = def, level = info.level)
        }
        if (!e.config.destroyItem) return Result(Outcome.FAILED, target, used = true, enchant = def, level = info.level)
        return Result(Outcome.DESTROYED, null, used = true, enchant = def, level = info.level)
    }

    /** 붙는 곳에 맞는가. 상위 곡괭이 전용 같은 것은 커스텀 아이템 플러그인이 알아보는 아이템에만. */
    private fun fits(def: EnchantDefinition, target: ItemStack): Boolean {
        if (!Applicability.matchesAny(def.applies, target.type.name, e.config.appliesGroups)) return false
        return !def.settings.customItemsOnly || e.customItems.identify(target) != null
    }

    /** 필요한 인첸트(진화 사슬의 아래 단계)가 없거나 함께 붙을 수 없는 것이 있으면 그 결과. 괜찮으면 null. */
    private fun unmet(def: EnchantDefinition, current: Map<String, Int>): Outcome? {
        for (need in def.settings.requiredEnchants) {
            val (id, level) = need.split(':').let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 1) }
            if ((current[id] ?: 0) < level) return Outcome.MISSING_REQUIRED
        }
        if (def.settings.notApplyableWith.any { it in current } || current.keys.any { other -> e.registry.get(other)?.settings?.notApplyableWith?.contains(def.id) == true }) {
            return Outcome.CONFLICT
        }
        return null
    }

    /**
     * 같은 인첸트·같은 레벨 두 장 → 한 단계 위 한 장. 성공률은 두 장의 평균(설정이 켜져 있으면),
     * 아니면 새로 뽑는다.
     */
    fun combine(book: ItemStack, other: ItemStack): Result? {
        if (!e.config.combineEnabled) return null
        val a = e.items.bookInfo(book) ?: return null
        val b = e.items.bookInfo(other) ?: return null
        if (a.def.id != b.def.id || a.level != b.level) return null
        if (a.level >= a.def.maxLevel) return Result(Outcome.MAX_LEVEL, other, used = false, enchant = a.def, level = a.level)
        val (success, destroy) = if (e.config.combineUseChances) ((a.success + b.success) / 2 to (a.destroy + b.destroy) / 2) else e.items.rates()
        val merged = e.items.book(a.def, a.level + 1, success, destroy)
        return Result(Outcome.COMBINED, merged, used = true, enchant = a.def, level = a.level + 1)
    }

    fun dust(dust: ItemStack, book: ItemStack): Result {
        val info = e.items.bookInfo(book) ?: return Result(Outcome.NOT_APPLICABLE, book, used = false)
        val group = e.items.group(dust)
        if (group != null && !group.id.equals(info.def.group, ignoreCase = true)) return Result(Outcome.DUST_WRONG_GROUP, book, used = false)
        if (info.success >= 100) return Result(Outcome.DUST_FULL, book, used = false)
        val raised = e.items.book(info.def, info.level, (info.success + e.items.amount(dust)).coerceAtMost(100), info.destroy)
        return Result(Outcome.DUST_APPLIED, raised, used = true, enchant = info.def, level = info.level)
    }

    fun reroll(scroll: ItemStack, book: ItemStack): Result {
        val info = e.items.bookInfo(book) ?: return Result(Outcome.NOT_APPLICABLE, book, used = false)
        val group = e.items.group(scroll)
        if (group != null && !group.id.equals(info.def.group, ignoreCase = true)) return Result(Outcome.DUST_WRONG_GROUP, book, used = false)
        val (success, destroy) = e.items.rates()
        return Result(Outcome.REROLLED, e.items.book(info.def, info.level, success, destroy), used = true)
    }

    // --- 강화 스크롤 ---------------------------------------------------------------------------

    /**
     * 강화 스크롤. 판정 순서가 규칙이다 — 굴리기 전에 걸리는 것은 스크롤을 쓰지 않는다.
     *
     * 금지 표시 → 금지 목록(`scrolls.yml`) → 없는 인첸트면 부여서와 같은 붙이기 검사(붙는 곳·칸·필요·충돌) /
     * 있는 인첸트면 최대 레벨 → 성공 굴림(+1, 없으면 1레벨로 붙는다) → 실패하면 하락 굴림(−1, 1레벨이면 사라진다).
     * 하락은 화이트 스크롤이 한 번 막는다. 이미 붙은 것을 올릴 때는 필요·충돌을 다시 보지 않는다 — 붙을 때 봤고,
     * 진화 사슬의 위 단계는 붙으면서 아래 단계를 지웠으므로 다시 보면 영영 못 올린다.
     */
    fun levelUp(player: Player?, scroll: ItemStack, target: ItemStack): Result {
        val info = e.items.scrollInfo(scroll) ?: return Result(Outcome.NOT_APPLICABLE, target, used = false)
        val enchant = info.target
        val have = e.scrolls.level(target, enchant)
        val keep = Result(Outcome.WRONG_ITEM, target, used = false, label = e.scrolls.label(enchant))
        if (locked(target)) return keep.copy(outcome = Outcome.LOCKED)
        if (e.scrolls.blocked(target, enchant)) return keep.copy(outcome = Outcome.SCROLL_BLACKLISTED)
        if (have >= e.scrolls.maxLevel(enchant)) return keep.copy(outcome = Outcome.MAX_LEVEL)
        if (have == 0) attachable(player, enchant, target)?.let { return keep.copy(outcome = it) }

        val result = target.clone()
        if (roll(info.success)) {
            setLevel(result, enchant, have + 1)
            return Result(Outcome.SCROLL_UP, result, used = true, label = e.scrolls.label(enchant, have + 1))
        }
        if (have == 0 || !roll(info.downgrade)) return Result(Outcome.SCROLL_KEPT, target, used = true, label = keep.label)
        if (EnchantStorage.flag(result, Keys.WHITE_SCROLL)) {
            EnchantStorage.setFlag(result, Keys.WHITE_SCROLL, false)
            e.lore.render(result)
            return Result(Outcome.SCROLL_PROTECTED, result, used = true, label = keep.label)
        }
        setLevel(result, enchant, have - 1)
        return if (have - 1 > 0) Result(Outcome.SCROLL_DOWN, result, used = true, label = e.scrolls.label(enchant, have - 1))
        else Result(Outcome.SCROLL_LOST, result, used = true, label = keep.label)
    }

    /** 없는 인첸트를 새로 붙일 수 있는가. 붙일 수 있으면 null. */
    private fun attachable(player: Player?, enchant: ScrollEnchant, target: ItemStack): Outcome? = when (enchant) {
        is ScrollEnchant.Custom -> when {
            !fits(enchant.def, target) -> Outcome.WRONG_ITEM
            !e.slots.hasRoom(target, player) -> Outcome.NO_SLOTS
            else -> unmet(enchant.def, EnchantStorage.read(target))
        }
        is ScrollEnchant.Vanilla -> when {
            !enchant.enchantment.canEnchantItem(target) -> Outcome.WRONG_ITEM
            target.enchantments.keys.any { it != enchant.enchantment && it.conflictsWith(enchant.enchantment) } -> Outcome.CONFLICT
            else -> null
        }
    }

    /**
     * [stack] 의 [enchant] 를 [level] 로 바꾼다(0 이하면 뗀다). 우리 인첸트를 새로 붙일 때는 진화 사슬의 아래 단계(`removed-enchants`)를
     * 지운다. 로어도 다시 그린다. **붙일 수 있는지는 보지 않는다** — 강화 스크롤과 관리자 붙이기·떼기가 부른다.
     */
    fun setLevel(stack: ItemStack, enchant: ScrollEnchant, level: Int) {
        when (enchant) {
            is ScrollEnchant.Custom -> {
                val current = EnchantStorage.read(stack)
                val id = enchant.def.id
                if (level <= 0) current.remove(id) else {
                    if (id !in current) for (removed in enchant.def.settings.removedEnchants) current.remove(removed)
                    current[id] = level
                }
                EnchantStorage.write(stack, current)
            }
            is ScrollEnchant.Vanilla ->
                if (level <= 0) stack.removeEnchantment(enchant.enchantment) else stack.addUnsafeEnchantment(enchant.enchantment, level)
        }
        e.lore.render(stack)
    }

    // --- 관리자: 손에 든 것에 바로 붙이기·떼기 --------------------------------------------------

    /** 손에 든 것에 [def] 를 [level] 로(최대 레벨까지). 칸·붙는 곳·확률을 보지 않는다. 명령어와 `내 아이템` 화면이 부른다. */
    fun adminSet(player: Player, def: EnchantDefinition, level: Int) {
        val stack = player.inventory.itemInMainHand
        val clamped = level.coerceIn(1, def.maxLevel.coerceAtLeast(1))
        setLevel(stack, ScrollEnchant.Custom(def), clamped)
        player.inventory.setItemInMainHand(stack)
        e.statics.refresh(player)
        e.messages.send(player, "admin-enchant-added", e.ph().enchant(e.display(def, clamped)))
    }

    /** 손에 든 것에서 [id] 를 뗀다. 정의가 지워진 id 도 뗀다. 붙어 있지 않았으면 false. */
    fun adminRemove(player: Player, id: String): Boolean {
        val stack = player.inventory.itemInMainHand
        val key = id.lowercase()
        val enchants = EnchantStorage.read(stack)
        if (enchants.remove(key) == null) return false
        EnchantStorage.write(stack, enchants)
        e.lore.render(stack)
        player.inventory.setItemInMainHand(stack)
        e.statics.refresh(player)
        e.messages.send(player, "admin-enchant-removed", e.ph().enchant(e.registry.get(key)?.let { e.lore.name(it) } ?: key))
        return true
    }

    // --- 아이템 위에 쓰는 것 ------------------------------------------------------------------

    private fun onItem(player: Player?, kind: ItemKind, tool: ItemStack, target: ItemStack): Result? {
        val result = target.clone()
        return when (kind) {
            ItemKind.WHITE_SCROLL -> {
                if (EnchantStorage.flag(result, Keys.WHITE_SCROLL)) return Result(Outcome.ALREADY, target, used = false)
                EnchantStorage.setFlag(result, Keys.WHITE_SCROLL, true)
                e.lore.render(result)
                Result(Outcome.WHITE_SCROLLED, result, used = true)
            }
            ItemKind.BLACK_SCROLL -> {
                val enchants = EnchantStorage.read(result)
                val removable = enchants.keys.filter { e.registry.get(it)?.settings?.removeable != false }
                if (removable.isEmpty()) return Result(Outcome.NOTHING_TO_EXTRACT, target, used = false)
                val id = removable[random.nextInt(removable.size)]
                val level = enchants.remove(id) ?: 1
                EnchantStorage.write(result, enchants)
                e.lore.render(result)
                val def = e.registry.get(id)!!
                val success = e.items.amount(tool).takeIf { it > 0 } ?: e.items.roll(e.config.blackScrollSuccess)
                Result(Outcome.BLACK_SCROLLED, result, used = true, returned = e.items.book(def, level, success, 0), enchant = def, level = level)
            }
            ItemKind.TRANSMOG_SCROLL -> {
                EnchantStorage.setFlag(result, Keys.TRANSMOG, true)
                e.lore.render(result)
                Result(Outcome.TRANSMOGGED, result, used = true)
            }
            ItemKind.SLOT_INCREASER -> {
                val extra = EnchantStorage.int(result, Keys.EXTRA_SLOTS)
                if (extra >= e.config.maxExtraSlots) return Result(Outcome.SLOTS_MAXED, target, used = false)
                EnchantStorage.setInt(result, Keys.EXTRA_SLOTS, (extra + e.items.amount(tool).coerceAtLeast(1)).coerceAtMost(e.config.maxExtraSlots))
                e.lore.render(result)
                Result(Outcome.SLOTS_ADDED, result, used = true)
            }
            ItemKind.ORB -> {
                val orb = e.items.orbKind(tool) ?: return null
                if (!Applicability.matchesAny(orb.applies, result.type.name, e.config.appliesGroups)) return Result(Outcome.WRONG_ITEM, target, used = false)
                val slots = e.items.amount(tool)
                if (EnchantStorage.int(result, Keys.ORB_SLOTS) >= slots) return Result(Outcome.ORB_WEAKER, target, used = false)
                EnchantStorage.setInt(result, Keys.ORB_SLOTS, slots)
                e.lore.render(result)
                Result(Outcome.ORB_APPLIED, result, used = true)
            }
            ItemKind.STATTRAK, ItemKind.MOBTRAK, ItemKind.BLOCKTRAK, ItemKind.FISHTRAK -> {
                val tracker = ItemKind.TRACKERS.getValue(kind)
                if (result.itemMeta.persistentDataContainer.has(tracker.key)) return Result(Outcome.ALREADY, target, used = false)
                EnchantStorage.setInt(result, tracker.key, 0)
                e.lore.render(result)
                Result(Outcome.TRACKER_APPLIED, result, used = true)
            }
            ItemKind.SOUL_TRACKER -> {
                if (EnchantStorage.flag(result, Keys.SOUL_TRACKER)) return Result(Outcome.ALREADY, target, used = false)
                EnchantStorage.setFlag(result, Keys.SOUL_TRACKER, true)
                EnchantStorage.setInt(result, Keys.SOULS, EnchantStorage.int(result, Keys.SOULS))
                e.lore.render(result)
                Result(Outcome.TRACKER_APPLIED, result, used = true)
            }
            else -> null
        }
    }

    /** 영혼석 → 아이템. 아이템에 영혼 추적기가 있어야 한다. 영혼석은 비워져 사라진다. */
    private fun soulsIntoItem(gem: ItemStack, target: ItemStack): Result {
        if (!EnchantStorage.flag(target, Keys.SOUL_TRACKER)) return Result(Outcome.NO_SOUL_TRACKER, target, used = false)
        val result = target.clone()
        e.souls.add(result, EnchantStorage.int(gem, Keys.SOULS))
        return Result(Outcome.SOULS_MOVED, result, used = true)
    }

    private fun mergeGems(gem: ItemStack, other: ItemStack): Result =
        Result(Outcome.SOULS_MOVED, e.items.soulGem(EnchantStorage.int(gem, Keys.SOULS) + EnchantStorage.int(other, Keys.SOULS)), used = true)

    /** 들고 있는 아이템의 영혼을 영혼석으로 꺼낸다(`영혼 꺼내기`). 꺼낼 게 없으면 null. */
    fun withdrawSouls(stack: ItemStack): Pair<ItemStack, ItemStack>? {
        val souls = EnchantStorage.int(stack, Keys.SOULS)
        if (souls <= 0 || !EnchantStorage.flag(stack, Keys.SOUL_TRACKER)) return null
        val emptied = stack.clone()
        EnchantStorage.setInt(emptied, Keys.SOULS, 0)
        e.lore.render(emptied)
        return emptied to e.items.soulGem(souls)
    }

    // --- 손에 들고 쓰는 것(우클릭) --------------------------------------------------------------

    /** 미확인 부여서를 연다. 그 등급에 인첸트가 없으면 null. */
    fun open(unopened: ItemStack): ItemStack? {
        val group = e.items.group(unopened) ?: return null
        val (def, level) = e.items.randomEnchant(group) ?: return null
        return e.items.randomBook(def, level)
    }

    /** 비밀 가루를 연다 — 등급의 확률로 마법 가루, 아니면 신비한 가루. */
    fun openSecret(dust: ItemStack): ItemStack {
        val group = e.items.group(dust) ?: return e.items.mysteryDust()
        if (random.nextDouble() * 100.0 >= group.secretDustChance) return e.items.mysteryDust()
        return e.items.magicDust(group, e.items.roll(group.dustMin..group.dustMax))
    }

    /** 이름표로 이름을 바꾼다. 색 코드는 관리자만. */
    fun rename(target: ItemStack, name: String, colors: Boolean): ItemStack {
        val result = target.clone()
        val text = if (colors) name else kr.inmc.core.util.Text.plain(kr.inmc.core.util.Text.render(name))
        result.editMeta { meta ->
            meta.displayName(kr.inmc.core.util.Text.renderFlat(text))
            meta.persistentDataContainer.remove(Keys.CUSTOM_NAME)
        }
        e.lore.render(result)
        return result
    }

    private fun roll(chance: Int): Boolean = chance >= 100 || (chance > 0 && random.nextInt(100) < chance)

    /** 한 개만 쓴다. 남은 것이 없으면 null. */
    fun consumeOne(stack: ItemStack): ItemStack? = if (stack.amount <= 1) null else stack.clone().also { it.amount -= 1 }

}
