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
 * 여러 보기 중 하나(또는 여럿)를 고르는 화면. 물약·입자·인첸트·발동 조건·등급 고르기가 전부 이것이다.
 *
 * @param icon 보기 하나를 그린 아이콘.
 * @param selected 여럿 고르기면 켜진 것. 한 개 고르기면 비워 둔다.
 * @param onPick 누른 보기. 여럿 고르기면 누를 때마다 불리고 화면은 그대로다.
 */
class PickMenu<T>(
    e: Enchants,
    viewer: Player,
    title: String,
    private val options: List<T>,
    private val icon: (T) -> ItemStack,
    private val multi: Boolean = false,
    private val selected: () -> Set<T> = { emptySet() },
    override val back: (() -> Unit)?,
    private val onPick: (T) -> Unit,
) : Menu(e, viewer, 54, title) {

    private var page = 0

    override fun draw() {
        clear()
        page = Paging.clamp(page, options.size)
        val chosen = selected()
        for ((slot, option) in Paging.slice(options, page).withIndex()) {
            val base = icon(option)
            val shown = if (!multi) base else Icon.annotate(
                base.clone().also { if (option in chosen) it.editMeta { meta -> meta.setEnchantmentGlintOverride(true) } },
                lore = listOf("", if (option in chosen) "<green>▶ 켜짐 - 클릭해서 끄기</green>" else "<gray>▶ 꺼짐 - 클릭해서 켜기</gray>"),
            )
            set(slot, shown) {
                onPick(option)
                if (multi) refresh()
            }
        }
        fillEmpty(Icon.FILLER)
        if (page > 0) set(Paging.SLOT_PREV, Icon.prevPage()) { page--; refresh() }
        if (page < Paging.pageCount(options.size) - 1) set(Paging.SLOT_NEXT, Icon.nextPage()) { page++; refresh() }
        navigation()
    }
}
