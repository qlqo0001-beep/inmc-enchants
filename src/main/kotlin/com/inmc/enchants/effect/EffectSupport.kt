package com.inmc.enchants.effect

import com.inmc.enchants.Enchants
import com.inmc.enchants.engine.EngineState
import com.inmc.enchants.engine.TriggerContext
import com.inmc.enchants.item.Keys
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Firework
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.persistence.PersistentDataType
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 효과들이 같이 쓰는 도구와, 효과가 걸어 둔 것을 **나중에** 처리하는 리스너.
 *
 * 호위·호박·출혈·빙결처럼 "일정 시간 뒤 되돌리기"가 필요한 효과는 여기서 작업을 건다. 서버가
 * 꺼지거나 사람이 나가면 [shutdown] · [PlayerQuitEvent] 가 되돌린다 — 호박을 쓴 채로 나간 사람의
 * 투구가 영영 호박이 되면 안 된다.
 */
class EffectSupport(private val e: Enchants) : Listener {

    // --- 효과가 준 피해의 재귀 막기 ------------------------------------------------------

    /**
     * 효과가 피해를 주는 동안 true. 리스너가 이 값을 보고 **발동 조건을 다시 쏘지 않는다.**
     * 안 그러면 "때리면 피해를 준다" 두 개가 서로를 부르며 서버가 멈춘다.
     */
    @Volatile
    var effectDamage: Boolean = false
        private set

    fun damage(target: LivingEntity, amount: Double, source: Entity?) {
        if (amount <= 0) return
        val was = effectDamage
        effectDamage = true
        try {
            if (source != null && source != target) target.damage(amount, source) else target.damage(amount)
        } finally {
            effectDamage = was
        }
    }

    /** 방어구를 무시하고 체력을 깎는다. 0 이 되면 죽는다(토템 없음). */
    fun removeHealth(target: LivingEntity, amount: Double, animate: Boolean) {
        if (amount <= 0 || target.isDead) return
        val next = target.health - amount
        if (animate) runCatching { target.playHurtAnimation(0f) }
        target.health = next.coerceAtLeast(0.0)
    }

    /** 체력을 깎되 죽을 만큼이면 진짜 피해로 — 불사의 토템이 끼어들 수 있다. */
    fun removeHealthTotem(target: LivingEntity, amount: Double, animate: Boolean) {
        if (amount <= 0 || target.isDead) return
        if (target.health - amount > 0) {
            removeHealth(target, amount, animate)
            return
        }
        damage(target, target.health + 1000.0, null)
    }

    // --- 동맹 판정 ----------------------------------------------------------------------

    /** 같은 편인가. 자기 호위·자기 길들인 동물은 편이다. `@Aoe{target=DAMAGEABLE}` 이 뺀다. */
    fun isAlly(self: Entity, other: Entity): Boolean {
        if (self == other) return true
        guards[other.uniqueId]?.let { if (it.owner == self.uniqueId) return true }
        val tame = other as? Tameable
        if (tame != null && tame.isTamed && tame.ownerUniqueId == self.uniqueId) return true
        return false
    }

    // --- 호위(GUARD) -------------------------------------------------------------------

    private class Guard(var owner: UUID, var enemy: UUID?, val until: Long)

    private val guards = ConcurrentHashMap<UUID, Guard>()

    /**
     * 호위를 부른다. [owner] 를 지키며 [enemy] 를 노린다. [switch] 면 거꾸로 — [owner] 를 노린다.
     */
    fun spawnGuards(owner: LivingEntity, enemy: Entity?, typeRaw: String, seconds: Int, amount: Int, name: String, switch: Boolean) {
        val baby = typeRaw.uppercase().startsWith("BABY_")
        val type = runCatching { org.bukkit.entity.EntityType.valueOf(typeRaw.uppercase().removePrefix("BABY_")) }.getOrNull() ?: return
        if (!type.isSpawnable || !type.isAlive) return
        val until = System.currentTimeMillis() + seconds.coerceIn(1, 600) * 1000L
        repeat(amount.coerceIn(1, 10)) {
            val at = owner.location.clone().add((Math.random() - 0.5) * 3, 0.0, (Math.random() - 0.5) * 3)
            val spawned = owner.world.spawnEntity(at, type)
            spawned.persistentDataContainer.set(GUARD_KEY, PersistentDataType.STRING, owner.uniqueId.toString())
            spawned.isPersistent = false
            if (name.isNotBlank()) {
                spawned.customName(e.text(name.replace("%player%", owner.name).replace("%owner%", owner.name)))
                spawned.isCustomNameVisible = true
            }
            if (baby) (spawned as? org.bukkit.entity.Ageable)?.setBaby()
            val guardOwner = if (switch) (enemy ?: owner) else owner
            val guardEnemy = if (switch) owner else enemy
            guards[spawned.uniqueId] = Guard(guardOwner.uniqueId, guardEnemy?.uniqueId, until)
            (spawned as? Mob)?.let { mob -> (guardEnemy as? LivingEntity)?.let { mob.target = it } }
        }
    }

    /** 상대의 호위를 내 편으로. */
    fun stealGuards(from: Entity, to: LivingEntity, enemy: Entity?) {
        for ((id, guard) in guards) {
            if (guard.owner != from.uniqueId) continue
            guard.owner = to.uniqueId
            guard.enemy = enemy?.uniqueId
            val mob = Bukkit.getEntity(id) as? Mob ?: continue
            (enemy as? LivingEntity)?.let { mob.target = it }
        }
    }

    fun isGuard(entity: Entity): Boolean = guards.containsKey(entity.uniqueId)

    /** 주인이 맞으면 호위가 때린 쪽을 노린다. */
    fun ownerAttacked(owner: Entity, attacker: Entity) {
        if (attacker !is LivingEntity) return
        for ((id, guard) in guards) {
            if (guard.owner != owner.uniqueId || id == attacker.uniqueId) continue
            val mob = Bukkit.getEntity(id) as? Mob ?: continue
            if (guards[attacker.uniqueId]?.owner == owner.uniqueId) continue
            mob.target = attacker
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onGuardTarget(event: EntityTargetEvent) {
        val guard = guards[event.entity.uniqueId] ?: return
        val target = event.target ?: return
        // 주인과 같은 편은 노리지 않는다.
        if (target.uniqueId == guard.owner || guards[target.uniqueId]?.owner == guard.owner) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onGuardDeath(event: EntityDeathEvent) {
        if (guards.remove(event.entity.uniqueId) != null) {
            event.drops.clear()
            event.droppedExp = 0
        }
    }

    // --- 호박(PUMPKIN) -----------------------------------------------------------------

    private val pumpkins = ConcurrentHashMap<UUID, ItemStack?>()

    fun pumpkin(target: LivingEntity, ticks: Int) {
        val equipment = target.equipment ?: return
        if (pumpkins.containsKey(target.uniqueId)) return
        pumpkins[target.uniqueId] = equipment.helmet?.clone()
        equipment.setHelmet(ItemStack(Material.CARVED_PUMPKIN))
        later(ticks.toLong().coerceAtLeast(1)) { restorePumpkin(target.uniqueId) }
    }

    private fun restorePumpkin(id: UUID) {
        val original = pumpkins.remove(id) ?: return
        val entity = Bukkit.getEntity(id) as? LivingEntity ?: return
        entity.equipment?.setHelmet(original)
    }

    // --- 출혈(BLEED) · 빙결(FREEZE) -----------------------------------------------------

    fun bleed(target: LivingEntity, damage: Double, ticks: Int, interval: Int) {
        val step = interval.coerceAtLeast(1).toLong()
        val times = (ticks / step).toInt().coerceIn(1, 200)
        var done = 0
        Bukkit.getScheduler().runTaskTimer(e.plugin, { task ->
            if (!target.isValid || target.isDead || done >= times) {
                task.cancel()
                return@runTaskTimer
            }
            removeHealth(target, damage, animate = true)
            target.world.spawnParticle(Particle.BLOCK, target.location.add(0.0, 1.0, 0.0), 12, 0.2, 0.3, 0.2, Material.REDSTONE_BLOCK.createBlockData())
            done++
        }, step, step)
    }

    fun freeze(target: LivingEntity, ticks: Int) {
        val duration = ticks.coerceIn(1, 20 * 60)
        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, duration, 10, false, false))
        target.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, duration, 200, false, false))
        target.freezeTicks = target.maxFreezeTicks
        var left = duration
        Bukkit.getScheduler().runTaskTimer(e.plugin, { task ->
            if (!target.isValid || left <= 0) {
                task.cancel()
                return@runTaskTimer
            }
            target.velocity = target.velocity.setX(0).setZ(0)
            left -= 2
        }, 1L, 2L)
    }

    fun screenFreeze(target: LivingEntity, ticks: Int) {
        val duration = ticks.coerceIn(1, 20 * 60)
        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, duration, 3, false, false))
        target.lockFreezeTicks(true)
        target.freezeTicks = target.maxFreezeTicks
        later(duration.toLong()) {
            if (target.isValid) {
                target.lockFreezeTicks(false)
                target.freezeTicks = 0
            }
        }
    }

    // --- 폭죽 · 낙석 ---------------------------------------------------------------------

    fun firework(location: Location, color: Color, fade: Color, type: org.bukkit.FireworkEffect.Type, power: Int, trail: Boolean, flicker: Boolean) {
        val firework = location.world.spawn(location, Firework::class.java) { fw ->
            fw.persistentDataContainer.set(HARMLESS_KEY, PersistentDataType.BYTE, 1)
            fw.fireworkMeta = fw.fireworkMeta.apply {
                addEffect(org.bukkit.FireworkEffect.builder().with(type).withColor(color).withFade(fade).trail(trail).flicker(flicker).build())
                this.power = power.coerceIn(0, 3)
            }
        }
        if (power <= 0) later(1L) { if (firework.isValid) firework.detonate() }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHarmlessFirework(event: EntityDamageByEntityEvent) {
        if (event.damager.persistentDataContainer.has(HARMLESS_KEY)) event.isCancelled = true
    }

    fun fallingBlocks(location: Location, material: Material, damage: Double, owner: Entity) {
        if (!material.isBlock) return
        repeat(3) {
            val at = location.clone().add((Math.random() - 0.5) * 2, 6.0 + it, (Math.random() - 0.5) * 2)
            val falling = location.world.spawn(at, FallingBlock::class.java) { fb ->
                fb.blockData = material.createBlockData()
                fb.dropItem = false
                fb.setHurtEntities(false)
                fb.persistentDataContainer.set(FALLING_KEY, PersistentDataType.STRING, owner.uniqueId.toString() + ";" + damage)
            }
            falling.velocity = org.bukkit.util.Vector(0.0, -0.4, 0.0)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFallingLand(event: EntityChangeBlockEvent) {
        val falling = event.entity as? FallingBlock ?: return
        val raw = falling.persistentDataContainer.get(FALLING_KEY, PersistentDataType.STRING) ?: return
        event.isCancelled = true
        falling.remove()
        val owner = runCatching { UUID.fromString(raw.substringBefore(';')) }.getOrNull()
        val damage = raw.substringAfter(';').toDoubleOrNull() ?: 0.0
        val ownerEntity = owner?.let { Bukkit.getEntity(it) }
        for (near in falling.getNearbyEntities(1.5, 1.5, 1.5)) {
            val living = near as? LivingEntity ?: continue
            if (living.uniqueId == owner || (ownerEntity != null && isAlly(ownerEntity, living))) continue
            damage(living, damage, ownerEntity)
        }
        falling.world.spawnParticle(Particle.BLOCK, falling.location, 20, 0.4, 0.2, 0.4, falling.blockData)
    }

    // --- 권한(PERMISSION) --------------------------------------------------------------

    private val attachments = ConcurrentHashMap<UUID, PermissionAttachment>()

    fun permission(player: Player, node: String, on: Boolean?) {
        val attachment = attachments.getOrPut(player.uniqueId) { player.addAttachment(e.plugin) }
        val current = attachment.permissions[node] == true
        val next = on ?: !current
        if (next) attachment.setPermission(node, true) else attachment.unsetPermission(node)
    }

    // --- 이동: 넉백 · 물·용암·거미줄 ----------------------------------------------------

    @EventHandler(ignoreCancelled = true)
    fun onKnockback(event: io.papermc.paper.event.entity.EntityKnockbackEvent) {
        if (e.state.knockbackBlocked(event.entity.uniqueId, System.currentTimeMillis())) event.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onArmorDamage(event: PlayerItemDamageEvent) {
        if (!e.state.armorProtected(event.player.uniqueId, System.currentTimeMillis())) return
        val slot = event.item.type.equipmentSlot
        if (slot.isArmor) event.isCancelled = true
    }

    private val temporary = ConcurrentHashMap<Block, Pair<Material, Long>>()

    /**
     * 물·용암 위 걷기. 발밑 3×3 의 원천 블록을 잠깐 굳혔다가 되돌린다. 바닐라 서리 걷기와 같은
     * 원리이고, **원래 블록을 기억해 두었다가** 되돌리므로 강물이 사라지지 않는다.
     */
    @EventHandler(ignoreCancelled = true)
    fun onWalk(event: PlayerMoveEvent) {
        val to = event.to
        if (event.from.blockX == to.blockX && event.from.blockY == to.blockY && event.from.blockZ == to.blockZ) return
        val player = event.player
        val id = player.uniqueId
        val water = e.state.hasWalker(id, EngineState.Walker.WATER)
        val lava = e.state.hasWalker(id, EngineState.Walker.LAVA)
        val web = e.state.hasWalker(id, EngineState.Walker.WEB)
        if (!water && !lava && !web) return
        if (web && to.block.type == Material.COBWEB) {
            player.velocity = to.direction.setY(0).normalize().multiply(0.25)
        }
        if (!water && !lava) return
        if (player.isSneaking) return
        val below = to.clone().subtract(0.0, 1.0, 0.0).block
        val until = System.currentTimeMillis() + 3000L
        for (dx in -1..1) for (dz in -1..1) {
            val block = below.getRelative(dx, 0, dz)
            val above = block.getRelative(0, 1, 0)
            if (!above.type.isAir) continue
            val replacement = when {
                water && block.type == Material.WATER && isSource(block) -> Material.FROSTED_ICE
                lava && block.type == Material.LAVA && isSource(block) -> Material.MAGMA_BLOCK
                else -> continue
            }
            temporary[block] = block.type to until
            block.type = replacement
        }
    }

    private fun isSource(block: Block): Boolean = (block.blockData as? org.bukkit.block.data.Levelled)?.level == 0

    /** 굳힌 블록을 되돌린다. 1초 틱커가 부른다. */
    fun restoreTemporary(now: Long, force: Boolean = false) {
        val iterator = temporary.entries.iterator()
        while (iterator.hasNext()) {
            val (block, value) = iterator.next()
            if (!force && value.second > now) continue
            if (block.type == Material.FROSTED_ICE || block.type == Material.MAGMA_BLOCK) block.type = value.first
            iterator.remove()
        }
    }

    // --- 블록 부수기 ------------------------------------------------------------------

    /** 효과가 블록을 부수는 동안 true. 채굴 리스너가 이것을 보고 MINING 을 다시 쏘지 않는다. */
    @Volatile
    var effectBreaking: Boolean = false
        private set

    /**
     * 보호 플러그인을 존중해 부순다 — 가짜 [BlockBreakEvent] 를 쏘아 누가 취소하면 안 부순다.
     * 드랍은 [ctx] 의 드랍 효과(배수·제련·가방으로)를 똑같이 받는다.
     *
     * [collectDrops] 가 false 면 블록은 깨지되(연출 포함) 아이템은 나오지 않는다 — 광역 채굴로
     * 딸려 깨지는 비광석용이다. 직접 캔 블록은 이 길을 타지 않으므로(바닐라가 부순다) 그대로 나온다.
     */
    fun breakBlock(
        player: Player?,
        block: Block,
        tool: ItemStack?,
        ctx: TriggerContext,
        collectDrops: Boolean = true,
    ): Boolean {
        val type = block.type
        if (type.isAir || !type.isBlock || block.isLiquid || type.hardness < 0) return false
        if (UNBREAKABLE.contains(type.name)) return false
        if (player != null) {
            val check = BlockBreakEvent(block, player)
            effectBreaking = true
            try {
                Bukkit.getPluginManager().callEvent(check)
            } finally {
                effectBreaking = false
            }
            if (check.isCancelled) return false
        }
        val location = block.location.add(0.5, 0.5, 0.5)
        breakFeedback(player, block, location)
        block.type = Material.AIR
        if (!collectDrops) return true
        val drops = if (tool != null) block.getDrops(tool, player) else block.drops
        e.drops.deliver(player, location, drops.toMutableList(), ctx)
        return true
    }

    /** 사람마다 마지막으로 깨는 소리를 낸 틱 — 광역 채굴 한 번에 한 번만 울리게. */
    private val soundTick = java.util.concurrent.ConcurrentHashMap<java.util.UUID, Int>()

    /**
     * 효과로 깬 블록의 연출 — 입자는 블록마다, **소리는 한 번(같은 틱·같은 사람)에 한 번만, 조금 작게.**
     * 전에는 블록마다 `STEP_SOUND`(입자 + 원래 크기의 깨는 소리)라 3x3 한 번에 8번이 겹쳐 울렸다(테섭 2026-10-02 "소리가 너무 시끄럽다").
     * 캔 블록 자체는 바닐라가 이미 소리를 낸다.
     */
    private fun breakFeedback(player: Player?, block: Block, location: org.bukkit.Location) {
        val data = block.blockData
        block.world.spawnParticle(Particle.BLOCK, location, 14, 0.25, 0.25, 0.25, 0.0, data)
        val now = Bukkit.getCurrentTick()
        if (player != null && soundTick.put(player.uniqueId, now) == now) return
        block.world.playSound(location, data.soundGroup.breakSound, 0.55f, 0.85f)
    }

    // --- 연출 도구 ------------------------------------------------------------------

    /** 데이터가 필요한 입자에 기본 데이터를 준다. 안 주면 `spawnParticle` 이 던진다(지뢰 표 참조). */
    fun particleData(particle: Particle): Any? = when (particle.dataType) {
        Void::class.java -> null
        Particle.DustOptions::class.java -> Particle.DustOptions(Color.RED, 1.2f)
        Particle.DustTransition::class.java -> Particle.DustTransition(Color.RED, Color.WHITE, 1.2f)
        org.bukkit.block.data.BlockData::class.java -> Material.STONE.createBlockData()
        ItemStack::class.java -> ItemStack(Material.STONE)
        Float::class.java, java.lang.Float::class.java -> 0f
        Int::class.java, java.lang.Integer::class.java -> 0
        Color::class.java -> Color.RED
        else -> null
    }

    fun particle(name: String): Particle? = runCatching { Particle.valueOf(name.trim().uppercase()) }.getOrNull()

    /** `ENTITY_PLAYER_LEVELUP` 같은 옛 이름과 `entity.player.levelup` 같은 키 둘 다 받는다. */
    fun sound(name: String): Sound? {
        val raw = name.trim()
        if (raw.contains('.') || raw.contains(':')) {
            val key = NamespacedKey.fromString(raw.lowercase()) ?: return null
            return runCatching { org.bukkit.Registry.SOUNDS.get(key) }.getOrNull()
        }
        return runCatching { Sound::class.java.getField(raw.uppercase()).get(null) as? Sound }.getOrNull()
    }

    fun potion(name: String): PotionEffectType? {
        val key = com.inmc.enchants.engine.Names.potion(name) ?: return null
        return org.bukkit.Registry.EFFECT.get(NamespacedKey.minecraft(key))
    }

    fun later(ticks: Long, block: () -> Unit) {
        Bukkit.getScheduler().runTaskLater(e.plugin, Runnable { block() }, ticks.coerceAtLeast(0L))
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        restorePumpkin(id)
        attachments.remove(id)?.let { runCatching { event.player.removeAttachment(it) } }
        if (event.player.isInvulnerable && event.player.gameMode.name != "CREATIVE") event.player.isInvulnerable = false
    }

    /** 서버 종료. 걸어 둔 것을 되돌린다. */
    fun shutdown() {
        for (id in pumpkins.keys.toList()) restorePumpkin(id)
        restoreTemporary(0L, force = true)
        for ((id, _) in guards) Bukkit.getEntity(id)?.remove()
        guards.clear()
    }

    /** 오래된 호위를 치운다. 1초 틱커가 부른다. */
    fun sweep(now: Long) {
        val iterator = guards.entries.iterator()
        while (iterator.hasNext()) {
            val (id, guard) = iterator.next()
            if (guard.until > now) continue
            Bukkit.getEntity(id)?.remove()
            iterator.remove()
        }
        restoreTemporary(now)
    }

    companion object {
        val GUARD_KEY = NamespacedKey(Keys.NAMESPACE, "guard")
        val HARMLESS_KEY = NamespacedKey(Keys.NAMESPACE, "harmless")
        val FALLING_KEY = NamespacedKey(Keys.NAMESPACE, "falling")
        private val UNBREAKABLE = setOf(
            "BEDROCK", "BARRIER", "COMMAND_BLOCK", "CHAIN_COMMAND_BLOCK", "REPEATING_COMMAND_BLOCK",
            "END_PORTAL_FRAME", "END_PORTAL", "NETHER_PORTAL", "STRUCTURE_BLOCK", "JIGSAW", "LIGHT", "REINFORCED_DEEPSLATE",
        )
    }
}
