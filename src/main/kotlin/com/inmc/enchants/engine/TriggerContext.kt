package com.inmc.enchants.engine

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.event.Event
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/**
 * 한 번의 발동에 필요한 전부.
 *
 * | | 공격(ATTACK) | 방어(DEFENSE) | 그 외 |
 * |---|---|---|---|
 * | [self] | 때린 쪽 | 맞은 쪽 | 발동시킨 쪽 |
 * | [attacker] | 때린 쪽 | 때린 쪽 | 없음 |
 * | [victim] | 맞은 쪽 | 맞은 쪽 | 없음 |
 *
 * AE 와 같다 — `@Attacker` 는 방어에서도 "때린 쪽"이다. 그래서 방어 인첸트가 반격할 때
 * `@Attacker` 를 쓴다.
 *
 * [values] 는 발동 조건마다 다른 변수(`%damage%` · `%block type%` · `%exp%` …)다.
 * 리스너가 채우고 [Variables] 가 읽는다.
 */
class TriggerContext(
    val trigger: Trigger,
    val self: LivingEntity,
    val attacker: Entity? = null,
    val victim: Entity? = null,
    val event: Event? = null,
    val block: Block? = null,
    /** 투사체가 닿은 곳. `%hit location%`. */
    val hitLocation: Location? = null,
    /** 지속형(HELD·EFFECT_STATIC·SHIFT·SPRINT)을 **벗을 때** true. 효과가 되돌린다. */
    val removal: Boolean = false,
    val values: MutableMap<String, String> = HashMap(),
) {

    /** 지금 발동 중인 인첸트가 붙은 아이템과 그 칸. 엔진이 인첸트마다 바꿔 넣는다. */
    /**
     * 사건 순간의 블록 종류. [block] 은 살아 있는 블록이라 캐고 나면 공기가 된다 — `WAIT` 뒤의 효과(다시 심기)와
     * whitelist 는 이걸 본다.
     */
    val blockType: org.bukkit.Material? = block?.type

    var item: ItemStack? = null
    var slot: EquipmentSlot? = null
    var enchantId: String = ""
    var level: Int = 0

    /** 지금 레벨의 whitelist·blacklist. 블록 효과(부수기·드랍 배수)가 재질을 거른다. */
    var settings: com.inmc.enchants.enchant.LevelSettings = com.inmc.enchants.enchant.LevelSettings()

    /**
     * 드랍을 바꾸는 효과(MORE_DROPS·SMELT·TP_DROPS)가 여기 적는다. 채굴은 드랍이 [Event] 보다
     * **나중에** 생기므로(BlockDropItemEvent) 리스너가 이 값을 들고 있다가 그때 적용한다.
     */
    var dropMultiplier: Double = 1.0
    var smeltDrops: Boolean = false
    var teleportDrops: Boolean = false

    /** 효과가 CANCEL_EVENT 를 냈다. 취소할 수 없는 사건이면 리스너가 이 값을 대신 본다. */
    var cancelled: Boolean = false

    /** 맞은 자리에 있던 적(방어면 때린 쪽, 공격이면 맞은 쪽). 호위·반격 효과가 쓴다. */
    val opponent: Entity?
        get() = if (self == attacker) victim else attacker
}
