package com.inmc.enchants.config

import com.inmc.enchants.Enchants
import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kr.inmc.core.store.YamlFileStore
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.enchantments.Enchantment
import org.bukkit.enchantments.EnchantmentOffer
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.EnchantmentStorageMeta
import java.io.File

/**
 * `disabled-enchants.yml` — 서버에서 **금지한 인첸트**(CMI `/cmi disableenchant` 대체, 2026-10-10).
 *
 * 사용자 결정: **새로 나오는 길만 막는다**(부여대 보기·결과, 모루 결과, 주민 거래, 상자 전리품, 우리 부여서·스크롤·뽑기).
 * 이미 가진 아이템은 건드리지 않는다 — 조용히 지우면 민원이 난다. 커스텀아이템 정의로 정체가 정해진 아이템
 * (`CustomItemHook.identify` 가 null 이 아님)은 예외다 — 커스텀아이템이 다시 그릴 때마다 정의의 인첸트를 도로 붙여 서로 뒤집는다.
 *
 * id 는 강화 스크롤과 같은 글자다([com.inmc.enchants.item.ScrollEnchant]) — 우리 것은 `lifesteal`, 바닐라는 `minecraft:sharpness`.
 * 읽지 못한 id(지워진 인첸트·데이터팩이 빠진 인첸트)도 그대로 들고 있다가 되써 넣는다(지뢰 13).
 *
 * 파일이 아직 없으면 **한 번** CMI 의 `Saves/DisabledEnchants.yml` 을 읽어 온다(읽기만 한다).
 */
class DisabledEnchants(private val e: Enchants) : YamlFileStore(
    io = e.io,
    path = listOf(FILE),
    header = HEADER,
    what = "금지 인첸트",
) {

    /** 금지한 id(넣은 순서). 메인 스레드에서만 바꾼다. */
    @Volatile
    private var ids: Set<String> = emptySet()

    /**
     * 파일을 제대로 읽었거나 만들었는가. 아니면(읽기 전·YAML 오류) **쓰지 않는다** — 읽지 못한 파일을 빈 목록으로 덮으면
     * 관리자의 목록이 사라지고, 아직 없는 파일을 빈 것으로 만들면 CMI 가져오기가 영영 건너뛰어진다.
     */
    @Volatile
    private var loaded = false

    /** 검증기가 파일을 건드리지 않고 하나를 잠깐 금지할 때(`/인첸트 검증`). */
    @Volatile
    var probe: String? = null

    override fun read(config: YamlConfiguration) {
        ids = config.getStringList("disabled").mapNotNull { it.trim().lowercase().takeIf(String::isNotEmpty) }.toCollection(LinkedHashSet())
    }

    override fun write(config: YamlConfiguration) {
        config.set("disabled", ids.toList())
    }

    /**
     * 읽는다. 우리 파일이 없으면 CMI 목록을 한 번 가져와 우리 파일을 만든다(목록이 비어도 만든다 — 다음부터는 가져오지 않는다).
     * 파일을 보는 일은 전부 워커에서. 우리 파일의 YAML 이 틀렸으면 크게 남기고 **지금 목록을 그대로 둔 채 저장을 멈춘다**([loaded]).
     */
    fun loadOrImport(then: () -> Unit = {}) {
        val ours = io.file(FILE)
        val cmi = File(e.plugin.dataFolder.parentFile, CMI_FILE)
        io.async({
            if (ours.exists()) Pair<Any?, List<String>?>(strict(ours), null)
            else Pair<Any?, List<String>?>(null, if (cmi.isFile) io.load(cmi).getStringList("List") else null)
        }) { (parsed, imported) ->
            if (parsed is Exception) {
                loaded = false
                e.logger.severe(FILE + " 을(를) 읽지 못했습니다 — 고칠 때까지 금지 목록을 저장하지 않습니다(지금 목록 " + ids.size + "개 유지): " + parsed.message)
            } else if (parsed is YamlConfiguration) {
                read(parsed)
                loaded = true
            } else {
                val (known, unknown) = imported.orEmpty().map { it to fromCmi(it) }.partition { it.second != null }
                ids = known.mapNotNull { it.second }.toCollection(LinkedHashSet())
                if (imported != null) {
                    e.logger.info("CMI 금지 인첸트 " + ids.size + "개를 가져왔습니다(" + CMI_FILE + ")")
                    if (unknown.isNotEmpty()) e.logger.warning("CMI 금지 인첸트 중 읽지 못한 것: " + unknown.joinToString { it.first })
                }
                loaded = true
                markDirty()
                flush()
            }
            then()
        }
    }

    /** 오류를 삼키지 않고 읽는다(`loadConfiguration` 은 틀린 YAML 을 빈 설정으로 준다). 워커에서. */
    private fun strict(file: File): Any =
        try {
            YamlConfiguration().also { it.load(file) }
        } catch (ex: Exception) {
            ex
        }

    /** onDisable 경로 — 제대로 읽은 적이 있을 때만 쓴다([loaded]). */
    fun saveOnShutdown() {
        if (loaded) flushBlocking()
    }

    // --- 묻기 ------------------------------------------------------------------------------

    fun all(): Set<String> = ids

    fun isDisabled(id: String): Boolean {
        val key = id.lowercase()
        return key in ids || key == probe
    }

    fun isDisabled(enchantment: Enchantment): Boolean = isDisabled(enchantment.key.toString())

    /** 켜고 끈다. 바로(원자적으로) 저장한다. @return 이제 금지인가. */
    fun toggle(id: String): Boolean {
        val key = id.trim().lowercase()
        val now = key !in ids
        ids = if (now) LinkedHashSet(ids).also { it += key } else ids - key
        if (loaded) {
            markDirty()
            flush()
        } else {
            e.logger.warning(FILE + " 을(를) 읽지 못한 상태라 이 변경(" + key + ")은 저장하지 않습니다 — 파일을 고치고 /인첸트 리로드 하세요.")
        }
        return now
    }

    // --- 새로 나오는 길에서 빼기 -------------------------------------------------------------------

    /** 커스텀아이템 정의로 정체가 정해진 아이템인가 — 금지 대상에서 뺀다(위 설명). */
    fun exempt(stack: ItemStack): Boolean = e.customItems.identify(stack) != null

    /**
     * 부여대가 보여 줄 보기에서 금지 인첸트를 지운다(그 칸은 누를 수 없게 된다). 하나라도 지웠으면 true.
     * 보기의 인첸트는 미리보기일 뿐이고 실제로 붙는 목록은 따로 굴려진다 — 결과는 [com.inmc.enchants.listener.DisabledEnchantListener] 가 한 번 더 본다.
     */
    fun filterOffers(offers: Array<EnchantmentOffer?>): Boolean {
        var changed = false
        for (index in offers.indices) {
            val offer = offers[index] ?: continue
            if (!isDisabled(offer.enchantment)) continue
            offers[index] = null
            changed = true
        }
        return changed
    }

    /**
     * [stack] 에서 금지 인첸트(붙은 것·책에 담긴 것)를 뗀 사본. [had] 가 있으면 그 아이템이 **이미 가진 레벨까지는** 남긴다(모루 —
     * 원래 가진 것은 두고 새로 얹히는 것만 막는다). 바뀐 것이 없으면 null.
     */
    fun strip(stack: ItemStack, had: ItemStack? = null): ItemStack? {
        if (stack.type.isAir || (ids.isEmpty() && probe == null)) return null
        val result = stack.clone()
        var changed = false
        for ((enchantment, level) in stack.enchantments) {
            if (!isDisabled(enchantment)) continue
            val keep = had?.getEnchantmentLevel(enchantment) ?: 0
            if (level <= keep) continue
            if (keep > 0) result.addUnsafeEnchantment(enchantment, keep) else result.removeEnchantment(enchantment)
            changed = true
        }
        val stored = (stack.itemMeta as? EnchantmentStorageMeta)?.storedEnchants.orEmpty()
        val hadStored = (had?.itemMeta as? EnchantmentStorageMeta)?.storedEnchants.orEmpty()
        val drop = stored.filter { (enchantment, level) -> isDisabled(enchantment) && level > (hadStored[enchantment] ?: 0) }
        if (drop.isNotEmpty()) {
            result.editMeta(EnchantmentStorageMeta::class.java) { meta ->
                for ((enchantment, _) in drop) {
                    meta.removeStoredEnchant(enchantment)
                    hadStored[enchantment]?.let { keep -> meta.addStoredEnchant(enchantment, keep, true) }
                }
            }
            changed = true
        }
        return if (changed) result else null
    }

    /** 담긴 인첸트가 하나도 남지 않은 인첸트 책인가 — 금지 인첸트만 담겼던 책. */
    fun emptyBook(stack: ItemStack): Boolean =
        (stack.itemMeta as? EnchantmentStorageMeta)?.hasStoredEnchants() == false && stack.enchantments.isEmpty()

    companion object {
        const val FILE = "disabled-enchants.yml"

        /** 플러그인 폴더(`plugins/`) 기준 CMI 목록. */
        const val CMI_FILE = "CMI/Saves/DisabledEnchants.yml"

        /**
         * CMI 목록의 한 줄 → 우리 id. CMI 는 옛 Bukkit 이름(`DAMAGE_ALL`)과 지금 이름(`sharpness`)을 다 쓴다 — 설정의 `enchantLimits` 가
         * 옛 이름으로 적혀 있다. 콜론이 있으면 그대로. 서버에 없는 인첸트면 null.
         */
        fun fromCmi(raw: String): String? {
            val id = cmiId(raw) ?: return null
            val key = NamespacedKey.fromString(id) ?: return null
            return runCatching { RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT).get(key) }.getOrNull()?.let { id }
        }

        /** [fromCmi] 의 글자 다루기만 — 서버에 있는지는 보지 않는다. 빈 줄이면 null. */
        fun cmiId(raw: String): String? {
            val name = raw.trim().lowercase().replace(' ', '_').takeIf { it.isNotEmpty() } ?: return null
            return if (':' in name) name else "minecraft:" + (LEGACY[name] ?: name)
        }

        /** 옛 Bukkit 인첸트 이름 → 지금 열쇠. 이름이 같은 것(thorns·mending …)은 없다. */
        val LEGACY: Map<String, String> = mapOf(
            "protection_environmental" to "protection",
            "protection_fire" to "fire_protection",
            "protection_fall" to "feather_falling",
            "protection_explosions" to "blast_protection",
            "protection_projectile" to "projectile_protection",
            "oxygen" to "respiration",
            "water_worker" to "aqua_affinity",
            "damage_all" to "sharpness",
            "damage_undead" to "smite",
            "damage_arthropods" to "bane_of_arthropods",
            "loot_bonus_mobs" to "looting",
            "dig_speed" to "efficiency",
            "durability" to "unbreaking",
            "loot_bonus_blocks" to "fortune",
            "arrow_damage" to "power",
            "arrow_knockback" to "punch",
            "arrow_fire" to "flame",
            "arrow_infinite" to "infinity",
            "luck" to "luck_of_the_sea",
            "sweeping" to "sweeping_edge",
        )

        private val HEADER = """
            ============================================================
             inmc-enchants 금지 인첸트
             /인첸트 관리 → 금지 인첸트, 또는 /인첸트 금지 <id> 로 켜고 끕니다.

             disabled   금지한 인첸트. 우리 인첸트는 id(lifesteal), 바닐라는 minecraft:sharpness
                        새로 나오는 길(부여대·모루·주민 거래·상자 전리품·부여서·강화 스크롤)만 막고
                        이미 가진 아이템은 그대로 둡니다. 커스텀아이템 아이템은 예외입니다.
            ============================================================
        """.trimIndent() + "\n"
    }
}
