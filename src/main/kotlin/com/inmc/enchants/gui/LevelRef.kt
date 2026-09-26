package com.inmc.enchants.gui

import com.inmc.enchants.Enchants
import com.inmc.enchants.enchant.EnchantLevel
import org.bukkit.entity.Player

/**
 * 편집할 레벨 하나. 인첸트의 한 레벨이거나 세트·무기의 발동 조건 하나다 — 둘 다 확률·대기·조건·효과 줄을
 * 가진 [EnchantLevel] 이라, 레벨·효과·조건 편집 화면이 이것만 알고 둘을 다 고친다.
 *
 * 정의가 불변이라 값을 들고 있지 않고 **찾아가는 길**을 들고 있다. 고칠 때마다 새 정의가 되므로
 * 옛 값을 붙들면 두 번째 편집이 첫 번째를 지운다.
 */
interface LevelRef {
    /** 화면 제목. 예: `lifesteal - 2레벨`. */
    val title: String

    /** 인첸트의 레벨이면 true. 레벨 설명·연동 값은 인첸트에만 뜻이 있다. */
    val enchant: Boolean

    fun get(): EnchantLevel?

    fun set(level: EnchantLevel)

    /** 레벨 편집 화면의 뒤로. */
    fun back(viewer: Player)
}

class EnchantLevelRef(private val e: Enchants, private val id: String, private val number: Int) : LevelRef {

    override val title = "$id - ${number}레벨"

    override val enchant = true

    override fun get(): EnchantLevel? = e.registry.get(id)?.levels?.get(number)

    override fun set(level: EnchantLevel) {
        val d = e.registry.get(id) ?: return
        if (number !in d.levels) return
        e.registry.put(d.copy(levels = d.levels + (number to level)))
    }

    override fun back(viewer: Player) = LevelListMenu(e, viewer, id).show()
}
