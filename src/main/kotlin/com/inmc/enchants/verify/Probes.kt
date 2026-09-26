package com.inmc.enchants.verify

import com.inmc.enchants.Enchants
import com.inmc.enchants.effect.EffectRun
import com.inmc.enchants.effect.impl.Experience
import com.inmc.enchants.engine.EngineState
import com.inmc.enchants.engine.Names
import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.item.Keys
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.bukkit.potion.PotionEffect

/**
 * 효과마다 "실제로 무엇이 바뀌어야 하는가".
 *
 * 효과를 실행하기 **전** 상태를 찍고, [Probe.delay] 틱 뒤 **후** 상태와 비교한다. 비교가 효과의 뜻 그대로다 —
 * `BURN` 이면 불이 붙었는가, `INCREASE_DAMAGE` 면 사건의 피해가 커졌는가.
 *
 * 여기 없는 효과는 검증기가 "검사식 없음"으로 **실패** 처리한다. 조용히 통과시키면 검증한 척이 된다.
 */
object Probes {

    /** 대상 하나의 상태. 효과가 바꿀 수 있는 것을 전부. */
    data class Shot(
        val health: Double,
        val fire: Int,
        val freeze: Int,
        val potions: Map<String, Int>,
        val velocity: Double,
        val location: Location,
        val equipment: List<Material>,
        val mainHand: Material,
        val invulnerable: Boolean,
        val food: Int,
        val air: Int,
        val walkSpeed: Float,
        val flySpeed: Float,
        val allowFlight: Boolean,
        val exp: Int,
        val money: Double,
        val dead: Boolean,
        val inventory: Int,
        val hotbar: List<Material>,
        /** 인첸트가 붙은 칸(주 손·방어구) 아이템의 손상. */
        val durability: Int,
        /** 입은 방어구의 손상 합. */
        val armorDamage: Int,
        /** 인첸트가 붙은 칸 아이템의 개수·인첸트·영혼. 아이템을 바꾸는 효과가 본다. */
        val holderAmount: Int,
        val holderEnchants: Map<String, Int>,
        val holderSouls: Int,
    )

    private val ARMOR = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    /** @param slot 인첸트가 붙은 칸. 방어구 인첸트면 그 부위다. */
    fun shot(entity: Entity?, holder: LivingEntity, slot: EquipmentSlot, e: Enchants): Shot? {
        entity ?: return null
        val living = entity as? LivingEntity
        val player = entity as? Player
        val eq = living?.equipment
        val held = holder.equipment?.getItem(slot)
        return Shot(
            health = living?.health ?: 0.0,
            fire = entity.fireTicks,
            freeze = entity.freezeTicks,
            potions = living?.activePotionEffects?.associate { it.type.key.key to it.amplifier } ?: emptyMap(),
            velocity = entity.velocity.length(),
            location = entity.location.clone(),
            equipment = ARMOR.map { eq?.getItem(it)?.type ?: Material.AIR },
            mainHand = eq?.itemInMainHand?.type ?: Material.AIR,
            invulnerable = entity.isInvulnerable,
            food = player?.foodLevel ?: 0,
            air = living?.remainingAir ?: 0,
            walkSpeed = player?.walkSpeed ?: 0f,
            flySpeed = player?.flySpeed ?: 0f,
            allowFlight = player?.allowFlight ?: false,
            exp = player?.let { Experience.total(it) } ?: 0,
            money = if (player != null && e.economy.isEnabled) e.economy.balance(player) else 0.0,
            dead = living?.isDead ?: !entity.isValid,
            inventory = player?.inventory?.contents?.sumOf { it?.amount ?: 0 } ?: 0,
            hotbar = player?.let { p -> (0..8).map { p.inventory.getItem(it)?.type ?: Material.AIR } } ?: emptyList(),
            durability = (held?.itemMeta as? Damageable)?.damage ?: 0,
            armorDamage = ARMOR.sumOf { (eq?.getItem(it)?.itemMeta as? Damageable)?.damage ?: 0 },
            holderAmount = held?.takeIf { !it.type.isAir }?.amount ?: 0,
            holderEnchants = EnchantStorage.read(held),
            holderSouls = EnchantStorage.int(held, Keys.SOULS),
        )
    }

    /** 실행 전후와 사건. 검사식이 필요한 것을 전부 모았다. */
    class Case(
        val run: EffectRun,
        val target: Entity?,
        val before: Shot?,
        val eventDamageBefore: Double?,
        val nearbyBefore: Int,
    ) {
        /** 대상이 엔티티일 때만 채운다. 사건형(EVENT) 검사식은 읽지 않는다. */
        lateinit var after: Shot
        var eventDamageAfter: Double? = null
        var nearbyAfter: Int = 0

        /** 발동한 쪽 말고 체력이 줄거나 죽은 무대의 생물 수. 주변을 치는 효과가 본다. */
        var hurtOthers: Int = 0

        /** 검증 무대에서 바뀐 블록 수. */
        var changedBlocks: Int = 0
    }

    class Probe(
        /** 몇 틱 뒤에 볼지. 예약 작업(출혈·되돌리기)을 쓰는 효과가 크다. */
        val delay: Long = 1,
        /** 이 무대에서는 검증할 수 없는 이유. null 이면 검증한다. */
        val skip: (EffectRun) -> String? = { null },
        /** 실행 전에 대상을 검사하기 좋은 상태로(체력 깎아 두기 등). */
        val prepare: (EffectRun, Entity?) -> Unit = { _, _ -> },
        /** 판정 뒤에 효과가 남긴 것을 치운다(권한·대기시간·열린 창). */
        val cleanup: (Case) -> Unit = {},
        /** null 이면 통과, 아니면 실패 이유. */
        val check: (Case) -> String?,
    )

    private fun ok(condition: Boolean, why: String): String? = if (condition) null else why

    private fun damageEvent(case: Case) = case.run.ctx.event as? EntityDamageEvent

    private fun hook(case: Case) = (case.run.ctx.event as? PlayerFishEvent)?.hook

    private val damageUp = Probe { c -> ok((c.eventDamageAfter ?: 0.0) > (c.eventDamageBefore ?: 0.0), "사건 피해가 늘지 않았다 ${c.eventDamageBefore}→${c.eventDamageAfter}") }
    private val damageDown = Probe { c -> ok((c.eventDamageAfter ?: 99.0) < (c.eventDamageBefore ?: 0.0), "사건 피해가 줄지 않았다 ${c.eventDamageBefore}→${c.eventDamageAfter}") }
    private val hurt = Probe { c -> ok(c.after.health < c.before!!.health || c.after.dead, "체력이 줄지 않았다 ${c.before!!.health}→${c.after.health}") }
    private val moved = Probe(delay = 2) { c ->
        ok(c.after.location.distance(c.before!!.location) > 0.2 || c.after.velocity > 0.05, "움직이지 않았다")
    }
    private val blockChanged = Probe { c -> ok(c.changedBlocks > 0, "무대의 블록이 하나도 바뀌지 않았다") }
    private val spawned = Probe(delay = 0) { c -> ok(c.nearbyAfter > c.nearbyBefore, "주변에 새로 생긴 것이 없다") }
    private val nothingToCheck = Probe { null }

    private val twoPlayers: (EffectRun) -> String? = { run ->
        val victim = run.player
        if (victim == null || run.counterpart(victim) !is Player) "빼앗는 쪽과 빼앗기는 쪽이 둘 다 플레이어여야 한다 - 두 사람이 게임 안에서 확인" else null
    }
    private val needsEconomy: (EffectRun) -> String? = { run ->
        when {
            !run.enchants.economy.isEnabled -> "Vault 경제 플러그인이 없다"
            run.player == null -> "대상이 플레이어가 아니다"
            else -> null
        }
    }

    /** 효과 이름 → 검사식. */
    val ALL: Map<String, Probe> = mapOf(
        "INCREASE_DAMAGE" to damageUp,
        "DOUBLE_DAMAGE" to damageUp,
        "DECREASE_DAMAGE" to damageDown,
        "HALF_DAMAGE" to damageDown,
        "NEGATE_DAMAGE" to damageDown,
        "IGNORE_ARMOR_PROTECTION" to Probe { c ->
            val event = damageEvent(c) ?: return@Probe "피해 사건이 없다"
            @Suppress("DEPRECATION")
            ok(event.isApplicable(EntityDamageEvent.DamageModifier.ARMOR) && event.getDamage(EntityDamageEvent.DamageModifier.ARMOR) == 0.0, "방어 경감이 남아 있다")
        },
        "IGNORE_ARMOR_DAMAGE" to Probe(delay = 0) { c ->
            val victim = c.run.ctx.victim ?: return@Probe "피해자가 없다"
            ok(c.run.enchants.state.armorProtected(victim.uniqueId, System.currentTimeMillis() - 1), "방어구 보호 표시가 없다")
        },
        "CANCEL_EVENT" to Probe { c -> ok((c.run.ctx.event as? Cancellable)?.isCancelled == true || c.run.ctx.cancelled, "사건이 취소되지 않았다") },
        "DISABLE_KNOCKBACK" to Probe(delay = 0) { c -> ok(c.run.enchants.state.knockbackBlocked(c.target!!.uniqueId, System.currentTimeMillis()), "넉백 무효 표시가 없다") },
        "STOP_KNOCKBACK" to Probe(delay = 0) { c -> ok(c.run.enchants.state.knockbackBlocked(c.target!!.uniqueId, System.currentTimeMillis()), "넉백 무효 표시가 없다") },
        "DO_HARM" to hurt,
        "REMOVE_HEALTH" to hurt,
        "REMOVE_HEALTH_DAMAGE" to hurt,
        "REMOVE_HEALTH_TOTEM" to hurt,
        "REMOVE_HEALTH_DAMAGE_TOTEM" to hurt,
        "KILL" to Probe { c -> ok(c.after.dead || c.after.health <= 0.0, "죽지 않았다") },
        "BLEED" to Probe(delay = 45) { c -> ok(c.after.health < c.before!!.health || c.after.dead, "출혈 피해가 없었다") },
        "STEAL_HEALTH" to Probe(prepare = { run, _ -> run.ctx.self.health = (run.ctx.self.health - 6).coerceAtLeast(1.0) }) { c ->
            ok(c.after.health < c.before!!.health, "대상 체력이 줄지 않았다")
        },
        "TNT" to Probe(delay = 2) { c -> ok(c.hurtOthers > 0, "폭발에 다친 것이 없다") },
        "EXPLODE" to Probe(delay = 2) { c -> ok(c.hurtOthers > 0 || c.after.velocity > 0.05, "폭발이 닿지 않았다") },
        "LIGHTNING" to Probe(delay = 2) { c -> ok(c.nearbyAfter > c.nearbyBefore || c.after.fire > 0 || c.after.health < c.before!!.health, "번개가 떨어지지 않았다") },

        "ADD_HEALTH" to Probe(prepare = { _, t -> (t as? LivingEntity)?.let { it.health = (it.health - 6).coerceAtLeast(1.0) } }) { c ->
            ok(c.after.health > c.before!!.health, "체력이 늘지 않았다")
        },
        "ADD_FOOD" to Probe(prepare = { _, t -> (t as? Player)?.foodLevel = 5 }) { c -> ok(c.after.food != c.before!!.food, "허기가 그대로다") },
        "AIR" to Probe(prepare = { _, t -> (t as? LivingEntity)?.remainingAir = 100 }) { c -> ok(c.after.air != c.before!!.air, "산소가 그대로다") },
        "SET_AIR" to Probe(prepare = { _, t -> (t as? LivingEntity)?.remainingAir = 7 }) { c -> ok(c.after.air != c.before!!.air, "산소가 그대로다") },
        "BURN" to Probe { c -> ok(c.after.fire > 0, "불이 붙지 않았다") },
        "EXTINGUISH" to Probe(prepare = { _, t -> t?.fireTicks = 200 }) { c -> ok(c.after.fire <= 0, "불이 꺼지지 않았다") },
        "FREEZE" to Probe { c -> ok(c.after.potions["slowness"] != null, "구속이 걸리지 않았다") },
        "SCREEN_FREEZE" to Probe { c -> ok(c.after.freeze > 0 && c.after.potions["slowness"] != null, "빙결 화면이 아니다") },
        "SNOWBLIND" to Probe { c -> ok(c.after.potions["blindness"] != null, "실명이 걸리지 않았다") },
        "INVINCIBLE" to Probe { c -> ok(c.after.invulnerable != c.before!!.invulnerable, "무적 상태가 그대로다") },
        "REVIVE" to Probe { c -> ok((c.run.ctx.event as? EntityResurrectEvent)?.isCancelled == false, "부활 사건이 풀리지 않았다") },
        "CURE" to Probe(prepare = { run, t -> potionFrom(run)?.let { (t as? LivingEntity)?.addPotionEffect(PotionEffect(it, 200, 0)) } }) { c ->
            ok(potionKey(c.run)?.let { it !in c.after.potions } ?: false, "효과가 남아 있다")
        },
        "CURE_PERMANENT" to Probe(prepare = { run, t -> potionFrom(run)?.let { (t as? LivingEntity)?.addPotionEffect(PotionEffect(it, PotionEffect.INFINITE_DURATION, 0)) } }) { c ->
            ok(potionKey(c.run)?.let { it !in c.after.potions } ?: false, "영구 효과가 남아 있다")
        },
        "POTION" to Probe { c -> ok(potionKey(c.run)?.let { it in c.after.potions } ?: false, "물약 효과가 걸리지 않았다(${c.run.arg(0)})") },
        "POTION_OVERRIDE" to Probe { c -> ok(potionKey(c.run)?.let { it in c.after.potions } ?: false, "물약 효과가 걸리지 않았다(${c.run.arg(0)})") },
        "TOTEM" to nothingToCheck,
        "PLAY_ENTITY" to nothingToCheck,

        "BOOST" to moved,
        "PULL_AWAY" to moved,
        "PULL_CLOSER" to moved,
        "TELEPORT" to Probe { c -> ok(c.after.location.distance(c.before!!.location) > 0.5, "옮겨지지 않았다") },
        "TELEPORT_BEHIND" to Probe { c -> ok(c.after.location.distance(c.before!!.location) > 0.5, "옮겨지지 않았다") },
        "FLY" to Probe { c -> ok(c.after.allowFlight != c.before!!.allowFlight, "비행 허용이 그대로다") },
        "FLY_SPEED" to Probe(prepare = { _, t -> (t as? Player)?.flySpeed = 0.07f }) { c -> ok(c.after.flySpeed != c.before!!.flySpeed, "비행 속도가 그대로다") },
        "ADD_FLY_SPEED" to Probe { c -> ok(c.after.flySpeed != c.before!!.flySpeed, "비행 속도가 그대로다") },
        "WALK_SPEED" to Probe(prepare = { _, t -> (t as? Player)?.walkSpeed = 0.13f }) { c -> ok(c.after.walkSpeed != c.before!!.walkSpeed, "걷는 속도가 그대로다") },
        "ADD_WALK_SPEED" to Probe { c -> ok(c.after.walkSpeed != c.before!!.walkSpeed, "걷는 속도가 그대로다") },
        "WATER_WALKER" to walker(EngineState.Walker.WATER),
        "LAVA_WALKER" to walker(EngineState.Walker.LAVA),
        "WEB_WALKER" to walker(EngineState.Walker.WEB),

        "BREAK_BLOCK" to blockChanged,
        "BREAK_TREE" to blockChanged,
        "SET_BLOCK" to blockChanged,
        "PLANT_SEEDS" to blockChanged,
        "SMELT" to Probe(delay = 0) { c -> ok(c.run.ctx.smeltDrops, "제련 표시가 없다") },
        "MORE_DROPS" to Probe(delay = 0) { c -> ok(c.run.ctx.dropMultiplier != 1.0, "드랍 배수가 그대로다") },
        "TP_DROPS" to Probe(delay = 0) { c -> ok(c.run.ctx.teleportDrops, "가방으로 표시가 없다") },
        "EXP" to spawned,

        "ADD_DURABILITY_CURRENT_ITEM" to Probe(prepare = { run, _ -> damageHeld(run, 30) }) { c -> ok(c.after.durability != c.before!!.durability, "내구도가 그대로다") },
        "ADD_DURABILITY_ARMOR" to Probe(prepare = { _, t -> dress(t, damage = 40) }) { c -> ok(c.after.armorDamage != c.before!!.armorDamage, "방어구 내구도가 그대로다") },
        "DAMAGE_ARMOR" to Probe(prepare = { _, t -> dress(t, damage = 40) }) { c -> ok(c.after.armorDamage > c.before!!.armorDamage, "방어구가 닳지 않았다") },
        "ADD_DURABILITY_ITEM" to Probe(prepare = { run, t -> (t as? Player)?.inventory?.setItem(run.int(0), damaged(Material.DIAMOND_SWORD, 30)) }) { c ->
            val damage = ((c.target as? Player)?.inventory?.getItem(c.run.int(0))?.itemMeta as? Damageable)?.damage
            ok(damage != null && damage != 30, "${c.run.int(0)}번 칸 내구도가 그대로다")
        },
        "REPAIR" to Probe(prepare = { run, _ -> damageHeld(run, 30) }) { c -> ok(c.after.durability < c.before!!.durability, "수리되지 않았다") },
        "DISARM" to Probe(prepare = { _, t -> (t as? LivingEntity)?.equipment?.setItemInMainHand(ItemStack(Material.STONE_SWORD)) }) { c ->
            ok(c.after.mainHand == Material.AIR, "무기를 그대로 들고 있다")
        },
        "DROP_HEAD" to Probe { c -> ok(c.nearbyAfter > c.nearbyBefore, "머리가 떨어지지 않았다(바닐라 머리가 없는 몹이면 mob-heads.yml 이 필요)") },
        "DROP_HELD_ITEM" to Probe(prepare = { _, t -> (t as? LivingEntity)?.equipment?.setItemInMainHand(ItemStack(Material.STICK)) }) { c ->
            ok(c.after.mainHand == Material.AIR, "들고 있는 것이 그대로다")
        },
        "GIVE_ITEM" to Probe { c -> ok(c.after.inventory > c.before!!.inventory || c.nearbyAfter > c.nearbyBefore, "아이템을 받지 않았다") },
        "DROP_ITEM" to spawned,
        "TAKE_AWAY" to Probe(prepare = { run, t -> Material.matchMaterial(run.arg(0))?.let { (t as? Player)?.inventory?.addItem(ItemStack(it, 8)) } }) { c ->
            ok(c.after.inventory < c.before!!.inventory, "가져가지 않았다")
        },
        "DELETE_ITEM" to Probe { c -> ok(c.after.holderAmount < c.before!!.holderAmount, "개수가 그대로다 ${c.before!!.holderAmount}→${c.after.holderAmount}") },
        "PUMPKIN" to Probe { c -> ok(c.after.equipment[0] == Material.CARVED_PUMPKIN, "호박을 쓰지 않았다") },
        "REMOVE_ARMOR" to Probe(prepare = { _, t -> dress(t) }) { c -> ok(c.after.equipment.count { it != Material.AIR } < 4, "방어구가 그대로다") },
        "REMOVE_RANDOM_ARMOR" to Probe(prepare = { _, t -> dress(t) }) { c -> ok(c.after.equipment.count { it != Material.AIR } < 4, "방어구가 그대로다") },
        "SHUFFLE_HOTBAR" to Probe(prepare = { _, t -> (t as? Player)?.let { p -> HOTBAR.forEachIndexed { i, m -> p.inventory.setItem(i, ItemStack(m)) } } }) { c ->
            // 9 개가 전부 다르므로 섞였는데 제자리일 확률은 1/362880 이다.
            ok(c.after.hotbar.toSet() == c.before!!.hotbar.toSet() && c.after.hotbar != c.before!!.hotbar, "단축바가 섞이지 않았거나 아이템이 사라졌다")
        },
        "CANCEL_USE" to Probe(
            cleanup = { c -> Material.matchMaterial(c.run.arg(0))?.let { (c.target as? Player)?.setCooldown(it, 0) } },
        ) { c -> ok(Material.matchMaterial(c.run.arg(0))?.let { (c.target as? Player)?.hasCooldown(it) } ?: false, "사용 대기시간이 없다") },
        "OPEN_CRAFTING_TABLE" to Probe(cleanup = { c -> (c.target as? Player)?.closeInventory() }) { c ->
            ok((c.target as? Player)?.openInventory?.topInventory?.type == InventoryType.WORKBENCH, "작업대가 안 열렸다")
        },
        "OPEN_ENDERCHEST" to Probe(cleanup = { c -> (c.target as? Player)?.closeInventory() }) { c ->
            ok((c.target as? Player)?.openInventory?.topInventory?.type == InventoryType.ENDER_CHEST, "엔더 상자가 안 열렸다")
        },
        "AUTO_REEL" to Probe(delay = 3) { c -> ok(hook(c)?.isValid == false, "찌가 감기지 않았다") },
        "SET_MAX_CATCH_TIME" to Probe(delay = 0) { c -> ok(hook(c)?.maxWaitTime == c.run.int(0), "최대 입질 시간이 ${hook(c)?.maxWaitTime}") },
        "SET_MIN_CATCH_TIME" to Probe(delay = 0) { c -> ok(hook(c)?.minWaitTime == c.run.int(0), "최소 입질 시간이 ${hook(c)?.minWaitTime}") },

        "GUARD" to spawned,
        "STEAL_GUARD" to nothingToCheck,
        "SPAWN_ENTITY" to spawned,
        "PROJECTILE" to spawned,
        "FIREBALL" to spawned,
        "SPAWN_ARROWS" to spawned,
        "SPAWN_BLOCKS" to spawned,
        "PARTICLE" to nothingToCheck,
        "PARTICLE_LINE" to nothingToCheck,
        "BLOOD" to nothingToCheck,
        "CACTUS" to nothingToCheck,
        "FIREWORK" to spawned,
        "PLAY_SOUND" to nothingToCheck,
        "PLAY_SOUND_OUTLOUD" to nothingToCheck,

        "MESSAGE" to nothingToCheck,
        "ACTION_BAR" to nothingToCheck,
        "TITLE" to nothingToCheck,
        "SUBTITLE" to nothingToCheck,
        "BROADCAST" to nothingToCheck,
        "BROADCAST_PERMISSION" to nothingToCheck,
        "CONSOLE_COMMAND" to nothingToCheck,
        "PLAYER_COMMAND" to nothingToCheck,
        // OP 는 등록 안 된 권한도 전부 "가졌다"고 나온다. 직접 부여됐는지까지 본다.
        "PERMISSION" to Probe(cleanup = { c -> (c.target as? Player)?.let { c.run.enchants.support.permission(it, c.run.arg(0), false) } }) { c ->
            val player = c.target as? Player
            ok(player != null && player.isPermissionSet(c.run.arg(0)) && player.hasPermission(c.run.arg(0)), "권한이 부여되지 않았다")
        },
        "ADD_MONEY" to Probe(skip = needsEconomy, cleanup = { c -> (c.target as? Player)?.let { c.run.enchants.economy.withdraw(it, c.run.num(0)) } }) { c ->
            ok(c.after.money > c.before!!.money, "돈이 늘지 않았다")
        },
        "REMOVE_MONEY" to Probe(skip = needsEconomy, prepare = { run, t -> (t as? Player)?.let { run.enchants.economy.deposit(it, run.num(0)) } }) { c ->
            ok(c.after.money < c.before!!.money, "돈이 줄지 않았다")
        },
        "STEAL_MONEY" to Probe(
            skip = { twoPlayers(it) ?: needsEconomy(it) },
            prepare = { run, t -> (t as? Player)?.let { run.enchants.economy.deposit(it, run.num(0)) } },
        ) { c -> ok(c.after.money < c.before!!.money, "돈을 빼앗기지 않았다") },
        "STEAL_EXP" to Probe(skip = twoPlayers, prepare = { _, t -> (t as? Player)?.giveExp(200) }) { c -> ok(c.after.exp < c.before!!.exp, "경험치가 그대로다") },

        "ADD_ENCHANT" to Probe { c -> ok(c.after.holderEnchants.containsKey(c.run.arg(0).lowercase()), "인첸트가 붙지 않았다") },
        "REMOVE_ENCHANT" to Probe(prepare = { run, _ -> tag(run, run.arg(0).lowercase()) }) { c ->
            ok(!c.after.holderEnchants.containsKey(c.run.arg(0).lowercase()), "인첸트가 그대로 있다")
        },
        "ADD_SOULS" to Probe(prepare = { run, _ -> soulTracker(run, 50) }) { c -> ok(c.after.holderSouls > c.before!!.holderSouls, "영혼이 늘지 않았다") },
        "REMOVE_SOULS" to Probe(prepare = { run, _ -> soulTracker(run, 50) }) { c -> ok(c.after.holderSouls < c.before!!.holderSouls, "영혼이 줄지 않았다") },
        "DISABLE_ACTIVATION" to Probe(delay = 0) { c ->
            ok(c.run.enchants.state.isDisabled(c.target!!.uniqueId, c.run.arg(0), System.currentTimeMillis()), "봉인되지 않았다")
        },
        "UNSEAL" to Probe(delay = 0, prepare = { run, t -> t?.let { run.enchants.state.disable(it.uniqueId, "ALL", 30.0, System.currentTimeMillis()) } }) { c ->
            ok(!c.run.enchants.state.isDisabled(c.target!!.uniqueId, "lifesteal", System.currentTimeMillis()), "봉인이 남아 있다")
        },
        "SET_VARIABLE" to Probe(delay = 0) { c -> ok(c.run.enchants.state.variables[c.run.arg(0)] == c.run.arg(1), "변수가 안 바뀌었다") },
        "INVERT_VARIABLE" to Probe(delay = 0) { c -> ok(c.run.enchants.state.variables[c.run.arg(0)] != null, "변수가 없다") },
        "MARK" to Probe(delay = 0) { c -> ok(c.run.enchants.state.isMarked(c.target!!.uniqueId, c.run.arg(0), System.currentTimeMillis()), "표식이 없다") },
        "RESET_COMBO" to Probe(delay = 0) { c -> ok(c.run.enchants.state.combo(c.target!!.uniqueId, System.currentTimeMillis()) == 0, "콤보가 남아 있다") },
        "WAIT" to nothingToCheck,
    )

    /**
     * 눈으로만 확인할 수 있는 것(입자·소리·메시지·연출)과 결과가 무대 밖에 남는 것(명령어).
     * 검증기는 이것들을 **"실행됨(눈으로 확인)"** 으로 따로 센다 — 통과로 섞지 않는다.
     */
    val OBSERVE_ONLY: Set<String> = ALL.filterValues { it === nothingToCheck }.keys

    /**
     * `/인첸트 검증 효과` 가 쓰는 표본 줄. 무대는 **검증하는 사람이 발동한 쪽**이고 `@Victim` 은
     * 그 앞에 세운 몹이다. 블록 효과는 앞에 쌓은 돌(나무·밭)을 대상으로 한다.
     */
    val SAMPLES: Map<String, String> = mapOf(
        "INCREASE_DAMAGE" to "INCREASE_DAMAGE:50",
        "DECREASE_DAMAGE" to "DECREASE_DAMAGE:50",
        "DOUBLE_DAMAGE" to "DOUBLE_DAMAGE",
        "HALF_DAMAGE" to "HALF_DAMAGE",
        "NEGATE_DAMAGE" to "NEGATE_DAMAGE:2",
        "IGNORE_ARMOR_PROTECTION" to "IGNORE_ARMOR_PROTECTION",
        "IGNORE_ARMOR_DAMAGE" to "IGNORE_ARMOR_DAMAGE",
        "CANCEL_EVENT" to "CANCEL_EVENT",
        "DISABLE_KNOCKBACK" to "DISABLE_KNOCKBACK:40 @Victim",
        "STOP_KNOCKBACK" to "STOP_KNOCKBACK @Victim",
        "DO_HARM" to "DO_HARM:4 @Victim",
        "REMOVE_HEALTH" to "REMOVE_HEALTH:4 @Victim",
        "REMOVE_HEALTH_DAMAGE" to "REMOVE_HEALTH_DAMAGE:4 @Victim",
        "REMOVE_HEALTH_TOTEM" to "REMOVE_HEALTH_TOTEM:4 @Victim",
        "REMOVE_HEALTH_DAMAGE_TOTEM" to "REMOVE_HEALTH_DAMAGE_TOTEM:4 @Victim",
        "KILL" to "KILL @Victim",
        "BLEED" to "BLEED:1:60:20 @Victim",
        "STEAL_HEALTH" to "STEAL_HEALTH:4 @Victim",
        "TNT" to "TNT:4:3 @Victim",
        "EXPLODE" to "EXPLODE:1 @Victim",
        "LIGHTNING" to "LIGHTNING:true @Victim",
        "ADD_HEALTH" to "ADD_HEALTH:4 @Victim",
        "ADD_FOOD" to "ADD_FOOD:4",
        "AIR" to "AIR:100 @Victim",
        "SET_AIR" to "SET_AIR:200 @Victim",
        "BURN" to "BURN:60 @Victim",
        "EXTINGUISH" to "EXTINGUISH @Victim",
        "FREEZE" to "FREEZE:60 @Victim",
        "SCREEN_FREEZE" to "SCREEN_FREEZE:60 @Victim",
        "SNOWBLIND" to "SNOWBLIND:60 @Victim",
        "INVINCIBLE" to "INVINCIBLE:40 @Victim",
        "REVIVE" to "REVIVE",
        "CURE" to "CURE:POISON @Victim",
        "CURE_PERMANENT" to "CURE_PERMANENT:POISON @Victim",
        "POTION" to "POTION:SPEED:0:100 @Victim",
        "POTION_OVERRIDE" to "POTION_OVERRIDE:SPEED:1:100 @Victim",
        "TOTEM" to "TOTEM @Victim",
        "PLAY_ENTITY" to "PLAY_ENTITY:HURT @Victim",
        "BOOST" to "BOOST:UP:1 @Victim",
        "PULL_AWAY" to "PULL_AWAY:2 @Victim",
        "PULL_CLOSER" to "PULL_CLOSER:2 @Victim",
        "TELEPORT" to "TELEPORT @Victim location=~0|0|2",
        "TELEPORT_BEHIND" to "TELEPORT_BEHIND @Victim",
        "FLY" to "FLY:100",
        "FLY_SPEED" to "FLY_SPEED:0.2",
        "ADD_FLY_SPEED" to "ADD_FLY_SPEED:0.05",
        "WALK_SPEED" to "WALK_SPEED:0.3",
        "ADD_WALK_SPEED" to "ADD_WALK_SPEED:0.05",
        "WATER_WALKER" to "WATER_WALKER",
        "LAVA_WALKER" to "LAVA_WALKER",
        "WEB_WALKER" to "WEB_WALKER",
        "BREAK_BLOCK" to "BREAK_BLOCK @Trench{radius=3}",
        "BREAK_TREE" to "BREAK_TREE:16:0 @Block",
        "SET_BLOCK" to "SET_BLOCK:GOLD_BLOCK @Block",
        "PLANT_SEEDS" to "PLANT_SEEDS:1:SEEDS @Block",
        "SMELT" to "SMELT",
        "MORE_DROPS" to "MORE_DROPS:2",
        "TP_DROPS" to "TP_DROPS",
        "EXP" to "EXP:10 @Victim",
        "ADD_DURABILITY_CURRENT_ITEM" to "ADD_DURABILITY_CURRENT_ITEM:10",
        "ADD_DURABILITY_ARMOR" to "ADD_DURABILITY_ARMOR:10 @Victim",
        "DAMAGE_ARMOR" to "DAMAGE_ARMOR:10 @Victim",
        "ADD_DURABILITY_ITEM" to "ADD_DURABILITY_ITEM:9:10",
        "REPAIR" to "REPAIR",
        "DISARM" to "DISARM @Victim",
        "DROP_HEAD" to "DROP_HEAD @Victim",
        "DROP_HELD_ITEM" to "DROP_HELD_ITEM @Victim",
        "GIVE_ITEM" to "GIVE_ITEM:DIRT:2",
        "TAKE_AWAY" to "TAKE_AWAY:DIRT:2",
        "DELETE_ITEM" to "DELETE_ITEM:1",
        "PUMPKIN" to "PUMPKIN:40 @Victim",
        "REMOVE_ARMOR" to "REMOVE_ARMOR:HELMET @Victim",
        "REMOVE_RANDOM_ARMOR" to "REMOVE_RANDOM_ARMOR @Victim",
        "SHUFFLE_HOTBAR" to "SHUFFLE_HOTBAR",
        "CANCEL_USE" to "CANCEL_USE:ENDER_PEARL:100",
        "OPEN_CRAFTING_TABLE" to "OPEN_CRAFTING_TABLE",
        "OPEN_ENDERCHEST" to "OPEN_ENDERCHEST",
        "AUTO_REEL" to "AUTO_REEL",
        "SET_MAX_CATCH_TIME" to "SET_MAX_CATCH_TIME:100",
        "SET_MIN_CATCH_TIME" to "SET_MIN_CATCH_TIME:20",
        "GUARD" to "GUARD:ZOMBIE:5:1",
        "STEAL_GUARD" to "STEAL_GUARD @Victim",
        "SPAWN_ENTITY" to "SPAWN_ENTITY:CHICKEN @Victim",
        "PROJECTILE" to "PROJECTILE:ARROW",
        "FIREBALL" to "FIREBALL",
        "SPAWN_ARROWS" to "SPAWN_ARROWS:3 @Victim",
        "SPAWN_BLOCKS" to "SPAWN_BLOCKS:SAND:2 @Victim",
        "PARTICLE" to "PARTICLE:FLAME:10:0.5 @Victim",
        "PARTICLE_LINE" to "PARTICLE_LINE:FLAME:1:10 @Victim",
        "BLOOD" to "BLOOD @Victim",
        "CACTUS" to "CACTUS @Victim",
        "FIREWORK" to "FIREWORK:RED:WHITE:BALL:0 @Victim",
        "PLAY_SOUND" to "PLAY_SOUND:ENTITY_EXPERIENCE_ORB_PICKUP",
        "PLAY_SOUND_OUTLOUD" to "PLAY_SOUND_OUTLOUD:ENTITY_EXPERIENCE_ORB_PICKUP @Victim",
        "MESSAGE" to "MESSAGE:<gray>[인첸트 검증] 채팅 메시지 효과",
        "ACTION_BAR" to "ACTION_BAR:<gray>[인첸트 검증] 액션바 효과",
        "TITLE" to "TITLE:<gray>인첸트 검증",
        "SUBTITLE" to "SUBTITLE:<gray>부제목 효과",
        "BROADCAST" to "BROADCAST:<gray>[인첸트 검증] 전체 공지 효과",
        "BROADCAST_PERMISSION" to "BROADCAST_PERMISSION:inmcenchant.admin:<gray>[인첸트 검증] 권한자 공지 효과",
        "CONSOLE_COMMAND" to "CONSOLE_COMMAND:list",
        "PLAYER_COMMAND" to "PLAYER_COMMAND:list",
        "PERMISSION" to "PERMISSION:inmcenchant.verify.probe",
        "ADD_MONEY" to "ADD_MONEY:1",
        "REMOVE_MONEY" to "REMOVE_MONEY:1",
        "STEAL_MONEY" to "STEAL_MONEY:1 @Victim",
        "STEAL_EXP" to "STEAL_EXP:10 @Victim",
        "ADD_ENCHANT" to "ADD_ENCHANT:${Verifier.PROBE_ID}:1",
        "REMOVE_ENCHANT" to "REMOVE_ENCHANT:${Verifier.PROBE_ID}",
        "ADD_SOULS" to "ADD_SOULS:5",
        "REMOVE_SOULS" to "REMOVE_SOULS:5",
        "DISABLE_ACTIVATION" to "DISABLE_ACTIVATION:ALL:2 @Victim",
        "UNSEAL" to "UNSEAL @Victim",
        "DROP_ITEM" to "DROP_ITEM:DIRT:1 @Victim",
        "RESET_COMBO" to "RESET_COMBO @Victim",
        "MARK" to "MARK:verify:5 @Victim",
        "SET_VARIABLE" to "SET_VARIABLE:verify_var:1",
        "INVERT_VARIABLE" to "INVERT_VARIABLE:verify_var",
        "WAIT" to "WAIT:1",
    )

    /**
     * 되돌릴 수 있는 효과를 **들고 있는 동안(HELD)** 걸었다가 벗기는 표본. 시간 칸을 비워야
     * "벗을 때까지"가 된다 — 시간을 적으면 그 시간이 지나야 풀리므로 되돌리기를 검사할 수 없다.
     */
    val STATIC_SAMPLES: Map<String, String> = mapOf(
        "INVINCIBLE" to "INVINCIBLE",
        "POTION" to "POTION:SPEED:0",
        "POTION_OVERRIDE" to "POTION_OVERRIDE:SPEED:1",
        "FLY" to "FLY",
        "FLY_SPEED" to "FLY_SPEED:0.2",
        "ADD_FLY_SPEED" to "ADD_FLY_SPEED:0.05",
        "WALK_SPEED" to "WALK_SPEED:0.3",
        "ADD_WALK_SPEED" to "ADD_WALK_SPEED:0.05",
        "WATER_WALKER" to "WATER_WALKER",
        "LAVA_WALKER" to "LAVA_WALKER",
        "WEB_WALKER" to "WEB_WALKER",
        "PERMISSION" to "PERMISSION:inmcenchant.verify.probe",
    )

    private val HOTBAR = listOf(
        Material.STONE, Material.DIRT, Material.OAK_PLANKS, Material.COBBLESTONE, Material.SAND,
        Material.GRAVEL, Material.GLASS, Material.BRICKS, Material.CLAY_BALL,
    )

    /**
     * 플레이어에게만 뜻이 있는 효과. 인첸트 검증에서 이 효과의 대상이 무대의 몹이면 건너뛴다 — 실제
     * 싸움에서는 상대가 플레이어다. 효과 자체는 효과 검증이 검증하는 사람에게 걸어 확인한다.
     */
    val PLAYER_ONLY: Set<String> = setOf(
        "ADD_FOOD", "FLY", "FLY_SPEED", "ADD_FLY_SPEED", "WALK_SPEED", "ADD_WALK_SPEED", "ADD_DURABILITY_ITEM",
        "GIVE_ITEM", "TAKE_AWAY", "SHUFFLE_HOTBAR", "CANCEL_USE", "OPEN_CRAFTING_TABLE", "OPEN_ENDERCHEST",
        "PERMISSION", "ADD_MONEY", "REMOVE_MONEY", "STEAL_MONEY", "STEAL_EXP", "PLAYER_COMMAND",
    )

    private fun walker(kind: EngineState.Walker) = Probe(delay = 0) { c -> ok(c.run.enchants.state.hasWalker(c.target!!.uniqueId, kind), "이동 능력 표시가 없다") }

    private fun potionFrom(run: EffectRun) = run.enchants.support.potion(run.arg(0))

    private fun potionKey(run: EffectRun): String? = Names.potion(run.arg(0))

    private fun damaged(material: Material, damage: Int) = ItemStack(material).also { stack ->
        stack.editMeta(Damageable::class.java) { it.damage = damage }
    }

    private fun damageHeld(run: EffectRun, amount: Int) {
        val stack = run.ctx.item ?: return
        stack.editMeta(Damageable::class.java) { it.damage = amount }
        run.ctx.slot?.let { run.ctx.self.equipment?.setItem(it, stack) }
    }

    private fun dress(target: Entity?, damage: Int = 0) {
        val eq = (target as? LivingEntity)?.equipment ?: return
        fun piece(material: Material) = if (damage > 0) damaged(material, damage) else ItemStack(material)
        eq.setHelmet(piece(Material.IRON_HELMET))
        eq.setChestplate(piece(Material.IRON_CHESTPLATE))
        eq.setLeggings(piece(Material.IRON_LEGGINGS))
        eq.setBoots(piece(Material.IRON_BOOTS))
    }

    /** 검사 전에 주 손 아이템에 인첸트를 하나 붙여 둔다(떼기 효과 검사용). */
    private fun tag(run: EffectRun, id: String) {
        val stack = run.ctx.item ?: return
        val enchants = EnchantStorage.read(stack)
        enchants[id] = 1
        EnchantStorage.write(stack, enchants)
        run.ctx.slot?.let { run.ctx.self.equipment?.setItem(it, stack) }
    }

    private fun soulTracker(run: EffectRun, souls: Int) {
        val stack = run.ctx.item ?: return
        EnchantStorage.setFlag(stack, Keys.SOUL_TRACKER, true)
        EnchantStorage.setInt(stack, Keys.SOULS, souls)
        run.ctx.slot?.let { run.ctx.self.equipment?.setItem(it, stack) }
    }
}
