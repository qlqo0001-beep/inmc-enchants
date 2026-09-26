package com.inmc.enchants.enchant

import kr.inmc.core.config.ConfigService
import kr.inmc.core.store.YamlFileStore
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * `enchantments.yml` — 인첸트 정의 전부. 편집 화면이 고친 것도 여기로 저장된다.
 *
 * **적재 때 거부한 항목의 원문을 들고 있다가 저장할 때 되써 넣는다**(`ARCHITECTURE.md` 지뢰 13).
 * [YamlFileStore] 는 들고 있는 것만 쓰므로, 안 그러면 오타 난 인첸트 하나가 다음 저장에서
 * 파일째 사라진다 — 관리자는 고칠 기회도 없이 정의를 잃는다.
 */
class EnchantRegistry(io: ConfigService, private val logger: Logger) : YamlFileStore(
    io = io,
    path = listOf("enchantments.yml"),
    header = "커스텀 인첸트 정의. /인첸트 관리 에서 GUI 로 만들고 고치는 것을 권장합니다.\n" +
        "문법은 AdvancedEnchantments 와 같습니다 - AE 의 enchantments.yml 을 그대로 붙여 넣어도 읽힙니다.\n",
    what = "인첸트",
) {

    private val byId = ConcurrentHashMap<String, EnchantDefinition>()

    /** 읽지 못한 항목의 원문. 저장 때 그대로 되써 넣는다. */
    private val rejected = LinkedHashMap<String, Map<String, Any?>>()

    /** 마지막 적재에서 나온 문제. 편집 화면과 `/인첸트 검증` 이 보여준다. */
    @Volatile
    var problems: List<String> = emptyList()
        private set

    val size: Int get() = byId.size

    /** 검증기가 잠깐 끼워 넣는 정의. **저장하지 않는다.** */
    private val transient = ConcurrentHashMap<String, EnchantDefinition>()

    fun get(id: String): EnchantDefinition? = byId[id.lowercase()] ?: transient[id.lowercase()]

    fun putTransient(def: EnchantDefinition) {
        transient[def.id] = def
    }

    fun removeTransient(id: String) {
        transient.remove(id)
    }

    fun all(): List<EnchantDefinition> = byId.values.sortedBy { it.id }

    fun ids(): List<String> = byId.keys.sorted()

    override fun read(config: YamlConfiguration) {
        val found = LinkedHashMap<String, EnchantDefinition>()
        val issues = ArrayList<String>()
        rejected.clear()
        for (key in config.getKeys(false)) {
            val section = config.getConfigurationSection(key) ?: continue
            val def = EnchantDefinition.load(key, section, issues)
            if (def == null) {
                rejected[key] = section.getValues(true)
                continue
            }
            found[def.id] = def
        }
        byId.clear()
        byId.putAll(found)
        problems = issues
        for (issue in issues.take(30)) logger.warning("인첸트 설정: $issue")
        if (issues.size > 30) logger.warning("인첸트 설정 문제가 ${issues.size - 30}개 더 있습니다 - /인첸트 검증 으로 전부 보세요")
    }

    override fun write(config: YamlConfiguration) {
        for (def in all()) def.save(config.createSection(def.id))
        for ((key, values) in rejected) {
            if (byId.containsKey(key)) continue
            val section = config.createSection(key)
            for ((path, value) in values) if (value !is ConfigurationSection) section.set(path, value)
        }
    }

    /** 새로 만들거나 바꾼다. 저장은 다음 플러시에. */
    fun put(def: EnchantDefinition) {
        byId[def.id] = def
        rejected.remove(def.id)
        markDirty()
    }

    fun remove(id: String): Boolean {
        val removed = byId.remove(id.lowercase()) != null || rejected.remove(id.lowercase()) != null
        if (removed) markDirty()
        return removed
    }
}

/** `groups.yml`. */
class GroupRegistry(io: ConfigService) : YamlFileStore(
    io = io,
    path = listOf("groups.yml"),
    header = "인첸트 등급. 색·순서·부여서 모양·가루와 확장기 수치.\n",
    what = "등급",
) {

    private val byId = ConcurrentHashMap<String, Group>()

    fun get(id: String): Group = byId[id.uppercase()] ?: byId[Group.FALLBACK.id] ?: Group.FALLBACK

    fun find(id: String): Group? = byId[id.uppercase()]

    /** 귀한 순서의 반대(낮은 것부터). */
    fun all(): List<Group> = byId.values.sortedBy { it.order }

    override fun read(config: YamlConfiguration) {
        val section = config.getConfigurationSection("groups") ?: return
        byId.clear()
        for ((index, key) in section.getKeys(false).withIndex()) {
            val node = section.getConfigurationSection(key) ?: continue
            val group = Group.load(key, node, index + 1)
            byId[group.id] = group
        }
    }

    override fun write(config: YamlConfiguration) {
        val section = config.createSection("groups")
        for (group in all()) group.save(section.createSection(group.id))
    }

    fun put(group: Group) {
        byId[group.id] = group
        markDirty()
    }

    fun remove(id: String): Boolean = (byId.remove(id.uppercase()) != null).also { if (it) markDirty() }
}
