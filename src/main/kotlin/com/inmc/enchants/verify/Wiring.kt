package com.inmc.enchants.verify

import com.destroystokyo.paper.event.player.PlayerJumpEvent
import com.inmc.enchants.Enchants
import com.inmc.enchants.engine.Trigger
import kr.inmc.core.event.InmcSignalEvent
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.BrewingStand
import org.bukkit.entity.Arrow
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Piglin
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerToggleSprintEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * 발동 조건마다 가짜 사건을 **우리 리스너에만** 넘겨, 그 조건의 인첸트가 실제로 도는지 본다.
 *
 * Bukkit 으로 쏘지 않는 것이 요점이다 — 다른 플러그인(보호·로그·경제)이 가짜 공격·채굴을 진짜로
 * 받으면 안 된다. 대신 리스너 인스턴스를 로케이터에서 꺼내 메서드를 직접 부른다.
 *
 * 검사하는 것은 **배선**이다: 리스너가 사건을 읽어 맞는 발동 조건·맞는 사람·맞는 칸으로 엔진을
 * 부르는가. 인첸트는 `SET_VARIABLE` 하나짜리 검증용이고, 그 변수가 바뀌면 통과다.
 */
object Wiring {

    /** 검증용 인첸트가 붙은 아이템을 누가 드는가. */
    enum class Holder { PLAYER, MOB, HEAD }

    class Kit(val e: Enchants, val player: Player, val mob: Piglin, val block: Block, val item: ItemStack)

    class Case(
        val trigger: Trigger,
        val holder: Holder = Holder.PLAYER,
        val item: Material = Material.DIAMOND_SWORD,
        val label: String = trigger.label,
        /** 이 무대에서 만들 수 없는 사건. 이유를 적는다. */
        val skip: String? = null,
        val fire: (Kit) -> Unit = {},
    )

    /** 사건을 만들다가 이 무대에서는 안 된다는 것을 알았다. */
    class Skip(reason: String) : RuntimeException(reason)

    private val MONSTER_MOB = NamespacedKey("inmc-monster", "mob_id")

    private fun hitBy(k: Kit, attacker: org.bukkit.entity.Entity, victim: org.bukkit.entity.Entity) =
        k.e.combat.onHit(Contexts.damage(attacker, victim))

    /** 진짜 화살을 쏘고 활 사건을 우리 리스너에 넘긴다. 쏜 순간의 활 인첸트가 화살에 기억된다. */
    private fun shoot(k: Kit, shooter: LivingEntity): Arrow {
        val arrow = shooter.launchProjectile(Arrow::class.java)
        k.e.projectileListener.onShoot(EntityShootBowEvent(shooter, k.item, ItemStack(Material.ARROW), arrow, EquipmentSlot.HAND, 1.0f, false))
        return arrow
    }

    private fun environment(k: Kit, cause: DamageCause) =
        k.e.combat.onDamage(EntityDamageEvent(k.player, cause, Contexts.source(null), 1.0))

    private fun interact(k: Kit, action: Action) =
        k.e.actions.onInteract(PlayerInteractEvent(k.player, action, k.item, null, BlockFace.SELF, EquipmentSlot.HAND))

    private fun kill(k: Kit) {
        k.mob.killer = k.player
        k.e.combat.onDeath(EntityDeathEvent(k.mob, Contexts.source(k.player), mutableListOf()))
    }

    val CASES: List<Case> = listOf(
        Case(Trigger.ATTACK, Holder.MOB) { k -> hitBy(k, k.mob, k.player) },
        Case(Trigger.ATTACK_MOB) { k -> hitBy(k, k.player, k.mob) },
        Case(Trigger.CUSTOM_MOB_DEFENSE) { k ->
            k.mob.persistentDataContainer.set(MONSTER_MOB, PersistentDataType.STRING, "verify")
            hitBy(k, k.player, k.mob)
        },
        Case(Trigger.DEFENSE, Holder.MOB) { k -> hitBy(k, k.player, k.mob) },
        Case(Trigger.DEFENSE_MOB) { k -> hitBy(k, k.mob, k.player) },
        Case(Trigger.DEFENSE_PROJECTILE, Holder.MOB) { k -> hitBy(k, k.player.launchProjectile(Arrow::class.java), k.mob) },
        Case(Trigger.DEFENSE_MOB_PROJECTILE) { k -> hitBy(k, k.mob.launchProjectile(Arrow::class.java), k.player) },
        Case(Trigger.SHOOT, Holder.MOB, Material.BOW) { k -> hitBy(k, shoot(k, k.mob), k.player) },
        Case(Trigger.SHOOT_MOB, item = Material.BOW) { k -> hitBy(k, shoot(k, k.player), k.mob) },
        Case(Trigger.ARROW_HIT, item = Material.BOW) { k -> k.e.projectileListener.onHit(ProjectileHitEvent(shoot(k, k.player), k.mob)) },
        Case(Trigger.BOW_FIRE, item = Material.BOW) { k -> shoot(k, k.player) },
        Case(Trigger.KILL) { k -> kill(k) },
        Case(Trigger.KILL_MOB) { k -> kill(k) },
        Case(Trigger.KILL_PLAYER, Holder.MOB) { k ->
            @Suppress("DEPRECATION")
            k.player.lastDamageCause = Contexts.damage(k.mob, k.player)
            k.e.combat.onDeath(EntityDeathEvent(k.player, Contexts.source(k.mob), mutableListOf()))
        },
        Case(Trigger.DEATH) { k -> k.e.combat.onResurrect(EntityResurrectEvent(k.player).apply { isCancelled = true }) },
        Case(Trigger.PASSIVE_DEATH) { k -> environment(k, DamageCause.LIGHTNING) },
        Case(Trigger.EXPLOSION) { k -> environment(k, DamageCause.BLOCK_EXPLOSION) },
        Case(Trigger.FALL_DAMAGE) { k -> environment(k, DamageCause.FALL) },
        Case(Trigger.FIRE) { k -> environment(k, DamageCause.FIRE) },
        Case(Trigger.ELYTRA_FLY_DAMAGE) { k ->
            k.player.inventory.setChestplate(ItemStack(Material.ELYTRA))
            k.player.isGliding = true
            if (!k.player.isGliding) throw Skip("서버가 비행 상태를 받아주지 않았다 - 겉날개로 날면서 게임 안에서 확인")
            environment(k, DamageCause.FLY_INTO_WALL)
        },
        Case(Trigger.ELYTRA_FLY) { k -> k.e.actions.onGlide(EntityToggleGlideEvent(k.player, true)) },
        Case(Trigger.JUMP) { k -> k.e.actions.onJump(PlayerJumpEvent(k.player, k.player.location, k.player.location.clone().add(0.0, 0.5, 0.0))) },
        Case(Trigger.SHIFT) { k -> k.e.actions.onSneak(PlayerToggleSneakEvent(k.player, true)) },
        Case(Trigger.SPRINT) { k -> k.e.actions.onSprint(PlayerToggleSprintEvent(k.player, true)) },
        Case(Trigger.MINING, item = Material.DIAMOND_PICKAXE) { k -> k.e.actions.onBreak(BlockBreakEvent(k.block, k.player)) },
        Case(Trigger.SWING) { k -> interact(k, Action.LEFT_CLICK_AIR) },
        Case(Trigger.RIGHT_CLICK) { k -> interact(k, Action.RIGHT_CLICK_AIR) },
        Case(Trigger.HORN, item = Material.GOAT_HORN) { k -> interact(k, Action.RIGHT_CLICK_AIR) },
        Case(Trigger.RIGHT_CLICK_ENTITY) { k -> k.e.actions.onInteractEntity(PlayerInteractEntityEvent(k.player, k.mob, EquipmentSlot.HAND)) },
        Case(Trigger.ITEM_BREAK) { k -> k.e.actions.onItemBreak(PlayerItemBreakEvent(k.player, k.item)) },
        Case(Trigger.EAT) { k -> k.e.actions.onEat(PlayerItemConsumeEvent(k.player, ItemStack(Material.BREAD), EquipmentSlot.HAND)) },
        Case(Trigger.BREW_POTION) { k ->
            // 양조 사건에는 사람이 없다 — 리스너는 양조대를 마지막으로 연 사람을 쓴다. 그래서 진짜로 연다.
            k.block.setType(Material.BREWING_STAND, false)
            val stand = k.block.state as BrewingStand
            k.player.openInventory(stand.inventory)
            k.player.closeInventory()
            k.e.actions.onBrew(BrewEvent(k.block, stand.inventory, listOf(ItemStack(Material.POTION)), 0))
        },
        Case(Trigger.COMMAND) { k -> k.e.actions.onCommand(PlayerCommandPreprocessEvent(k.player, "/" + Verifier.PROBE_COMMAND)) },
        Case(Trigger.JOIN) { k -> k.e.actions.onJoin(PlayerJoinEvent(k.player, Component.empty())) },
        Case(Trigger.QUIT) { k -> k.e.actions.onQuit(PlayerQuitEvent(k.player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED)) },
        Case(Trigger.ROD_CAST, item = Material.FISHING_ROD) { k -> k.e.actions.onFish(Contexts.fish(k.player, null, PlayerFishEvent.State.FISHING)) },
        Case(Trigger.BITE_HOOK, item = Material.FISHING_ROD) { k -> k.e.actions.onFish(Contexts.fish(k.player, null, PlayerFishEvent.State.BITE)) },
        Case(Trigger.CATCH_FISH, item = Material.FISHING_ROD) { k ->
            val fish = k.player.world.dropItem(k.mob.location, ItemStack(Material.COD))
            k.e.actions.onFish(Contexts.fish(k.player, fish, PlayerFishEvent.State.CAUGHT_FISH))
        },
        Case(Trigger.CATCH_FISH, item = Material.FISHING_ROD, label = "낚기(inmc-fishing 신호)") { k ->
            k.e.fishingSignal.onSignal(InmcSignalEvent("fishing", "catch", k.player.uniqueId, k.player, "verify", 1L, mapOf("grade" to "S")))
        },
        Case(Trigger.HOOK_ENTITY, item = Material.FISHING_ROD) { k -> k.e.actions.onFish(Contexts.fish(k.player, k.mob, PlayerFishEvent.State.CAUGHT_ENTITY)) },
        Case(Trigger.SHIELD_BLOCK, skip = "서버가 방패를 드는 중인지 직접 계산한다 - 방패로 막으면서 게임 안에서 확인"),
        Case(Trigger.HELD) { k -> k.e.statics.refresh(k.player) },
        Case(Trigger.EFFECT_STATIC, Holder.HEAD, Material.DIAMOND_HELMET) { k -> k.e.statics.refresh(k.player) },
        Case(Trigger.REPEATING) { k -> k.e.statics.tick(System.currentTimeMillis()) },
    )
}
