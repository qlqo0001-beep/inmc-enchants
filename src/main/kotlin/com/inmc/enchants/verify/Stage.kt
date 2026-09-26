package com.inmc.enchants.verify

import com.inmc.enchants.Enchants
import com.inmc.enchants.effect.impl.Experience
import org.bukkit.Difficulty
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Piglin
import org.bukkit.entity.Wolf
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import java.util.UUID

/**
 * 검증 무대. 검증하는 사람 앞에 몹을 세우고 블록을 쌓고, **건마다** 전부 되돌린다.
 *
 * | 자리 | 무엇 |
 * |---|---|
 * | 선 자리 | 검증하는 사람 = 발동한 쪽. 몹 쪽(동쪽)을 바라보게 돌려 세운다 |
 * | +5, 0, 0 | 몹(`@Victim`). 폭발·번개가 사람에게 닿지 않을 만큼 떨어뜨렸다 |
 * | +1.5, 0, -1.5 | 구경꾼 몹. 범위 대상(`@Aoe`·`@EntityInSight`) 줄에만 세운다 — 반경이 5 보다 작으면 비니까 |
 * | -1.5, 0, 1.5 | 길들인 늑대. 아군 대상(`target=UNDAMAGEABLE`) 줄에만 세운다 |
 * | 0, +1, +4 | 블록 효과의 중심(돌 3×3×3 · 나무 · 밭) |
 *
 * 몹은 **피글린**이다. 언데드(좀비)는 독·재생이 안 걸려 멀쩡한 독 인첸트가 실패로 나온다. 피글린은
 * 무기를 들고 방어구를 입고 머리를 떨군다.
 *
 * 되돌리는 것: 무대 블록, **무대가 생긴 뒤 나타난 엔티티 전부**(드랍·구슬·투사체·소환물),
 * 검증하는 사람의 가방·상태, 월드 난이도(평화로움이면 검증 동안 쉬움)와 날씨(검증 동안 맑음 —
 * 빗속에서는 불이 곧바로 꺼져 멀쩡한 불 인첸트가 실패로 나온다). 그래서 **주변에 몹이
 * 없는 평지**에서 돌려야 한다 — 검증 도중 걸어 들어온 몹도 지워진다.
 */
class Stage(private val e: Enchants, val player: Player) {

    val origin: Location = player.location.block.location.add(0.5, 0.0, 0.5)
    val victimSpot: Location = origin.clone().add(5.0, 0.0, 0.0)
    val blockCenter: Block = origin.block.getRelative(0, 1, 4)

    private val saved = PlayerState(player)

    /** 평화로움에서는 적대 몹이 소환되지 않고 소환돼도 곧 사라진다. 검증하는 동안만 쉬움으로 둔다. */
    private val difficulty: Difficulty = origin.world.difficulty
    private val storm = origin.world.hasStorm()
    private val thunder = origin.world.isThundering
    private val weatherTicks = origin.world.weatherDuration
    private val backup: Map<Block, BlockData> = buildMap {
        for (dx in -2..2) for (dy in -3..5) for (dz in -2..2) blockCenter.getRelative(dx, dy, dz).let { put(it, it.blockData.clone()) }
        val v = victimSpot.block
        for (dx in -2..2) for (dy in -1..3) for (dz in -2..2) v.getRelative(dx, dy, dz).let { put(it, it.blockData.clone()) }
    }
    private val baseline: Set<UUID> = entities().map { it.uniqueId }.toSet()

    init {
        player.teleport(origin.clone().also { it.yaw = -90f; it.pitch = 0f })
        player.gameMode = GameMode.SURVIVAL
        if (difficulty == Difficulty.PEACEFUL) origin.world.difficulty = Difficulty.EASY
        origin.world.setStorm(false)
        origin.world.isThundering = false
        between()
    }

    /** 무대 반경 안의 사람 아닌 엔티티. */
    private fun entities(): List<Entity> =
        origin.world.getNearbyEntities(origin, RADIUS, RADIUS, RADIUS).filter { it !is Player }

    fun nearby(): Int = entities().size

    /** 무대 생물의 체력. 검증하는 사람은 뺀다(발동한 쪽이다). */
    fun health(): Map<UUID, Double> = entities().filterIsInstance<org.bukkit.entity.LivingEntity>().associate { it.uniqueId to it.health }

    /** [before] 에서 체력이 줄었거나 사라진(죽은) 생물 수. */
    fun hurt(before: Map<UUID, Double>): Int = before.count { (id, health) ->
        val now = org.bukkit.Bukkit.getEntity(id) as? org.bukkit.entity.LivingEntity
        now == null || now.isDead || now.health < health
    }

    fun spawnVictim(): Piglin = spawnMob(victimSpot)

    fun spawnBystander(): Piglin = spawnMob(origin.clone().add(1.5, 0.0, -1.5))

    /** 검증하는 사람이 주인인 늑대. 엔진은 자기 호위와 자기 길들인 동물을 아군으로 본다. */
    fun spawnAlly(): Wolf = origin.world.spawn(origin.clone().add(-1.5, 0.0, 1.5), Wolf::class.java) { w ->
        w.setAI(false)
        w.isSilent = true
        w.isPersistent = false
        w.isTamed = true
        w.owner = player
        w.addScoreboardTag(TAG)
    }

    private fun spawnMob(at: Location): Piglin = origin.world.spawn(at, Piglin::class.java) { p ->
        p.setAI(false)
        p.isSilent = true
        p.isPersistent = false
        p.removeWhenFarAway = false
        p.isImmuneToZombification = true
        p.setAdult()
        p.canPickupItems = false
        p.equipment.clear()
        p.addScoreboardTag(TAG)
    }

    enum class Scene { NONE, CUBE, TREE, FARM }

    /** 장면을 짓고 발동의 블록(`@Block`)을 돌려준다. */
    fun build(scene: Scene, material: Material = Material.STONE): Block? = when (scene) {
        Scene.NONE -> null
        Scene.CUBE -> {
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) blockCenter.getRelative(dx, dy, dz).setType(material, false)
            blockCenter
        }
        Scene.TREE -> {
            val bottom = blockCenter.getRelative(0, -1, 0)
            bottom.getRelative(0, -1, 0).setType(Material.DIRT, false)
            for (dy in 0..3) bottom.getRelative(0, dy, 0).setType(Material.OAK_LOG, false)
            for (dx in -1..1) for (dz in -1..1) if (dx != 0 || dz != 0) bottom.getRelative(dx, 3, dz).setType(Material.OAK_LEAVES, false)
            bottom
        }
        Scene.FARM -> {
            val soil = blockCenter.getRelative(0, -1, 0)
            for (dx in -1..1) for (dz in -1..1) {
                soil.getRelative(dx, 0, dz).setType(Material.FARMLAND, false)
                soil.getRelative(dx, 1, dz).setType(Material.AIR, false)
                soil.getRelative(dx, 2, dz).setType(Material.AIR, false)
            }
            soil
        }
    }

    fun blocks(): Map<Block, Material> = backup.keys.associateWith { it.type }

    fun changed(since: Map<Block, Material>): Int = since.count { (block, type) -> block.type != type }

    /** 한 건이 끝날 때마다. 무대를 처음 상태로, 사람은 검사하기 좋은 상태로. */
    fun between() {
        for (entity in entities()) if (entity.uniqueId !in baseline) entity.remove()
        for ((block, data) in backup) if (block.blockData != data) block.setBlockData(data, false)
        saved.restore(player, forTest = true)
        e.state.forget(player.uniqueId)
        // 검증용 인첸트를 들었던 칸이 비었다 — 지속 효과 목록도 맞춰 둔다.
        e.statics.refresh(player)
    }

    /** 검증을 마치거나 멈출 때. 무대를 치우고 사람을 **검증 전 그대로** 돌려놓는다. */
    fun close() {
        between()
        origin.world.difficulty = difficulty
        if (storm) {
            origin.world.setStorm(true)
            origin.world.isThundering = thunder
            origin.world.weatherDuration = weatherTicks
        }
        saved.restore(player, forTest = false)
        e.statics.refresh(player)
    }

    companion object {
        const val TAG = "inmc_enchant_verify"
        private const val RADIUS = 24.0
    }
}

/**
 * 검증하는 사람의 상태. 검증은 그 사람에게 물약·불·비행·속도를 걸고 가방을 뒤섞는다.
 *
 * [forTest] 면 체력·허기를 **가득**으로 두고 가방을 **비운다** — 시작할 때 체력이 2 였다면 첫 폭발에 죽고,
 * 검증하는 사람이 들고 있던 것(인첸트 붙은 장비·세트 조각)이 검사에 섞인다(실제로 손에 든 세트 투구 때문에
 * 수중 호흡 검사가 떨어졌다). 끝날 때만 원래 값으로 돌린다.
 */
private class PlayerState(player: Player) {
    private val contents: Array<ItemStack?> = player.inventory.contents.map { it?.clone() }.toTypedArray()
    private val heldSlot = player.inventory.heldItemSlot
    private val gameMode = player.gameMode
    private val health = player.health
    private val food = player.foodLevel
    private val saturation = player.saturation
    private val fire = player.fireTicks
    private val air = player.remainingAir
    private val potions: List<PotionEffect> = player.activePotionEffects.toList()
    private val walkSpeed = player.walkSpeed
    private val flySpeed = player.flySpeed
    private val allowFlight = player.allowFlight
    private val flying = player.isFlying
    private val invulnerable = player.isInvulnerable
    private val exp = Experience.total(player)
    private val location = player.location.clone()

    fun restore(player: Player, forTest: Boolean) {
        player.inventory.contents = if (forTest) arrayOfNulls(contents.size) else contents.map { it?.clone() }.toTypedArray()
        player.inventory.heldItemSlot = heldSlot
        // 죽는 중이면 가방만 돌려놓는다(사망 사건이 keepInventory 로 그대로 둔다). 체력·이동은 부활 뒤의 일이다.
        if (player.isDead) return
        if (player.openInventory.topInventory.type != org.bukkit.event.inventory.InventoryType.CRAFTING) player.closeInventory()
        for (effect in player.activePotionEffects) player.removePotionEffect(effect.type)
        potions.forEach(player::addPotionEffect)
        player.fireTicks = if (forTest) 0 else fire
        player.freezeTicks = 0
        player.remainingAir = if (forTest) player.maximumAir else air
        player.walkSpeed = walkSpeed
        player.flySpeed = flySpeed
        player.isInvulnerable = invulnerable
        Experience.set(player, exp)
        if (player.location.world != location.world || player.location.distanceSquared(location) > 0.25) player.teleport(location)
        val max = player.getAttribute(Attribute.MAX_HEALTH)?.value ?: 20.0
        if (forTest) {
            player.health = max
            player.foodLevel = 20
            player.saturation = 5f
            player.isFlying = false
            player.allowFlight = false
        } else {
            player.gameMode = gameMode
            player.health = health.coerceIn(0.5, max)
            player.foodLevel = food
            player.saturation = saturation
            player.allowFlight = allowFlight
            player.isFlying = flying && allowFlight
        }
    }
}
