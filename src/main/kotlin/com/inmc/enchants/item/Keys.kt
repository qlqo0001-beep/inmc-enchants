package com.inmc.enchants.item

import org.bukkit.NamespacedKey

/**
 * 아이템에 적는 PDC 키 전부.
 *
 * **네임스페이스는 `inmc_enchant` 로 고정이다.** `NamespacedKey(plugin, …)` 를 쓰면 플러그인 이름이
 * 바뀌는 순간 서버에 돌아다니는 인첸트 아이템이 전부 평범한 아이템이 된다(`ARCHITECTURE.md`
 * "PDC 네임스페이스"). 키 이름도 바꾸면 안 된다.
 */
object Keys {

    const val NAMESPACE = "inmc_enchant"

    private fun key(name: String) = NamespacedKey(NAMESPACE, name)

    // --- 인첸트가 붙은 아이템 ------------------------------------------------------------
    /** `id:레벨;id:레벨` — 붙은 순서. */
    val ENCHANTS = key("enchants")
    /** 우리가 로어 맨 위에 쓴 줄 수. 다시 그릴 때 이만큼 지우고 쓴다. */
    val LORE_TOP = key("lore_top")
    /** 우리가 로어 맨 아래에 쓴 줄 수(추적기·보호 표시). */
    val LORE_BOTTOM = key("lore_bottom")
    /** 슬롯 확장기·오브로 늘어난 칸. */
    val EXTRA_SLOTS = key("extra_slots")
    /** 오브가 정한 칸 수(오브는 "늘리기"가 아니라 "이 수로"). */
    val ORB_SLOTS = key("orb_slots")
    val SOULS = key("souls")
    val SOUL_TRACKER = key("soul_tracker")
    val WHITE_SCROLL = key("white_scroll")
    val STAT_TRAK = key("stattrak")
    val MOB_TRAK = key("mobtrak")
    val BLOCK_TRAK = key("blocktrak")
    val FISH_TRAK = key("fishtrak")
    val TRANSMOG = key("transmog")
    /** 이름표로 바꾼 이름. 정렬 스크롤이 개수를 덧붙일 때 원래 이름을 알아야 한다. */
    val CUSTOM_NAME = key("custom_name")

    // --- 플러그인 아이템 -----------------------------------------------------------------
    /** 이 아이템이 무엇인가([PluginItemType.id]). 없으면 플러그인 아이템이 아니다. */
    val ITEM_TYPE = key("item")
    val BOOK_ENCHANT = key("book_enchant")
    val BOOK_LEVEL = key("book_level")
    val BOOK_SUCCESS = key("book_success")
    val BOOK_DESTROY = key("book_destroy")
    /** 가루·확장기·미공개 부여서가 속한 그룹. */
    val GROUP = key("group")
    /** 마법 가루의 성공률 증가 · 오브·확장기의 칸 수 · 영혼 보석의 영혼 수. */
    val AMOUNT = key("amount")
    /** 오브의 종류(weapon·armor·tool). */
    val ORB_KIND = key("orb_kind")
    /** 같은 아이템끼리 겹쳐지지 않게 하는 값. 부여서는 한 장씩 따로 굴려야 한다. */
    val UNIQUE = key("unique")
}
