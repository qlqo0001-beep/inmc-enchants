package com.inmc.enchants.item

import com.inmc.enchants.Enchants
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 땜장이에서 바꾼 것의 기록 — 정한 시간(`tinkerer.restore-hours`, 기본 72) 안에는 **되돌릴 수 있다**.
 * 되돌릴 때는 그때 받은 경험치와 비밀 가루를 **그대로 돌려받고**, 하나라도 모자라면 되돌리지 않는다.
 *
 * ## 저장은 바꿀 때마다 즉시, 원자적으로
 * 넣은 아이템이 게임에서 사라져 이 파일에만 남는다. `PlayerStore` 의 30초 창에 두면 서버가 죽었을 때 아이템이 증발한다.
 * 그래서 플레이어별 파일(`tinkerer/<uuid>.yml`)을 바꿀 때마다 메인 스레드에서 `.tmp` → rename 으로 쓴다
 * (커스텀아이템 `EquipmentStore` 와 같은 이유).
 *
 * ## 되돌리기 순서 — 복사보다 잃는 쪽
 * 받은 것을 먼저 거두고 → 기록을 지워 저장하고 → 아이템을 돌려준다. 돌려주기 전에 죽으면 그 거래는 잃지만,
 * 거꾸로 하면 같은 아이템을 두 번 받을 수 있다.
 */
class TinkerLog(private val e: Enchants) {

    /** @param at 바꾼 시각(밀리초) — 기록의 id 이기도 하다. @param given 넣은 것. @param dusts 받은 비밀 가루. */
    data class Trade(val at: Long, val given: List<ItemStack>, val exp: Int, val dusts: List<ItemStack>)

    enum class Result { RESTORED, GONE, EXPIRED, NO_EXP, NO_DUST }

    private val loaded = ConcurrentHashMap<UUID, MutableList<Trade>>()

    private val folder: File get() = e.io.file("tinkerer")

    private val window: Long get() = e.config.tinkerRestoreHours * 3_600_000L

    /** 되돌릴 수 있는 것, 새것부터. 시간이 지난 것은 이때 버린다. */
    fun trades(player: UUID, now: Long = System.currentTimeMillis()): List<Trade> {
        val list = of(player)
        if (list.removeIf { now - it.at > window }) save(player)
        return list.sortedByDescending { it.at }
    }

    fun record(player: UUID, trade: Trade) {
        if (e.config.tinkerRestoreHours <= 0 || trade.given.isEmpty()) return
        of(player).add(trade)
        save(player)
    }

    fun restore(player: Player, at: Long, now: Long = System.currentTimeMillis()): Result {
        val list = of(player.uniqueId)
        val trade = list.firstOrNull { it.at == at } ?: return Result.GONE
        if (now - trade.at > window) return Result.EXPIRED
        val total = player.calculateTotalExperiencePoints()
        if (total < trade.exp) return Result.NO_EXP
        if (!hasAll(player, trade.dusts)) return Result.NO_DUST

        for (dust in trade.dusts) player.inventory.removeItem(dust.clone())
        if (trade.exp > 0) player.setExperienceLevelAndProgress(total - trade.exp)
        list.remove(trade)
        save(player.uniqueId)
        for (stack in trade.given) for (left in player.inventory.addItem(stack.clone()).values) player.world.dropItemNaturally(player.location, left)
        return Result.RESTORED
    }

    /** 받은 가루를 전부 갖고 있는가 — 같은 것끼리 묶어 센다(가루는 성공률이 달라 서로 다른 아이템이다). */
    private fun hasAll(player: Player, dusts: List<ItemStack>): Boolean {
        val need = ArrayList<ItemStack>()
        for (dust in dusts) need.firstOrNull { it.isSimilar(dust) }?.let { it.amount += dust.amount } ?: need.add(dust.clone())
        return need.all { wanted -> player.inventory.storageContents.filter { it != null && it.isSimilar(wanted) }.sumOf { it!!.amount } >= wanted.amount }
    }

    /** 기록 하나를 버린다(검증기가 만든 것을 치울 때). */
    fun discard(player: UUID, at: Long) {
        if (of(player).removeIf { it.at == at }) save(player)
    }

    fun forget(player: UUID) {
        loaded.remove(player)
    }

    private fun of(player: UUID): MutableList<Trade> = loaded.getOrPut(player) { read(player) }

    private fun read(player: UUID): MutableList<Trade> {
        val file = File(folder, "$player.yml")
        if (!file.isFile) return ArrayList()
        val yaml = YamlConfiguration.loadConfiguration(file)
        return yaml.getKeys(false).mapNotNull { key ->
            val section = yaml.getConfigurationSection(key) ?: return@mapNotNull null
            runCatching {
                Trade(key.toLong(), section.getStringList("given").map(::decode), section.getInt("exp"), section.getStringList("dusts").map(::decode))
            }.onFailure { e.logger.warning("땜장이 기록을 읽지 못했습니다 ($player $key): ${it.message}") }.getOrNull()
        }.toMutableList()
    }

    private fun save(player: UUID) {
        val list = loaded[player] ?: return
        val target = File(folder, "$player.yml")
        if (list.isEmpty()) {
            target.delete()
            return
        }
        val yaml = YamlConfiguration()
        for (trade in list) {
            val section = yaml.createSection(trade.at.toString())
            section.set("given", trade.given.map(::encode))
            section.set("exp", trade.exp)
            section.set("dusts", trade.dusts.map(::encode))
        }
        folder.mkdirs()
        val temp = File(folder, "$player.yml.tmp")
        temp.writeText(yaml.saveToString(), Charsets.UTF_8)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encode(stack: ItemStack): String = Base64.getEncoder().encodeToString(stack.serializeAsBytes())

    private fun decode(raw: String): ItemStack = ItemStack.deserializeBytes(Base64.getDecoder().decode(raw))
}
