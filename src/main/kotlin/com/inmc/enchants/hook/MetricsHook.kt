package com.inmc.enchants.hook

import com.inmc.enchants.Enchants
import org.bstats.bukkit.Metrics
import org.bstats.charts.SimplePie

/**
 * bStats 집계.
 *
 * **개인 식별 값은 보내지 않는다.** 인첸트·세트·킷 이름도 보내지 않는다 — 서버가 만든 콘텐츠다.
 * 개수만 구간으로 뭉쳐 센다.
 *
 * 차트 콜백은 **비메인 스레드에서 돈다.** Bukkit API 를 건드리면 안 된다.
 */
class MetricsHook(private val e: Enchants) {

    private var metrics: Metrics? = null

    fun start() {
        val instance = Metrics(e.plugin, PLUGIN_ID)
        instance.addCustomChart(SimplePie("enchant_count") { bucket(e.registry.size) })
        instance.addCustomChart(SimplePie("vault") { if (e.economy.isEnabled) "on" else "off" })
        metrics = instance
    }

    fun stop() {
        metrics?.shutdown()
        metrics = null
    }

    private fun bucket(count: Int): String = when {
        count == 0 -> "0"
        count <= 5 -> "1-5"
        count <= 20 -> "6-20"
        count <= 50 -> "21-50"
        count <= 200 -> "51-200"
        else -> "201+"
    }

    private companion object {
        /** ⚠ 임시 번호다. **배포 전에 bStats 에 등록하고 바꿔야** 통계가 남의 것과 섞이지 않는다. */
        const val PLUGIN_ID = 0
    }
}
