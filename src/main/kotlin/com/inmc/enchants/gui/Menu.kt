package com.inmc.enchants.gui

import com.inmc.enchants.Enchants
import kr.inmc.core.gui.Icon
import kr.inmc.core.gui.Paging
import kr.inmc.core.util.Text
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * core [kr.inmc.core.gui.Menu] 에 이 플러그인의 로케이터를 붙인 얇은 층. 리로드가 열린 화면을 닫을 때
 * [owner] 로 우리 것을 가려낸다.
 */
abstract class Menu(
    protected val e: Enchants,
    protected val viewer: Player,
    size: Int,
    title: String,
) : kr.inmc.core.gui.Menu(size, Text.renderFlat(title)) {

    override val owner: Any get() = e

    /** 뒤로 버튼이 여는 화면. null 이면 뒤로 버튼이 없다. */
    protected open val back: (() -> Unit)? = null

    protected fun navigation() {
        back?.let { go -> set(Paging.SLOT_BACK, Icon.back()) { go() } }
        set(Paging.SLOT_CLOSE, Icon.close()) { viewer.closeInventory() }
    }

    fun show() = open(viewer)
}

/**
 * core [kr.inmc.core.gui.PickMenu] 에 이 플러그인의 로케이터만 붙인 것(2026-10-08 — 본체는 core 로 올렸다).
 * 보기 아이콘·여럿 고르기·◀ 뒤로의 뜻은 core 의 것과 같다.
 */
class PickMenu<T>(
    e: Enchants,
    viewer: Player,
    title: String,
    options: List<T>,
    icon: (T) -> ItemStack,
    multi: Boolean = false,
    selected: () -> Set<T> = { emptySet() },
    back: (() -> Unit)?,
    onPick: (T) -> Unit,
) : kr.inmc.core.gui.PickMenu<T>(e, viewer, title, options, icon, multi, selected, back, onPick)
