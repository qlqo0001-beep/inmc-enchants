package com.inmc.enchants.engine

import com.inmc.enchants.item.EnchantStorage
import com.inmc.enchants.item.Keys
import org.bukkit.Location
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Enemy
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack

/**
 * `%...%` 변수를 푼다. AE 와 같은 이름이다(`wiki: Conditions Variables · Variables`).
 *
 * `%victim health%` 처럼 앞에 `player`·`attacker`·`victim` 을 붙여 누구의 값인지 정한다. 안 붙이면
 * `player`(발동한 쪽)다. 모르는 이름이 `누구 이름` 모양이면 PlaceholderAPI 에 그 사람 기준으로 묻는다
 * (`%victim player_level%` → PAPI `%player_level%`).
 *
 * **두 바퀴로 푼다.** 사람별 변수 `%custom_clicks%attacker name%%` 는 안의 `%attacker name%` 을 먼저
 * 풀어야 이름이 완성된다. 첫 바퀴는 `custom_` 이 아닌 것만, 둘째 바퀴가 `custom_` 을 푼다.
 * 모르는 것은 **그대로 둔다** — `50%` 같은 글자를 망가뜨리지 않기 위해서다.
 */
class Variables(
    private val customValues: (String) -> String?,
    private val papi: ((Player, String) -> String?)? = null,
    private val combo: (Entity) -> Int = { 0 },
    private val souls: (ItemStack?) -> Int = { EnchantStorage.int(it, Keys.SOULS) },
    /** 조건에서 목록처럼 쓰는 사용자 정의 변수(AE `abilities.yml` 의 `conditions.custom`). 리로드로 바뀐다. */
    private val listVariables: () -> Map<String, String> = { emptyMap() },
    /** 이 엔티티에 이 이름의 표식이 살아 있는가(MARK). */
    private val marked: (Entity, String) -> Boolean = { _, _ -> false },
) {

    fun resolve(text: String, ctx: TriggerContext): String {
        if (!text.contains('%')) return text
        val first = scan(text) { name -> if (name.startsWith("custom_")) null else value(name, ctx) }
        return scan(first) { name -> if (name.startsWith("custom_")) customValues(name.removePrefix("custom_")) ?: "0" else null }
    }

    /** 한 글자씩 옮겨 가며 `%이름%` 을 찾는다. 모르는 이름이면 토큰째 건너뛰지 않고 한 칸만 간다. */
    private fun scan(text: String, lookup: (String) -> String?): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%') {
                val end = text.indexOf('%', i + 1)
                if (end > i + 1) {
                    val name = text.substring(i + 1, end)
                    if (!name.startsWith(' ') && !name.endsWith(' ')) {
                        val value = lookup(name)
                        if (value != null) {
                            out.append(value)
                            i = end + 1
                            continue
                        }
                    }
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    /** 한 이름의 값. 모르면 null. */
    fun value(rawName: String, ctx: TriggerContext): String? {
        listVariables()["%$rawName%"]?.let { return it }
        val name = rawName.trim()
        val lower = name.lowercase()

        // --- 발동 전체의 값 ---
        when (lower) {
            "level" -> return ctx.level.toString()
            "trigger type" -> return ctx.trigger.name
            "is removed", "is removal" -> return ctx.removal.toString()
            "system time" -> return System.currentTimeMillis().toString()
            "hit location" -> return ctx.hitLocation?.let(::loc) ?: ctx.block?.location?.let(::loc)
            "block location" -> return ctx.block?.location?.let(::loc)
            "block x" -> return ctx.block?.x?.toString()
            "block y" -> return ctx.block?.y?.toString()
            "block z" -> return ctx.block?.z?.toString()
            "block type" -> return ctx.block?.type?.name ?: ctx.values["block type"]
            "block type lowercase" -> return (ctx.block?.type?.name ?: ctx.values["block type"])?.lowercase()
            "is crop" -> return ((ctx.block?.blockData as? Ageable) != null).toString()
            "is fully grown" -> return ((ctx.block?.blockData as? Ageable)?.let { it.age >= it.maximumAge } ?: false).toString()
            "block is interactable" -> return (ctx.block?.type?.isInteractable ?: false).toString()
            // 한 번 발동에 하나. 줄마다 따로 굴리면 "셋 중 하나" 가 0~3 개가 된다 — 줄마다 <condition> 으로 구간을 나눠 쓴다.
            "roll" -> return ctx.values.getOrPut("roll:" + ctx.enchantId) { num(java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 100.0) }
            "block tags" -> return ctx.values["block tags"]
            "souls on item" -> return souls(ctx.item).toString()
            "maximum durability" -> return ctx.item?.type?.maxDurability?.toString()
            "item slot" -> return ctx.slot?.name
            // 발동한 쪽과 상대 사이. 다른 월드면 매우 멀다고 본다.
            "distance" -> return ctx.opponent?.let { other ->
                if (other.world != ctx.self.world) "9999" else num(other.location.distance(ctx.self.location))
            } ?: "0"
        }
        ctx.values[lower]?.let { return it }

        // --- 누구의 값 ---
        val (who, field) = when {
            lower.startsWith("player ") -> ctx.self to name.substring(7)
            lower.startsWith("attacker ") -> ctx.attacker to name.substring(9)
            lower.startsWith("victim ") -> ctx.victim to name.substring(7)
            else -> ctx.self to name
        }
        val entity = who ?: return if (lower.startsWith("attacker ") || lower.startsWith("victim ")) "" else null
        entityValue(entity, field.trim(), ctx)?.let { return it }

        // --- PlaceholderAPI: `누구 이름` 모양일 때만 ---
        if (name.contains(' ')) {
            val player = entity as? Player ?: return null
            return papi?.invoke(player, "%" + field.trim() + "%")
        }
        return null
    }

    private fun entityValue(entity: Entity, field: String, ctx: TriggerContext): String? {
        val f = field.lowercase()
        val living = entity as? LivingEntity
        val player = entity as? Player
        return when (f) {
            "name" -> (entity as? Player)?.name ?: entity.name
            "custom name" -> entity.customName() ?.let { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it) } ?: ""
            "health" -> living?.health?.let(::num) ?: "0"
            "max health" -> living?.let { num(maxHealth(it)) } ?: "0"
            "health percentage" -> living?.let { num(it.health / maxHealth(it).coerceAtLeast(0.0001) * 100) } ?: "0"
            "food" -> player?.foodLevel?.toString() ?: "20"
            "world" -> entity.world.name
            "environment" -> entity.world.environment.name
            "x" -> entity.location.blockX.toString()
            "y" -> entity.location.blockY.toString()
            "z" -> entity.location.blockZ.toString()
            "x double" -> num(entity.location.x)
            "y double" -> num(entity.location.y)
            "z double" -> num(entity.location.z)
            "yaw" -> num(entity.location.yaw.toDouble())
            "pitch" -> num(entity.location.pitch.toDouble())
            "is sneaking", "is crouching" -> (player?.isSneaking ?: false).toString()
            "is sprinting" -> (player?.isSprinting ?: false).toString()
            "is flying" -> (player?.isFlying ?: false).toString()
            "is gliding" -> (living?.isGliding ?: false).toString()
            "is blocking" -> (player?.isBlocking ?: false).toString()
            "is on fire" -> (entity.fireTicks > 0).toString()
            "is under water" -> entity.isInWater.toString()
            "is riding" -> entity.isInsideVehicle.toString()
            "passengers" -> entity.passengers.size.toString()
            "is holding" -> living?.equipment?.itemInMainHand?.type?.name ?: "AIR"
            "offhand item" -> living?.equipment?.itemInOffHand?.type?.name ?: "AIR"
            "helmet" -> living?.equipment?.helmet?.type?.name ?: "AIR"
            "chestplate" -> living?.equipment?.chestplate?.type?.name ?: "AIR"
            "leggings" -> living?.equipment?.leggings?.type?.name ?: "AIR"
            "boots" -> living?.equipment?.boots?.type?.name ?: "AIR"
            "mob type" -> entity.type.name
            "is hostile" -> (entity is Enemy).toString()
            "is damageable" -> (living != null && !entity.isInvulnerable && (player == null || player.gameMode.name in setOf("SURVIVAL", "ADVENTURE"))).toString()
            "is op" -> (player?.isOp ?: false).toString()
            "gamemode" -> player?.gameMode?.name ?: ""
            "permissions" -> player?.effectivePermissions?.filter { it.value }?.joinToString(",") { it.permission } ?: ""
            "can break" -> ctx.values["can break"] ?: "true"
            "nearby mobs" -> entity.getNearbyEntities(10.0, 10.0, 10.0).count { it is Mob }.toString()
            "players" -> entity.getNearbyEntities(10.0, 10.0, 10.0).count { it is Player }.toString()
            "block below" -> entity.location.clone().subtract(0.0, 0.1, 0.0).block.type.name
            "light level" -> entity.location.block.lightLevel.toString()
            "time" -> entity.world.time.toString()
            "combo" -> combo(entity).toString()
            "is bleeding" -> "false"
            "faction land" -> ""
            "client version" -> player?.protocolVersion?.toString() ?: ""
            "souls on item" -> souls(living?.equipment?.itemInMainHand).toString()
            else -> patternValue(entity, living, f)
        }
    }

    /** `has potion effect speed` · `item in hand level lifesteal` 처럼 인자가 붙은 것. */
    private fun patternValue(entity: Entity, living: LivingEntity?, f: String): String? {
        val equipment = living?.equipment
        HAS_POTION.matchEntire(f)?.let { m ->
            val key = Names.potion(m.groupValues[1]) ?: return "false"
            return (living?.activePotionEffects?.any { it.type.key.key == key } ?: false).toString()
        }
        POTION_LEVEL.matchEntire(f)?.let { m ->
            val key = Names.potion(m.groupValues[1]) ?: return "false"
            val amp = m.groupValues[2].toIntOrNull() ?: return "false"
            return (living?.activePotionEffects?.any { it.type.key.key == key && it.amplifier == amp } ?: false).toString()
        }
        HAS_HAND_LEVEL.matchEntire(f)?.let { m ->
            val level = EnchantStorage.level(equipment?.itemInMainHand, m.groupValues[1])
            return (level == m.groupValues[2].toIntOrNull()).toString()
        }
        HAS_HAND.matchEntire(f)?.let { m ->
            return (EnchantStorage.level(equipment?.itemInMainHand, m.groupValues[1]) > 0).toString()
        }
        HAS_SLOT.matchEntire(f)?.let { m ->
            val stack = when (m.groupValues[1]) {
                "helmet" -> equipment?.helmet
                "chestplate" -> equipment?.chestplate
                "leggings" -> equipment?.leggings
                "boots" -> equipment?.boots
                "offhand" -> equipment?.itemInOffHand
                else -> equipment?.itemInMainHand
            }
            return (EnchantStorage.level(stack, m.groupValues[2]) > 0).toString()
        }
        HAND_LEVEL.matchEntire(f)?.let { m ->
            return EnchantStorage.level(equipment?.itemInMainHand, m.groupValues[1]).toString()
        }
        HAS_MARK.matchEntire(f)?.let { m -> return marked(entity, m.groupValues[1]).toString() }
        SKILL.matchEntire(f)?.let { return "0" }
        return null
    }

    private fun maxHealth(entity: LivingEntity): Double =
        entity.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH)?.value ?: 20.0

    companion object {
        private val HAS_POTION = Regex("has potion effect ([a-z_:]+)")
        private val POTION_LEVEL = Regex("potion effect level ([a-z_:]+) (\\d+)")
        private val HAS_HAND_LEVEL = Regex("has enchantment in hand of ([a-z0-9_]+) level (\\d+)")
        private val HAS_HAND = Regex("has enchantment in hand of ([a-z0-9_]+)")
        private val HAS_SLOT = Regex("has enchantment in ([a-z]+) of ([a-z0-9_]+)")
        private val HAND_LEVEL = Regex("item in hand level ([a-z0-9_]+)")
        private val SKILL = Regex("level of skill (.+)")
        private val HAS_MARK = Regex("has mark ([a-z0-9_]+)")

        /** `x|y|z` — `location=` 이 받는 모양. */
        fun loc(location: Location): String = "${location.x}|${location.y}|${location.z}"

        fun num(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else String.format(java.util.Locale.ROOT, "%.2f", value)

        /** 편집 화면의 조건 빌더가 보여줄 보기(이름 · 뜻). */
        val CATALOG: List<Pair<String, String>> = listOf(
            "health" to "체력", "max health" to "최대 체력", "health percentage" to "체력 %",
            "food" to "허기", "world" to "월드", "environment" to "차원(NORMAL·NETHER·THE_END)",
            "distance" to "상대와의 거리", "y" to "높이", "is sneaking" to "웅크림",
            "is sprinting" to "달리는 중", "is flying" to "비행 중", "is gliding" to "활공 중",
            "is blocking" to "막는 중", "is on fire" to "불붙음", "is under water" to "물속",
            "is holding" to "든 아이템", "mob type" to "엔티티 종류", "is hostile" to "적대적",
            "name" to "이름", "nearby mobs" to "주변 몹 수", "players" to "주변 플레이어 수",
            "block below" to "발밑 블록", "time" to "월드 시간", "combo" to "연속 타격",
            "light level" to "밝기", "damage" to "피해(방어 후)", "raw damage" to "피해(방어 전)",
            "damage cause" to "피해 원인", "is critical" to "치명타", "is headshot" to "헤드샷",
            "damaged from behind" to "뒤에서 맞음", "block type" to "블록 종류", "is crop" to "작물",
            "is fully grown" to "다 자람", "exp" to "경험치", "caught" to "낚은 것", "level" to "인첸트 레벨",
            "souls on item" to "아이템 영혼", "is removed" to "벗는 중",
            "roll" to "주사위(0~100, 한 번 발동에 하나 — 여러 줄 중 하나 고르기)",
        )
    }
}

/** 칸 → 장비 아이템. 엔진과 변수가 같이 쓴다. */
fun LivingEntity.itemIn(slot: EquipmentSlot): ItemStack? {
    val equipment = equipment ?: return null
    return when (slot) {
        EquipmentSlot.HAND -> equipment.itemInMainHand
        EquipmentSlot.OFF_HAND -> equipment.itemInOffHand
        EquipmentSlot.HEAD -> equipment.helmet
        EquipmentSlot.CHEST -> equipment.chestplate
        EquipmentSlot.LEGS -> equipment.leggings
        EquipmentSlot.FEET -> equipment.boots
        else -> null
    }
}
