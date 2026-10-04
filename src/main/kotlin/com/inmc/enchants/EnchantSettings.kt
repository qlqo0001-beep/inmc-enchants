package com.inmc.enchants

import kr.inmc.core.integration.PlayerSettings
import org.bukkit.Material

/**
 * 인첸트가 core 개인 설정 창구([PlayerSettings])에 올리는 것 — 플레이어 메뉴의 개인 설정 화면에 보인다(2026-10-02).
 * 끈 사람에게만 효과를 건너뛴다. 정의가 없을 때(core 가 옛 판) 기본은 켜짐이라 지금과 같다.
 */
internal object EnchantSettings {

    const val OWNER = "커스텀 인첸트"

    /** `BREAK_BLOCK` — 트렌치·터널·광맥으로 캔 블록 둘레까지. */
    const val AREA_MINING = "enchants.area-mining"

    /** `BREAK_TREE` — 나무 통째 베기. */
    const val TREE_FELLING = "enchants.tree-felling"

    /** 광역 채굴로 딸린 블록의 드랍을 받을지. 끄면 광석 포함 아무것도 안 나온다(직접 캔 것은 그대로). */
    const val AREA_DROPS = "enchants.area-drops"

    fun register() {
        PlayerSettings.register(
            PlayerSettings.Setting(
                AREA_MINING, OWNER, "광역 채굴(3x3 등)", Material.IRON_PICKAXE,
                listOf("트렌치·터널·광맥 인첸트가 둘레 블록까지 캡니다.", "끄면 캔 블록 하나만 — 꾸미거나 섬세하게 팔 때."),
                PlayerSettings.Toggle(true),
            ),
        )
        PlayerSettings.register(
            PlayerSettings.Setting(
                TREE_FELLING, OWNER, "나무 통째 베기", Material.IRON_AXE,
                listOf("벌목 인첸트가 나무 한 그루를 통째로 벱니다.", "끄면 캔 통나무 하나만."),
                PlayerSettings.Toggle(true),
            ),
        )
        PlayerSettings.register(
            PlayerSettings.Setting(
                AREA_DROPS, OWNER, "광역 채굴 드랍 받기", Material.COBBLESTONE,
                listOf("켜면 광역 채굴로 딸려 깨지는 블록의 드랍이 나옵니다.", "끄면 광석 포함 아무것도 안 나옵니다(직접 캔 것·광맥 채굴은 그대로)."),
                PlayerSettings.Toggle(true),
            ),
        )
    }

    fun unregister() = PlayerSettings.unregisterAll(OWNER)
}
