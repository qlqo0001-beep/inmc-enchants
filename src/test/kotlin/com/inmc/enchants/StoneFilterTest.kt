package com.inmc.enchants

import com.inmc.enchants.effect.impl.BlockEffects
import com.inmc.enchants.engine.TargetKind
import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 광역 채굴 드랍 규칙 — 딸려 깨지는 비광석은 드랍 없이 사라지고, 광석은 나온다.
 * 직접 캔 것은 바닐라가 부숴 그대로. 광맥 채굴(Veinmine)은 전부 둔다. 서버 없이 돈다.
 */
class StoneFilterTest {

    @Test
    fun `광맥이면 드랍을 둔다`() {
        assertTrue(BlockEffects.keepsDrops(listOf(TargetKind.VEINMINE)))
        assertTrue(BlockEffects.keepsDrops(listOf(TargetKind.BLOCK, TargetKind.VEINMINE)))
    }

    @Test
    fun `트렌치·터널이면 광석만 둔다`() {
        assertFalse(BlockEffects.keepsDrops(listOf(TargetKind.TRENCH)))
        assertFalse(BlockEffects.keepsDrops(listOf(TargetKind.TUNNEL)))
        assertFalse(BlockEffects.keepsDrops(listOf(TargetKind.BLOCK)))
        assertFalse(BlockEffects.keepsDrops(emptyList()))
    }

    @Test
    fun `광석을 알아본다`() {
        assertTrue(BlockEffects.isOre(Material.DIAMOND_ORE))
        assertTrue(BlockEffects.isOre(Material.DEEPSLATE_IRON_ORE))
        assertTrue(BlockEffects.isOre(Material.NETHER_GOLD_ORE))
        assertTrue(BlockEffects.isOre(Material.ANCIENT_DEBRIS))
        assertFalse(BlockEffects.isOre(Material.STONE))
        assertFalse(BlockEffects.isOre(Material.COBBLESTONE))
        assertFalse(BlockEffects.isOre(Material.DIRT))
        assertFalse(BlockEffects.isOre(Material.OAK_LOG))
    }
}
