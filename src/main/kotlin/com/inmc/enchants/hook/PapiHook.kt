package com.inmc.enchants.hook

import com.inmc.enchants.Enchants
import com.inmc.enchants.item.EnchantStorage
import kr.inmc.core.util.Durations
import kr.inmc.core.util.Text
import me.clip.placeholderapi.PlaceholderAPI
import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player

/**
 * PlaceholderAPI, 양쪽.
 *
 * 들어오는 쪽: 인첸트 조건의 `%player_level%` 같은 남의 변수를 풀어 준다([Enchants.papi]).
 * 나가는 쪽: `%inmcenchant_…%` — 손에 든 것의 영혼·칸, 입은 세트, 킷 상태.
 *
 * PlaceholderAPI 클래스는 compileOnly 라 **플러그인이 있을 때만** 건드린다. [setup] 이 유일한 문이다.
 */
class PapiHook(private val e: Enchants) {

    private var expansion: EnchantExpansion? = null

    fun setup() {
        teardown()
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            e.logger.info("PlaceholderAPI 미설치 - 조건의 %...% 변수는 풀리지 않습니다")
            return
        }
        try {
            e.papi = { player, text -> PlaceholderAPI.setPlaceholders(player, text) }
            expansion = EnchantExpansion(e).also { it.register() }
            e.logger.info("PlaceholderAPI 연동 활성화 (%inmcenchant_...%)")
        } catch (t: Throwable) {
            e.logger.warning("PlaceholderAPI 연동 실패: ${t.message}")
            teardown()
        }
    }

    fun teardown() {
        expansion?.let { runCatching { it.unregister() } }
        expansion = null
        e.papi = null
    }
}

/** 따로 둔 것은 PlaceholderAPI 가 있을 때만 이 클래스가 적재되게 하려는 것이다. */
private class EnchantExpansion(private val e: Enchants) : PlaceholderExpansion() {

    override fun getIdentifier(): String = "inmcenchant"

    override fun getAuthor(): String = "INMC"

    override fun getVersion(): String = e.plugin.pluginMeta.version

    override fun persist(): Boolean = true

    override fun onRequest(player: OfflinePlayer?, params: String): String? {
        if (params.equals("enchant_count", true)) return e.registry.size.toString()
        val online = player as? Player ?: player?.uniqueId?.let(Bukkit::getPlayer) ?: return ""
        val held = online.inventory.itemInMainHand
        return when {
            params.equals("souls", true) -> e.souls.souls(held).toString()
            params.equals("enchants", true) -> EnchantStorage.read(held).size.toString()
            params.equals("slots_max", true) -> if (held.type.isAir) "0" else e.slots.max(held, online).toString()
            params.equals("set", true) -> e.setService.worn(online)?.let { Text.plain(Text.render(it.name)) }.orEmpty()
            params.equals("set_id", true) -> e.setService.worn(online)?.set.orEmpty()
            else -> null
        }
    }
}
