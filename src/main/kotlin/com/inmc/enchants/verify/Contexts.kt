package com.inmc.enchants.verify

import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.engine.TriggerContext
import com.inmc.enchants.engine.Variables
import com.google.common.base.Function
import com.destroystokyo.paper.event.player.PlayerJumpEvent
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.damage.DamageSource
import org.bukkit.damage.DamageType
import org.bukkit.entity.Entity
import org.bukkit.entity.FishHook
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityDamageEvent.DamageModifier
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerToggleSprintEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import java.util.EnumMap

/**
 * 발동 조건마다 **리스너가 만드는 것과 같은 모양의** 맥락을 만든다. 효과·인첸트 검증은
 * 리스너를 거치지 않고 효과 줄을 곧바로 돌리므로, 여기가 리스너의 역할을 대신한다.
 *
 * 역할 배치는 [TriggerContext] 의 표와 같다 — 공격이면 검증하는 사람이 때리고 무대의 몹이 맞는다,
 * 방어면 몹이 때리고 사람이 맞는다.
 */
object Contexts {

    /** 검증하는 사람이 때린 쪽. `@Victim` 이 무대의 몹이다. */
    val ATTACK: Set<Trigger> = setOf(
        Trigger.ATTACK, Trigger.ATTACK_MOB, Trigger.CUSTOM_MOB_DEFENSE, Trigger.SHOOT, Trigger.SHOOT_MOB, Trigger.ARROW_HIT,
        Trigger.KILL, Trigger.KILL_MOB, Trigger.KILL_PLAYER, Trigger.RIGHT_CLICK_ENTITY, Trigger.HOOK_ENTITY,
    )

    /** 검증하는 사람이 맞은 쪽. `@Attacker` 가 무대의 몹이다. */
    val DEFENSE: Set<Trigger> = setOf(
        Trigger.DEFENSE, Trigger.DEFENSE_MOB, Trigger.DEFENSE_PROJECTILE, Trigger.DEFENSE_MOB_PROJECTILE,
        Trigger.SHIELD_BLOCK, Trigger.DEATH,
    )

    /** 때린 쪽이 없는 피해. */
    val ENVIRONMENT: Map<Trigger, DamageCause> = mapOf(
        Trigger.FALL_DAMAGE to DamageCause.FALL,
        Trigger.FIRE to DamageCause.FIRE,
        Trigger.EXPLOSION to DamageCause.BLOCK_EXPLOSION,
        Trigger.PASSIVE_DEATH to DamageCause.LIGHTNING,
        Trigger.ELYTRA_FLY_DAMAGE to DamageCause.FLY_INTO_WALL,
    )

    val FISHING: Set<Trigger> = setOf(Trigger.ROD_CAST, Trigger.BITE_HOOK, Trigger.CATCH_FISH, Trigger.HOOK_ENTITY)

    /**
     * @param command COMMAND 발동이면 레벨이 기다리는 명령어.
     */
    fun of(
        trigger: Trigger,
        player: Player,
        mob: LivingEntity,
        block: Block?,
        item: ItemStack,
        removal: Boolean = false,
        command: String? = null,
    ): TriggerContext {
        val values = HashMap<String, String>()
        return when {
            trigger in ATTACK -> {
                val event: Event? = when (trigger) {
                    Trigger.KILL, Trigger.KILL_MOB, Trigger.KILL_PLAYER ->
                        EntityDeathEvent(mob, source(player), mutableListOf(ItemStack(Material.ROTTEN_FLESH)), 5)
                    Trigger.RIGHT_CLICK_ENTITY -> PlayerInteractEntityEvent(player, mob, EquipmentSlot.HAND)
                    Trigger.HOOK_ENTITY -> fish(player, mob, PlayerFishEvent.State.CAUGHT_ENTITY)
                    else -> damage(player, mob)
                }
                (event as? EntityDamageEvent)?.let { values.putAll(damageValues(it)) }
                TriggerContext(trigger, player, player, mob, event, block, mob.location, removal, values)
            }
            trigger in DEFENSE -> {
                val event: Event = if (trigger == Trigger.DEATH) {
                    EntityResurrectEvent(player).apply { isCancelled = true }
                } else {
                    damage(mob, player)
                }
                (event as? EntityDamageEvent)?.let { values.putAll(damageValues(it)) }
                TriggerContext(trigger, player, mob, player, event, block, player.location, removal, values)
            }
            trigger in ENVIRONMENT -> {
                val event = EntityDamageEvent(player, ENVIRONMENT.getValue(trigger), source(null), 4.0)
                values.putAll(damageValues(event))
                TriggerContext(trigger, player, null, player, event, block, null, removal, values)
            }
            else -> {
                val event: Event? = when (trigger) {
                    Trigger.MINING -> block?.let { BlockBreakEvent(it, player) }
                    Trigger.RIGHT_CLICK, Trigger.HORN -> PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF, EquipmentSlot.HAND)
                    Trigger.SWING -> PlayerInteractEvent(player, Action.LEFT_CLICK_AIR, item, null, BlockFace.SELF, EquipmentSlot.HAND)
                    Trigger.EAT -> PlayerItemConsumeEvent(player, ItemStack(Material.BREAD), EquipmentSlot.HAND)
                    Trigger.ITEM_BREAK -> PlayerItemBreakEvent(player, item)
                    Trigger.JUMP -> PlayerJumpEvent(player, player.location, player.location.clone().add(0.0, 0.5, 0.0))
                    Trigger.SHIFT -> PlayerToggleSneakEvent(player, !removal)
                    Trigger.SPRINT -> PlayerToggleSprintEvent(player, !removal)
                    Trigger.ELYTRA_FLY -> EntityToggleGlideEvent(player, true)
                    Trigger.COMMAND -> PlayerCommandPreprocessEvent(player, "/" + (command ?: "verify"))
                    Trigger.ROD_CAST -> fish(player, null, PlayerFishEvent.State.FISHING)
                    Trigger.BITE_HOOK -> fish(player, null, PlayerFishEvent.State.BITE)
                    Trigger.CATCH_FISH -> fish(player, player.world.dropItem(mob.location, ItemStack(Material.COD)), PlayerFishEvent.State.CAUGHT_FISH)
                    else -> null
                }
                if (trigger == Trigger.COMMAND) values["command"] = command?.trim()?.removePrefix("/")?.lowercase() ?: "verify"
                if (trigger == Trigger.MINING && block != null) values["block type"] = block.type.name
                TriggerContext(trigger, player, player, null, event, block, null, removal, values)
            }
        }
    }

    /** [com.inmc.enchants.listener.CombatListener] 가 피해 사건마다 채우는 값과 같은 열쇠. */
    private fun damageValues(event: EntityDamageEvent): Map<String, String> = mapOf(
        "damage" to Variables.num(event.finalDamage),
        "raw damage" to Variables.num(event.damage),
        "damage cause" to event.cause.name,
        "is critical" to "false",
        "damaged from behind" to "false",
    )

    fun source(causing: Entity?): DamageSource {
        val builder = DamageSource.builder(DamageType.GENERIC)
        if (causing != null) builder.withCausingEntity(causing).withDirectEntity(causing)
        return builder.build()
    }

    /**
     * 기본 10 에 방어 경감 -2 가 붙은 피해. 방어 경감이 있어야 `IGNORE_ARMOR_PROTECTION` 이
     * 실제로 무언가를 지웠는지 볼 수 있다.
     */
    @Suppress("DEPRECATION")
    fun damage(attacker: Entity, victim: Entity, amount: Double = 10.0): EntityDamageByEntityEvent {
        val modifiers = EnumMap<DamageModifier, Double>(DamageModifier::class.java)
        modifiers[DamageModifier.BASE] = amount
        modifiers[DamageModifier.ARMOR] = -2.0
        val functions = EnumMap<DamageModifier, Function<in Double, Double>>(DamageModifier::class.java)
        functions[DamageModifier.BASE] = Function { 0.0 }
        functions[DamageModifier.ARMOR] = Function { base -> -(base ?: 0.0) * 0.2 }
        return EntityDamageByEntityEvent(attacker, victim, DamageCause.ENTITY_ATTACK, source(attacker), modifiers, functions)
    }

    /** 진짜 낚시찌를 띄운다. 손에 낚싯대가 없으면 바닐라가 다음 틱에 거둔다. */
    fun fish(player: Player, caught: Entity?, state: PlayerFishEvent.State): PlayerFishEvent {
        val hook = player.launchProjectile(FishHook::class.java)
        return PlayerFishEvent(player, caught, hook, EquipmentSlot.HAND, state)
    }
}
