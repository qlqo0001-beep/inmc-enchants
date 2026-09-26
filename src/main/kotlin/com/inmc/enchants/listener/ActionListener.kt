package com.inmc.enchants.listener

import com.inmc.enchants.Enchants
import com.inmc.enchants.engine.Trigger
import com.inmc.enchants.engine.TriggerContext
import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.item.Keys
import com.inmc.enchants.item.Trackers
import kr.inmc.core.input.Clicks
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.EntityToggleGlideEvent
import org.bukkit.event.inventory.BrewEvent
import org.bukkit.event.inventory.InventoryOpenEvent
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 채굴·상호작용·이동·낚시·먹기·명령어·접속 발동 조건. */
class ActionListener(private val e: Enchants) : Listener {

    /** 채굴의 드랍은 BlockBreakEvent 보다 **나중에** 나온다. 그때까지 드랍 효과를 들고 있는다. */
    private val pendingDrops = ConcurrentHashMap<Location, TriggerContext>()

    /** 양조대 → 마지막으로 연 사람. 양조 사건에는 사람이 없다. */
    private val brewers = ConcurrentHashMap<Location, UUID>()

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        // 효과가 보호 플러그인 확인용으로 쏜 가짜 사건이다. 여기서 또 채굴을 돌리면 트렌치가 트렌치를 부른다.
        if (e.support.effectBreaking || !e.ready) return
        val player = event.player
        val block = event.block
        val tool = player.inventory.itemInMainHand
        val drops = block.getDrops(tool, player)
        val values: MutableMap<String, String> = hashMapOf(
            "block type" to block.type.name,
            "block drop type" to (drops.firstOrNull()?.type?.name ?: "AIR"),
            "exp" to event.expToDrop.toString(),
            "block tags" to "",
        )
        val ctx = TriggerContext(Trigger.MINING, player, player, null, event, block, values = values)
        e.engine.fire(ctx)
        if (ctx.cancelled) return
        if (ctx.dropMultiplier != 1.0 || ctx.smeltDrops || ctx.teleportDrops) pendingDrops[block.location] = ctx

        Trackers.bump(e, player, EquipmentSlot.HAND, Trackers.BLOCK)
        if (e.config.miningSoulsChance > 0 && Math.random() * 100 < e.config.miningSoulsChance && EnchantStorage.flag(tool, Keys.SOUL_TRACKER)) {
            e.souls.add(tool, 1)
            player.inventory.setItemInMainHand(tool)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDrops(event: BlockDropItemEvent) {
        val ctx = pendingDrops.remove(event.block.location) ?: return
        val stacks = event.items.map { it.itemStack }.toMutableList()
        event.items.forEach(Item::remove)
        event.items.clear()
        e.drops.deliver(event.player, event.block.location.add(0.5, 0.5, 0.5), stacks, ctx)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onInteract(event: PlayerInteractEvent) {
        if (!e.ready || event.hand != EquipmentSlot.HAND) return
        // 우클릭에 딸려 오는 손 흔들기 유령 좌클릭 — 받으면 우클릭 한 번에 휘두르기 인첸트까지 돈다.
        if (Clicks.isGhost(event)) return
        val player = event.player
        val block = event.clickedBlock
        val values: MutableMap<String, String> = HashMap()
        block?.let { values["block type"] = it.type.name }
        when (event.action) {
            Action.RIGHT_CLICK_AIR, Action.RIGHT_CLICK_BLOCK -> {
                if (player.inventory.itemInMainHand.type == Material.GOAT_HORN) {
                    e.engine.fire(TriggerContext(Trigger.HORN, player, player, null, event, block, values = HashMap(values)))
                }
                e.engine.fire(TriggerContext(Trigger.RIGHT_CLICK, player, player, null, event, block, values = values))
            }
            Action.LEFT_CLICK_AIR, Action.LEFT_CLICK_BLOCK ->
                e.engine.fire(TriggerContext(Trigger.SWING, player, player, null, event, block, values = values))
            else -> Unit
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        if (!e.ready || event.hand != EquipmentSlot.HAND) return
        e.engine.fire(TriggerContext(Trigger.RIGHT_CLICK_ENTITY, event.player, event.player, event.rightClicked, event))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEat(event: PlayerItemConsumeEvent) {
        if (!e.ready || !event.item.type.isEdible) return
        e.engine.fire(TriggerContext(Trigger.EAT, event.player, event.player, null, event, values = hashMapOf("item" to event.item.type.name)))
    }

    /**
     * 부서지는 순간. 효과가 `CANCEL_EVENT` 를 내면(복원 계열) 효과가 고쳐 둔 아이템을 **돌려준다** —
     * 이 사건은 취소할 수 없어서 바닐라가 지운 뒤 다음 틱에 되돌려 놓는다.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onItemBreak(event: PlayerItemBreakEvent) {
        if (!e.ready) return
        val broken = event.brokenItem
        val ctx = TriggerContext(Trigger.ITEM_BREAK, event.player, event.player, null, event)
        e.engine.fireWith(ctx, broken, EnchantStorage.read(broken))
        if (!ctx.cancelled) return
        val saved = broken.clone().also { it.amount = 1 }
        val player = event.player
        val wasHeld = player.inventory.itemInMainHand.isSimilar(broken)
        e.support.later(1L) {
            if (!player.isOnline) return@later
            if (wasHeld && player.inventory.itemInMainHand.type.isAir) {
                player.inventory.setItemInMainHand(saved)
            } else {
                for (left in player.inventory.addItem(saved).values) player.world.dropItemNaturally(player.location, left)
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onJump(event: com.destroystokyo.paper.event.player.PlayerJumpEvent) {
        if (!e.ready) return
        e.engine.fire(TriggerContext(Trigger.JUMP, event.player, event.player, null, event))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSneak(event: PlayerToggleSneakEvent) {
        if (!e.ready) return
        e.engine.fire(TriggerContext(Trigger.SHIFT, event.player, event.player, null, event, removal = !event.isSneaking))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSprint(event: PlayerToggleSprintEvent) {
        if (!e.ready) return
        e.engine.fire(TriggerContext(Trigger.SPRINT, event.player, event.player, null, event, removal = !event.isSprinting))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onGlide(event: EntityToggleGlideEvent) {
        if (!e.ready || !event.isGliding) return
        val entity = event.entity as? org.bukkit.entity.LivingEntity ?: return
        e.engine.fire(TriggerContext(Trigger.ELYTRA_FLY, entity, entity, null, event))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBrewOpen(event: InventoryOpenEvent) {
        val location = event.inventory.location ?: return
        if (event.inventory.type != org.bukkit.event.inventory.InventoryType.BREWING) return
        brewers[location.block.location] = event.player.uniqueId
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBrew(event: BrewEvent) {
        if (!e.ready) return
        val player = brewers[event.block.location]?.let { org.bukkit.Bukkit.getPlayer(it) } ?: return
        val first = event.results.firstOrNull { !it.type.isAir }
        val meta = first?.itemMeta as? org.bukkit.inventory.meta.PotionMeta
        val values: MutableMap<String, String> = hashMapOf(
            "potion type" to (meta?.basePotionType?.key?.key ?: "none"),
            "is extended" to (meta?.basePotionType?.key?.key?.startsWith("long_") ?: false).toString(),
            "is upgraded" to (meta?.basePotionType?.key?.key?.startsWith("strong_") ?: false).toString(),
        )
        e.engine.fire(TriggerContext(Trigger.BREW_POTION, player, player, null, event, event.block, values = values))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (!e.ready) return
        val command = event.message.removePrefix("/").substringBefore(' ').lowercase()
        e.engine.fire(TriggerContext(Trigger.COMMAND, event.player, event.player, null, event, values = hashMapOf("command" to command)))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        if (!e.ready) return
        e.engine.fire(TriggerContext(Trigger.JOIN, event.player, event.player, null, event))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        if (!e.ready) return
        e.engine.fire(TriggerContext(Trigger.QUIT, event.player, event.player, null, event))
        e.state.forget(event.player.uniqueId)
        e.tinkerLog.forget(event.player.uniqueId)
    }

    /**
     * 바닐라 낚시. inmc-fishing 이 가로챈 낚기(취소된 CAUGHT_FISH)는 여기 오지 않고
     * [FishingSignalListener] 가 `fishing/catch` 신호로 받는다.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onFish(event: PlayerFishEvent) {
        if (!e.ready) return
        val player = event.player
        when (event.state) {
            PlayerFishEvent.State.FISHING -> e.engine.fire(TriggerContext(Trigger.ROD_CAST, player, player, null, event))
            PlayerFishEvent.State.BITE -> e.engine.fire(TriggerContext(Trigger.BITE_HOOK, player, player, null, event))
            PlayerFishEvent.State.CAUGHT_FISH -> {
                val caught = (event.caught as? Item)?.itemStack
                val values: MutableMap<String, String> = hashMapOf("caught" to (caught?.type?.name ?: ""), "exp" to event.expToDrop.toString())
                val ctx = TriggerContext(Trigger.CATCH_FISH, player, player, null, event, values = values)
                e.engine.fire(ctx)
                applyCatchDrops(player, event, ctx)
                fishCaught(player)
            }
            PlayerFishEvent.State.CAUGHT_ENTITY -> {
                e.engine.fire(TriggerContext(Trigger.HOOK_ENTITY, player, player, event.caught, event))
            }
            else -> Unit
        }
    }

    /** 낚은 것에 배수·제련이 걸렸으면 낚은 아이템을 바꾼다. */
    private fun applyCatchDrops(player: Player, event: PlayerFishEvent, ctx: TriggerContext) {
        if (ctx.dropMultiplier == 1.0 && !ctx.smeltDrops) return
        val item = event.caught as? Item ?: return
        val stacks = mutableListOf(item.itemStack)
        e.drops.transform(stacks, ctx)
        val split = stacks.flatMap(e.drops::split)
        item.itemStack = split.first()
        for (extra in split.drop(1)) for (left in player.inventory.addItem(extra).values) player.world.dropItemNaturally(player.location, left)
    }

    /** 낚시 추적기·낚시 영혼. 바닐라와 inmc-fishing 신호가 같이 부른다. */
    fun fishCaught(player: Player) {
        Trackers.bump(e, player, EquipmentSlot.HAND, Trackers.FISH)
        val rod = player.inventory.itemInMainHand
        if (e.config.fishingSoulsChance > 0 && Math.random() * 100 < e.config.fishingSoulsChance && EnchantStorage.flag(rod, Keys.SOUL_TRACKER)) {
            e.souls.add(rod, 1)
            player.inventory.setItemInMainHand(rod)
        }
    }

    /** 1분마다 오래된 드랍 대기를 치운다(드랍이 안 나오는 블록 — 창작 모드 등). */
    fun sweep() {
        if (pendingDrops.size > 256) pendingDrops.clear()
        brewers.entries.removeIf { org.bukkit.Bukkit.getPlayer(it.value) == null }
    }
}
