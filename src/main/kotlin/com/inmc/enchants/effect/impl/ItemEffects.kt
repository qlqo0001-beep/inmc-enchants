package com.inmc.enchants.effect.impl

import com.inmc.enchants.effect.EffectExec
import com.inmc.enchants.effect.EffectRun
import kr.inmc.core.item.ItemRef
import kr.inmc.core.item.StoredItem
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable

/** 내구도·무장해제·머리·아이템 주고받기·호박·방어구·낚시 보조. */
internal object ItemEffects {

    /** 내구도를 바꾼다(+ 수리, - 손상). 다 닳으면 부서진다. 바뀐 아이템을 돌려준다(부서졌으면 null). */
    private fun adjust(stack: ItemStack?, amount: Int): ItemStack? {
        if (stack == null || stack.type.isAir) return stack
        val max = stack.type.maxDurability.toInt()
        if (max <= 0) return stack
        val meta = stack.itemMeta as? Damageable ?: return stack
        if (meta.isUnbreakable && amount < 0) return stack
        val next = meta.damage - amount
        if (next >= max) return null
        meta.damage = next.coerceIn(0, max - 1)
        stack.itemMeta = meta
        return stack
    }

    private fun setSlot(entity: LivingEntity, slot: EquipmentSlot?, stack: ItemStack?) {
        if (slot == null) return
        entity.equipment?.setItem(slot, stack ?: ItemStack(Material.AIR))
    }

    /** 플레이어면 가방에, 넘치면 발밑에. 그 밖이면 발밑에. */
    private fun give(target: LivingEntity, stack: ItemStack) {
        if (target is Player) {
            for (left in target.inventory.addItem(stack).values) target.world.dropItemNaturally(target.location, left)
        } else {
            target.world.dropItemNaturally(target.location, stack)
        }
    }

    /** `DIAMOND` · `inmc:장검` · `mmoitems:SWORD:KATANA` — core 의 참조 문법 그대로. */
    private fun create(run: EffectRun, raw: String, amount: Int): ItemStack? {
        val ref = ItemRef.parse(raw.trim())
        if (ref is ItemRef.None) return null
        val material = (ref as? ItemRef.Vanilla)?.material ?: Material.PAPER
        return run.enchants.itemResolver.create(StoredItem(ref, material), amount.coerceIn(1, 64 * 36))
    }

    fun register(map: MutableMap<String, EffectExec>) {
        map["ADD_DURABILITY_CURRENT_ITEM"] = EffectExec { run ->
            val holder = run.ctx.self
            val result = adjust(run.ctx.item, run.int(0))
            setSlot(holder, run.ctx.slot, result)
            if (result == null) holder.world.playSound(holder.location, org.bukkit.Sound.ENTITY_ITEM_BREAK, 1f, 1f)
        }
        map["ADD_DURABILITY_ARMOR"] = EffectExec { run -> armor(run, run.int(0), mostDamaged = run.arg(1).equals("MOST_DAMAGED", ignoreCase = true)) }
        map["DAMAGE_ARMOR"] = EffectExec { run -> armor(run, -run.int(0)) }
        map["ADD_DURABILITY_ITEM"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            val slot = run.int(0)
            if (slot !in 0 until player.inventory.size) return@EffectExec
            player.inventory.setItem(slot, adjust(player.inventory.getItem(slot), run.int(1)))
        }
        map["REPAIR"] = EffectExec { run ->
            val stack = run.ctx.item ?: return@EffectExec
            val meta = stack.itemMeta as? Damageable ?: return@EffectExec
            meta.damage = 0
            stack.itemMeta = meta
            setSlot(run.ctx.self, run.ctx.slot, stack)
        }
        map["DISARM"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            val equipment = target.equipment ?: return@EffectExec
            val held = equipment.itemInMainHand
            if (held.type.isAir) return@EffectExec
            equipment.setItemInMainHand(ItemStack(Material.AIR))
            if (target is Player) {
                // 단축바 밖 빈칸으로. 없으면 발밑에. 아이템을 없애면 안 된다.
                val free = (9 until 36).firstOrNull { target.inventory.getItem(it) == null }
                if (free != null) target.inventory.setItem(free, held) else target.world.dropItemNaturally(target.location, held)
            } else {
                target.world.dropItemNaturally(target.location, held)
            }
        }
        map["DROP_HEAD"] = EffectExec { run ->
            val target = run.entity ?: return@EffectExec
            val head = run.enchants.heads.of(target) ?: return@EffectExec
            target.world.dropItemNaturally(target.location, head)
        }
        map["DROP_HELD_ITEM"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            val equipment = target.equipment ?: return@EffectExec
            val held = equipment.itemInMainHand
            if (held.type.isAir) return@EffectExec
            equipment.setItemInMainHand(ItemStack(Material.AIR))
            target.world.dropItemNaturally(target.location, held)
        }
        map["GIVE_ITEM"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            val stack = create(run, run.arg(0), run.int(1).coerceAtLeast(1)) ?: return@EffectExec
            for (part in run.enchants.drops.split(stack)) give(target, part)
        }
        map["DROP_ITEM"] = EffectExec { run ->
            val stack = create(run, run.arg(0), run.int(1).coerceAtLeast(1)) ?: return@EffectExec
            val location = run.location
            for (part in run.enchants.drops.split(stack)) location.world.dropItemNaturally(location, part)
        }
        map["TAKE_AWAY"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            val material = Material.matchMaterial(run.arg(0)) ?: return@EffectExec
            player.inventory.removeItem(ItemStack(material, run.int(1).coerceAtLeast(1)))
        }
        map["DELETE_ITEM"] = EffectExec { run ->
            val stack = run.ctx.item ?: return@EffectExec
            val left = stack.amount - run.int(0).coerceAtLeast(1)
            if (left <= 0) setSlot(run.ctx.self, run.ctx.slot, null) else {
                stack.amount = left
                setSlot(run.ctx.self, run.ctx.slot, stack)
            }
        }
        map["PUMPKIN"] = EffectExec { run -> run.living?.let { run.enchants.support.pumpkin(it, run.int(0)) } }
        map["REMOVE_ARMOR"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            val slot = when (run.arg(0).uppercase()) {
                "HELMET" -> EquipmentSlot.HEAD
                "CHESTPLATE" -> EquipmentSlot.CHEST
                "LEGGINGS" -> EquipmentSlot.LEGS
                "BOOTS" -> EquipmentSlot.FEET
                else -> return@EffectExec
            }
            strip(target, slot)
        }
        map["REMOVE_RANDOM_ARMOR"] = EffectExec { run ->
            val target = run.living ?: return@EffectExec
            val equipment = target.equipment ?: return@EffectExec
            val worn = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
                .filter { !equipment.getItem(it).type.isAir }
            if (worn.isEmpty()) return@EffectExec
            strip(target, worn.random())
        }
        map["SHUFFLE_HOTBAR"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            val hotbar = (0..8).map { player.inventory.getItem(it) }.shuffled()
            for ((slot, stack) in hotbar.withIndex()) player.inventory.setItem(slot, stack)
        }
        map["CANCEL_USE"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            val material = Material.matchMaterial(run.arg(0)) ?: return@EffectExec
            player.setCooldown(material, run.int(1).coerceAtLeast(1))
        }
        map["OPEN_CRAFTING_TABLE"] = EffectExec { run ->
            @Suppress("DEPRECATION")
            run.player?.openWorkbench(null, true)
        }
        map["OPEN_ENDERCHEST"] = EffectExec { run ->
            val player = run.player ?: return@EffectExec
            player.openInventory(player.enderChest)
        }
        map["AUTO_REEL"] = EffectExec { run ->
            val event = run.ctx.event as? PlayerFishEvent ?: return@EffectExec
            val hook = event.hook
            // 입질 틱에 바로 감으면 바닐라가 아직 걸린 것을 모른다. 한 틱 뒤에.
            run.enchants.support.later(1L) { if (hook.isValid) runCatching { hook.retrieve(EquipmentSlot.HAND) } }
        }
        map["SET_MAX_CATCH_TIME"] = EffectExec { run ->
            val hook = (run.ctx.event as? PlayerFishEvent)?.hook ?: return@EffectExec
            val ticks = run.int(0).coerceAtLeast(1)
            if (hook.minWaitTime > ticks) hook.minWaitTime = ticks
            hook.maxWaitTime = ticks
        }
        map["SET_MIN_CATCH_TIME"] = EffectExec { run ->
            val hook = (run.ctx.event as? PlayerFishEvent)?.hook ?: return@EffectExec
            val ticks = run.int(0).coerceAtLeast(0)
            if (hook.maxWaitTime < ticks) hook.maxWaitTime = ticks
            hook.minWaitTime = ticks
        }
    }

    /** 입은 방어구 전부, [mostDamaged] 면 가장 많이 닳은 하나만. */
    private fun armor(run: EffectRun, amount: Int, mostDamaged: Boolean = false) {
        val target = run.living ?: return
        val equipment = target.equipment ?: return
        val slots = listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)
        val chosen = if (!mostDamaged) slots else listOfNotNull(slots.filter { !equipment.getItem(it).type.isAir }
            .maxByOrNull { (equipment.getItem(it).itemMeta as? org.bukkit.inventory.meta.Damageable)?.damage ?: 0 })
        for (slot in chosen) {
            val stack = equipment.getItem(slot)
            if (stack.type.isAir) continue
            equipment.setItem(slot, adjust(stack, amount) ?: ItemStack(Material.AIR))
        }
    }

    /** 방어구를 벗긴다. 플레이어는 가방으로(없으면 발밑), 몹은 떨군다. */
    private fun strip(target: LivingEntity, slot: EquipmentSlot) {
        val equipment = target.equipment ?: return
        val stack = equipment.getItem(slot)
        if (stack.type.isAir) return
        equipment.setItem(slot, ItemStack(Material.AIR))
        give(target, stack)
    }
}
