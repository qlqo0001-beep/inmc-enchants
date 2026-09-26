package com.inmc.enchants.item

import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType

/**
 * 아이템에 붙은 인첸트를 읽고 쓴다. 로어는 [LoreRenderer] 가 따로 그린다.
 *
 * **읽기는 뜨거운 경로다.** 공격·피격·채굴마다 손과 방어구 다섯 개를 읽는다. 메타가 없으면
 * PDC 를 열지 않고 곧바로 빈 목록을 준다.
 */
object EnchantStorage {

    fun read(stack: ItemStack?): LinkedHashMap<String, Int> {
        if (stack == null || stack.type.isAir || !stack.hasItemMeta()) return LinkedHashMap()
        val raw = stack.itemMeta.persistentDataContainer.get(Keys.ENCHANTS, PersistentDataType.STRING)
            ?: return LinkedHashMap()
        return decode(raw)
    }

    fun has(stack: ItemStack?): Boolean {
        if (stack == null || stack.type.isAir || !stack.hasItemMeta()) return false
        return stack.itemMeta.persistentDataContainer.has(Keys.ENCHANTS, PersistentDataType.STRING)
    }

    fun level(stack: ItemStack?, id: String): Int = read(stack)[id] ?: 0

    /** 통째로 바꾼다. 빈 목록이면 키를 지운다. **로어는 건드리지 않는다.** */
    fun write(stack: ItemStack, enchants: Map<String, Int>) {
        stack.editMeta { meta ->
            val pdc = meta.persistentDataContainer
            if (enchants.isEmpty()) pdc.remove(Keys.ENCHANTS) else pdc.set(Keys.ENCHANTS, PersistentDataType.STRING, encode(enchants))
        }
    }

    fun encode(enchants: Map<String, Int>): String =
        enchants.entries.joinToString(";") { it.key + ":" + it.value }

    /** 망가진 조각은 건너뛴다. 한 조각 때문에 아이템의 인첸트 전부를 잃으면 안 된다. */
    fun decode(raw: String): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (part in raw.split(';')) {
            val id = part.substringBefore(':').trim().lowercase()
            val level = part.substringAfter(':', "").trim().toIntOrNull() ?: continue
            if (id.isNotEmpty() && level > 0) out[id] = level
        }
        return out
    }

    // --- 정수 값 몇 개 --------------------------------------------------------------------

    fun int(stack: ItemStack?, key: org.bukkit.NamespacedKey): Int {
        if (stack == null || stack.type.isAir || !stack.hasItemMeta()) return 0
        return stack.itemMeta.persistentDataContainer.get(key, PersistentDataType.INTEGER) ?: 0
    }

    fun flag(stack: ItemStack?, key: org.bukkit.NamespacedKey): Boolean {
        if (stack == null || stack.type.isAir || !stack.hasItemMeta()) return false
        return stack.itemMeta.persistentDataContainer.has(key)
    }

    fun setInt(stack: ItemStack, key: org.bukkit.NamespacedKey, value: Int?) {
        stack.editMeta { meta ->
            if (value == null) meta.persistentDataContainer.remove(key)
            else meta.persistentDataContainer.set(key, PersistentDataType.INTEGER, value)
        }
    }

    fun setFlag(stack: ItemStack, key: org.bukkit.NamespacedKey, on: Boolean) {
        stack.editMeta { meta ->
            if (on) meta.persistentDataContainer.set(key, PersistentDataType.BYTE, 1)
            else meta.persistentDataContainer.remove(key)
        }
    }
}
