package com.inmc.enchants.effect.impl

import com.inmc.enchants.effect.EffectExec
import com.inmc.enchants.effect.EffectRun
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.ExperienceOrb
import org.bukkit.entity.Player

/** 블록 부수기·나무 베기·심기·드랍 바꾸기·경험치. */
internal object BlockEffects {

    private val LOG_SUFFIXES = listOf("_LOG", "_WOOD", "_STEM", "_HYPHAE")

    private fun isLog(type: Material): Boolean = LOG_SUFFIXES.any { type.name.endsWith(it) } && !type.name.startsWith("STRIPPED_")

    private fun isLeaves(type: Material): Boolean = type.name.endsWith("_LEAVES") || type.name.endsWith("_WART_BLOCK")

    private data class Crop(val block: Material, val seed: Material, val soil: Material)

    private val CROPS = mapOf(
        "SEEDS" to Crop(Material.WHEAT, Material.WHEAT_SEEDS, Material.FARMLAND),
        "POTATO" to Crop(Material.POTATOES, Material.POTATO, Material.FARMLAND),
        "CARROT" to Crop(Material.CARROTS, Material.CARROT, Material.FARMLAND),
        "BEETROOT" to Crop(Material.BEETROOTS, Material.BEETROOT_SEEDS, Material.FARMLAND),
        "MELON" to Crop(Material.MELON_STEM, Material.MELON_SEEDS, Material.FARMLAND),
        "PUMPKIN" to Crop(Material.PUMPKIN_STEM, Material.PUMPKIN_SEEDS, Material.FARMLAND),
        "NETHER_WART" to Crop(Material.NETHER_WART, Material.NETHER_WART, Material.SOUL_SAND),
    )

    fun register(map: MutableMap<String, EffectExec>) {
        map["BREAK_BLOCK"] = EffectExec { run ->
            val block = run.block ?: return@EffectExec
            if (block == run.ctx.block) return@EffectExec
            // 개인 설정 "광역 채굴"을 끈 사람 — 캔 블록 하나만(core PlayerSettings).
            if (!allowedBy(run, com.inmc.enchants.EnchantSettings.AREA_MINING)) return@EffectExec
            if (!run.ctx.settings.allowsMaterial(block.type.name)) return@EffectExec
            if (!sameToolFamily(run, block)) return@EffectExec
            breakOne(run, block, dropNonOres = false)
        }
        map["BREAK_TREE"] = EffectExec { run ->
            val start = run.block ?: return@EffectExec
            if (!isLog(start.type)) return@EffectExec
            if (!allowedBy(run, com.inmc.enchants.EnchantSettings.TREE_FELLING)) return@EffectExec
            val maxLogs = run.int(0).coerceIn(1, 512)
            val maxLeaves = run.int(1).coerceIn(0, 512)
            val logs = flood(start, maxLogs) { isLog(it) }
            for (block in logs) if (block != run.ctx.block) breakOne(run, block)
            if (maxLeaves > 0) {
                var broken = 0
                for (log in logs) for (dx in -2..2) for (dy in -1..2) for (dz in -2..2) {
                    if (broken >= maxLeaves) return@EffectExec
                    val leaf = log.getRelative(dx, dy, dz)
                    if (isLeaves(leaf.type) && breakOne(run, leaf)) broken++
                }
            }
        }
        map["SET_BLOCK"] = EffectExec { run ->
            val block = run.block ?: return@EffectExec
            val material = Material.matchMaterial(run.arg(0)) ?: return@EffectExec
            if (!material.isBlock || block.type.hardness < 0) return@EffectExec
            block.type = material
        }
        map["PLANT_SEEDS"] = EffectExec { run ->
            val center = run.block ?: run.ctx.self.location.block
            val radius = run.int(0).coerceIn(0, 7)
            // AUTO = 캔 작물 그대로(다시 심기). 캔 순간의 종류를 본다 — 블록은 이미 공기다.
            val crop = if (run.arg(1).equals("AUTO", ignoreCase = true)) {
                CROPS.values.firstOrNull { it.block == run.ctx.blockType } ?: return@EffectExec
            } else {
                CROPS[run.arg(1).uppercase()] ?: CROPS.getValue("SEEDS")
            }
            val player = run.ctx.self as? Player
            for (dx in -radius..radius) for (dz in -radius..radius) for (dy in -1..1) {
                val soil = center.getRelative(dx, dy, dz)
                val above = soil.getRelative(0, 1, 0)
                if (soil.type != crop.soil || !above.type.isAir) continue
                // 가방의 씨앗을 쓴다. 공짜로 심으면 씨앗 복제가 된다. 창작 모드는 예외.
                if (player != null && player.gameMode.name != "CREATIVE") {
                    if (!player.inventory.containsAtLeast(org.bukkit.inventory.ItemStack(crop.seed), 1)) return@EffectExec
                    player.inventory.removeItem(org.bukkit.inventory.ItemStack(crop.seed, 1))
                }
                above.type = crop.block
            }
        }
        map["SMELT"] = EffectExec { run -> run.ctx.smeltDrops = true }
        map["MORE_DROPS"] = EffectExec { run ->
            // 채굴이면 whitelist 로 재질을 거른다 — 안 거르면 다이아 블록도 몇 배가 된다(AE 경고와 같다).
            val block = run.ctx.block
            if (block != null && !run.ctx.settings.allowsMaterial(block.type.name)) return@EffectExec
            run.ctx.dropMultiplier *= run.num(0).coerceIn(0.0, 64.0)
        }
        map["TP_DROPS"] = EffectExec { run -> run.ctx.teleportDrops = true }
        map["EXP"] = EffectExec { run ->
            val amount = run.int(0)
            if (amount <= 0) return@EffectExec
            val location = run.location
            location.world.spawn(location, ExperienceOrb::class.java) { it.experience = amount }
        }
    }

    /** 플레이어가 그 개인 설정을 켜 두었나. 플레이어가 아닌 쪽(몹이 쓴 효과)은 늘 그대로. */
    private fun allowedBy(run: EffectRun, key: String): Boolean {
        val player = run.ctx.self as? Player ?: return true
        return kr.inmc.core.integration.PlayerSettings.enabled(player, key)
    }

    private fun breakOne(run: EffectRun, block: Block, dropNonOres: Boolean = true): Boolean {
        val player = run.ctx.self as? Player
        val tool = run.ctx.self.equipment?.itemInMainHand
        // 광역 채굴로 딸려 깨지는 블록은 드랍 없이 사라진다. 직접 캔 것은 이 길을 타지 않아(바닐라가 부순다)
        // 그대로 나온다. 광물(광석)은 예외 — 캐다 만 광산이 텅 비면 안 되므로 그대로 떨군다.
        // 광맥 채굴(Veinmine)은 광석이 본체라 전부 둔다. 통베기는 나무가 본체라 건드리지 않는다.
        // 개인 설정 OFF 는 위에서 이미 걸렀다.
        // `drops` 인자(true)가 있으면 딸린 블록도 바닐라처럼 떨군다 — 3x3(깨기)과 무드롭이 따로 논다.
        val vein = keepsDrops(run.line.targets.map { it.kind })
        val collect = vein || dropNonOres || run.bool(0) || isOre(block.type)
        val broken = run.enchants.support.breakBlock(player, block, tool, run.ctx, collectDrops = collect)
        if (broken && player != null && run.enchants.config.breakBlockDamagesTool) {
            damageTool(player)
        }
        return broken
    }

    /** 삽으로 돌을 트렌치하지 않게 한다. `{ignoretool=true}` 면 건너뛴다. */
    private fun sameToolFamily(run: EffectRun, block: Block): Boolean {
        if (run.line.targets.any { it.bool("ignoretool", false) }) return true
        val tool = run.ctx.self.equipment?.itemInMainHand ?: return true
        if (tool.type.isAir) return true
        return block.isPreferredTool(tool)
    }

    private fun damageTool(player: Player) {
        val tool = player.inventory.itemInMainHand
        if (tool.type.maxDurability <= 0) return
        player.inventory.setItemInMainHand(tool.damage(1, player))
    }

    /**
     * 광맥 채굴이면 드랍을 전부 두는가 — 광석이 본체라 버리면 안 된다. 서버 없이 돈다.
     */
    internal fun keepsDrops(kinds: List<com.inmc.enchants.engine.TargetKind>): Boolean =
        kinds.any { it == com.inmc.enchants.engine.TargetKind.VEINMINE }

    /** 광물(광석)인가 — `_ORE` 로 끝나는 것과 고대 잔해. 서버 없이 돈다. */
    internal fun isOre(type: Material): Boolean =
        type.name.endsWith("_ORE") || type == Material.ANCIENT_DEBRIS

    private fun flood(start: Block, limit: Int, accept: (Material) -> Boolean): List<Block> {
        val seen = HashSet<Block>()
        val queue = ArrayDeque<Block>()
        queue.add(start)
        seen.add(start)
        val out = ArrayList<Block>()
        while (queue.isNotEmpty() && out.size < limit) {
            val current = queue.removeFirst()
            out += current
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val next = current.getRelative(dx, dy, dz)
                if (!accept(next.type) || !seen.add(next)) continue
                queue.add(next)
            }
        }
        return out
    }
}
