package com.inmc.enchants

import com.inmc.enchants.command.EnchantsCommand
import com.inmc.enchants.config.EnchantConfig
import com.inmc.enchants.config.Messages
import com.inmc.enchants.item.EnchantProvider
import com.inmc.enchants.listener.ActionListener
import com.inmc.enchants.listener.ChatInputListener
import com.inmc.enchants.listener.CombatListener
import com.inmc.enchants.listener.FishingSignalListener
import com.inmc.enchants.listener.ProjectileListener
import com.inmc.enchants.scheduler.Ticker
import kr.inmc.core.integration.CustomEnchantHook
import org.bukkit.plugin.java.JavaPlugin

/**
 * 진입점. **배선만** 한다.
 */
class EnchantsPlugin : JavaPlugin() {

    private lateinit var enchants: Enchants
    private lateinit var ticker: Ticker
    private lateinit var provider: EnchantProvider
    private lateinit var papi: com.inmc.enchants.hook.PapiHook
    private lateinit var metrics: com.inmc.enchants.hook.MetricsHook

    override fun onEnable() {
        enchants = Enchants(this)
        enchants.economy.setup()
        enchants.mmoItems.setup()
        enchants.customItems.setup()

        // 정의를 다 읽기 전에 꽂는다 — 공급처는 레지스트리를 그때그때 읽으므로 비어 있는 동안은 "없음"이다.
        provider = EnchantProvider(enchants)
        CustomEnchantHook.register(provider)

        val actions = ActionListener(enchants)
        ticker = Ticker(enchants, actions)
        registerListeners(actions)
        EnchantsCommand(enchants, this).register()

        // 커스텀아이템에 "인첸트 아이템·부여서 모양" 역할을 내놓는다(core ItemRoles). 역할이 바뀌면 겉모습을 다시 읽고,
        // 커스텀아이템이 처음 꽂히면 인첸트 아이템을 그 목록에 한 번 올린다.
        for (role in com.inmc.enchants.item.EnchantRoles.roles(enchants)) kr.inmc.core.integration.ItemRoles.register(role)
        kr.inmc.core.integration.ItemRoles.listen(com.inmc.enchants.item.EnchantRoles.OWNER) { role ->
            if (role != null && role != com.inmc.enchants.item.EnchantRoles.ITEM && role != com.inmc.enchants.item.EnchantRoles.BOOK) return@listen
            if (role == null && enchants.ready) {
                com.inmc.enchants.item.EnchantRoles.link(enchants)
                // 커스텀아이템이 늦게 켜졌으면 옛 세트 파일을 여기서 옮긴다.
                com.inmc.enchants.set.SetMigration.run(enchants)
            }
            enchants.items.refreshAppearances()
        }

        papi = com.inmc.enchants.hook.PapiHook(enchants).also { it.setup() }
        metrics = com.inmc.enchants.hook.MetricsHook(enchants).also { it.start() }

        reload {
            enchants.markReady()
            com.inmc.enchants.item.EnchantRoles.link(enchants)
            com.inmc.enchants.set.SetMigration.run(enchants)
            enchants.items.refreshAppearances()
            ticker.start()
            logger.info("inmc-enchants 활성화 완료 - 인첸트 " + enchants.registry.size + "개")
        }
    }

    override fun onDisable() {
        if (!::enchants.isInitialized) return
        if (::provider.isInitialized) CustomEnchantHook.unregister(provider)
        kr.inmc.core.integration.ItemRoles.unregisterAll(com.inmc.enchants.item.EnchantRoles.OWNER)
        if (::papi.isInitialized) papi.teardown()
        if (::metrics.isInitialized) metrics.stop()
        enchants.verifier.abort("서버가 내려갑니다")
        ticker.stop()
        enchants.statics.clearAll()
        enchants.support.shutdown()
        enchants.registry.flushBlocking()
        enchants.groups.flushBlocking()
        enchants.io.shutdown()
    }

    private fun registerListeners(actions: ActionListener) {
        val manager = server.pluginManager
        enchants.combat = CombatListener(enchants)
        enchants.actions = actions
        enchants.projectileListener = ProjectileListener(enchants)
        enchants.fishingSignal = FishingSignalListener(enchants, actions)
        manager.registerEvents(enchants.combat, this)
        manager.registerEvents(enchants.projectileListener, this)
        manager.registerEvents(actions, this)
        manager.registerEvents(enchants.fishingSignal, this)
        manager.registerEvents(enchants.statics, this)
        manager.registerEvents(enchants.support, this)
        manager.registerEvents(ChatInputListener(enchants), this)
        enchants.itemListener = com.inmc.enchants.listener.ItemListener(enchants)
        manager.registerEvents(enchants.itemListener, this)
        enchants.sources = com.inmc.enchants.listener.SourceListener(enchants)
        manager.registerEvents(enchants.sources, this)
        manager.registerEvents(enchants.verifier, this)
        manager.registerEvents(kr.inmc.core.listener.MenuListener(enchants), this)
    }

    /**
     * 설정·메시지·등급·인첸트를 다시 읽는다. 파일 읽기는 워커에서, 반영은 메인에서
     * (core [kr.inmc.core.config.ConfigService] 의 계약).
     */
    fun reload(then: () -> Unit = {}) {
        for (name in RESOURCES) {
            val target = enchants.io.file(name)
            // 옮긴 파일(세트 → 커스텀아이템)은 기본값을 다시 깔지 않는다 — 깔면 다음에 또 옮겨 세트가 둘이 된다.
            if (kr.inmc.core.integration.ItemRoles.isRetired(target)) continue
            enchants.io.copyDefault(name, target)
        }
        enchants.io.async({
            Triple(
                enchants.io.load(enchants.io.file("config.yml")),
                enchants.io.load(enchants.io.file("messages.yml")),
                enchants.io.load(enchants.io.file("mob-heads.yml")) to enchants.io.load(enchants.io.file("items.yml")),
            )
        }) { (configYaml, messagesYaml, extras) ->
            val (headsYaml, itemsYaml) = extras
            enchants.config = EnchantConfig.load(configYaml)
            enchants.messages = Messages.from(messagesYaml)
            enchants.heads.load(headsYaml.getKeys(false).associateWith { headsYaml.getString(it).orEmpty() })
            enchants.items.load(itemsYaml.getConfigurationSection("items"))
            closeOpenMenus()
            enchants.groups.load {
                enchants.registry.load {
                    // 정의가 바뀌었으니 세트 효과를 다시 읽고, 걸린 지속 효과를 새 정의로 다시 건다.
                    enchants.setService.rebuild()
                    enchants.statics.clearAll()
                    then()
                }
            }
        }
    }

    /** 이 플러그인이 띄운 화면을 전부 닫는다. 정의 객체가 교체되므로 낡은 것을 편집하게 두면 안 된다. */
    private fun closeOpenMenus() {
        for (player in server.onlinePlayers) {
            val holder = player.openInventory.topInventory.holder
            if (holder !is kr.inmc.core.gui.Menu || holder.owner !== enchants) continue
            player.closeInventory()
        }
    }

    companion object {
        /** 처음 한 번 깔아주는 배포 파일들. */
        val RESOURCES = listOf("config.yml", "messages.yml", "groups.yml", "enchantments.yml", "mob-heads.yml", "items.yml", "sets.yml")
    }
}
