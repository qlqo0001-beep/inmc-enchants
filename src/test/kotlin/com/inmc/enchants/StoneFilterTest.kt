package com.inmc.enchants

import com.inmc.enchants.effect.impl.BlockEffects
import com.inmc.enchants.engine.TargetKind
import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 광역 채굴 돌·조약돌 규칙 — 직접 캔 것은 그대로, 딸려 깨지는 것은 버린다.
 * 광맥 채굴(Veinmine)은 광석이 본체라 전부 둔다. 서버 없이 돈다.
 */
class StoneFilterTest {

    @Test
    fun `광맥이면 돌 드랍을 둔다`() {
        assertTrue(BlockEffects.keepsStoneDrops(listOf(TargetKind.VEINMINE)))
        assertTrue(BlockEffects.keepsStoneDrops(listOf(TargetKind.BLOCK, TargetKind.VEINMINE)))
    }

    @Test
    fun `트렌치·터널이면 돌 드랍을 버린다`() {
        assertFalse(BlockEffects.keepsStoneDrops(listOf(TargetKind.TRENCH)))
        assertFalse(BlockEffects.keepsStoneDrops(listOf(TargetKind.TUNNEL)))
        assertFalse(BlockEffects.keepsStoneDrops(listOf(TargetKind.BLOCK)))
        assertFalse(BlockEffects.keepsStoneDrops(emptyList()))
    }

    @Test
    fun `돌 계열 네 가지가 대상이다`() {
        assertTrue(BlockEffects.PLAIN_STONE_DROPS.contains(Material.STONE))
        assertTrue(BlockEffects.PLAIN_STONE_DROPS.contains(Material.COBBLESTONE))
        assertTrue(BlockEffects.PLAIN_STONE_DROPS.contains(Material.DEEPSLATE))
        assertTrue(BlockEffects.PLAIN_STONE_DROPS.contains(Material.COBBLED_DEEPSLATE))
        assertFalse(BlockEffects.PLAIN_STONE_DROPS.contains(Material.DIAMOND_ORE))
        assertFalse(BlockEffects.PLAIN_STONE_DROPS.contains(Material.DIRT))
    }
}
