package com.inmc.enchants.hook

import com.inmc.enchants.Enchants
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.item.ItemRef
import org.bukkit.inventory.ItemStack

/**
 * **만들기 전용** 공급처 `inmcenchant:<인첸트>:<레벨>[:<성공>:<파괴>]` (2026-10-10).
 *
 * 부여서는 커스텀아이템 목록에 없다(인첸트 × 레벨이 너무 많아 일부러 뺐다 — `EnchantRoles.LISTED`). 그래서
 * `inmc:` 참조로는 못 만들고, 이 공급처가 essentials `/지급`·킷·urb 보상에 같은 참조를 준다.
 * 성공·파괴를 적지 않으면 관리 화면의 '아이템 지급' 과 같이 설정대로 굴린다([com.inmc.enchants.item.EnchantItems.randomBook]).
 *
 * **[identify] 는 언제나 null 이다.** 알아보기 시작하면 모든 플러그인의 보상 캡처가 손에 든 부여서를 이 참조로 저장하고
 * 받을 때마다 확률을 다시 굴리게 된다 — 조용한 동작 변화.
 *
 * 네임스페이스가 `enchant` 가 아닌 것은 ItemsAdder 네임스페이스와 겹치지 않게 하려는 것이다(`register` 는 같은 이름을 갈아 끼운다).
 * 서버 금지 인첸트도 만든다 — 관리자 지급이고, 관리 화면의 지급과 같다(규칙 32 는 '새로 나오는 길' 중 뽑기·주민을 막는다).
 */
class BookItemProvider(private val e: Enchants) : CustomItemHook.Provider {

    override val namespace: String get() = NAMESPACE

    /** 만들기 전용 — 찾기·지우기 제안에서 빠지고 "꽂혔나" 에 세지 않는다(core `makeOnly`). */
    override val makeOnly: Boolean get() = true

    override fun identify(stack: ItemStack): String? = null

    override fun create(ref: ItemRef.Namespaced): ItemStack? {
        if (!ref.namespace.equals(NAMESPACE, ignoreCase = true)) return null
        val spec = parse(ref.id) ?: return null
        val def = e.registry.get(spec.enchant) ?: return null
        if (spec.level > def.maxLevel) return null
        val success = spec.success ?: return e.items.randomBook(def, spec.level)
        return e.items.book(def, spec.level, success, spec.destroy ?: 0)
    }

    /**
     * 인첸트마다 **한 줄** `<id>:<최대레벨>` — 레벨을 낮춰 쓰면 된다. 레벨마다 내놓으면 인첸트 370개에 1,000줄이 넘어 `/지급` 제안을 가득 채운다.
     * 성공·파괴를 붙인 꼴은 끝이 없어 목록에 넣지 않는다. 정의를 다 읽기 전에는 빈 목록.
     */
    override fun ids(): Collection<String> = e.registry.all().map { def -> suggestionId(def.id, def.maxLevel) }

    /** 참조 하나. [success]·[destroy] 는 둘 다 있거나 둘 다 없다. */
    data class Spec(val enchant: String, val level: Int, val success: Int?, val destroy: Int?)

    companion object {
        const val NAMESPACE = "inmcenchant"

        /** 제안 목록의 한 줄 — `<id>:<최대레벨>`(최대가 1 미만이면 1). */
        fun suggestionId(enchantId: String, maxLevel: Int): String = enchantId + ":" + maxLevel.coerceAtLeast(1)

        /** `<인첸트>:<레벨>` 또는 `<인첸트>:<레벨>:<성공>:<파괴>`. 레벨은 1 이상, 성공·파괴는 0~100. 그 밖의 꼴은 null. */
        fun parse(id: String): Spec? {
            val parts = id.trim().lowercase().split(':')
            if (parts.size != 2 && parts.size != 4) return null
            val enchant = parts[0].takeIf { it.isNotBlank() } ?: return null
            val level = parts[1].toIntOrNull()?.takeIf { it >= 1 } ?: return null
            if (parts.size == 2) return Spec(enchant, level, null, null)
            val success = parts[2].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            val destroy = parts[3].toIntOrNull()?.takeIf { it in 0..100 } ?: return null
            return Spec(enchant, level, success, destroy)
        }
    }
}
