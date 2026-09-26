package com.inmc.enchants

import com.inmc.enchants.config.EnchantConfig
import com.inmc.enchants.config.Messages
import com.inmc.enchants.effect.DropService
import com.inmc.enchants.effect.EffectSupport
import com.inmc.enchants.effect.Effects
import com.inmc.enchants.enchant.EnchantDefinition
import com.inmc.enchants.enchant.EnchantRegistry
import com.inmc.enchants.enchant.GroupRegistry
import com.inmc.enchants.engine.EnchantEngine
import com.inmc.enchants.engine.EngineState
import com.inmc.enchants.engine.Functions
import com.inmc.enchants.engine.Targets
import com.inmc.enchants.engine.Variables
import com.inmc.enchants.item.ActivationStats
import com.inmc.enchants.item.HeadService
import com.inmc.enchants.item.LoreRenderer
import com.inmc.enchants.item.SlotService
import com.inmc.enchants.item.SoulService
import com.inmc.enchants.util.Ph
import kr.inmc.core.InmcHost
import kr.inmc.core.config.ConfigService
import kr.inmc.core.input.ChatPrompt
import kr.inmc.core.integration.CustomItemHook
import kr.inmc.core.integration.EconomyHook
import kr.inmc.core.integration.MMOItemsHook
import kr.inmc.core.item.ItemResolver
import kr.inmc.core.util.Placeholders
import kr.inmc.core.util.Text
import net.kyori.adventure.text.Component
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin

/**
 * 플러그인을 엮는 서비스 로케이터. 서비스는 **한 번만** 만든다 — 리로드는 설정 객체를 바꿀 뿐
 * 서비스를 다시 만들지 않는다(리스너·화면이 낡은 참조를 들게 된다).
 */
class Enchants(override val plugin: JavaPlugin) : InmcHost {

    val logger: java.util.logging.Logger = plugin.logger

    override val io = ConfigService(plugin)

    override fun tell(target: CommandSender, key: String, ph: Placeholders?) =
        messages.send(target, key, ph as? Ph)

    @Volatile
    var messages: Messages = Messages.from(YamlConfiguration())

    @Volatile
    var config: EnchantConfig = EnchantConfig()

    val economy = EconomyHook(logger)
    val mmoItems = MMOItemsHook(logger)
    val customItems = CustomItemHook(logger)
    val itemResolver = ItemResolver(mmoItems, customItems, logger)

    val registry = EnchantRegistry(io, logger)
    val groups = GroupRegistry(io)
    /** 커스텀아이템 세트의 인첸트 효과를 엔진에 태운다. 세트 자체는 커스텀아이템이 관리한다. */
    val setService = com.inmc.enchants.set.SetService(this)
    val tinkerLog = com.inmc.enchants.item.TinkerLog(this)

    val state = EngineState()
    val stats = ActivationStats()
    val effects = Effects()
    val support = EffectSupport(this)
    val drops = DropService()
    val heads = HeadService()

    val variables = Variables(
        customValues = { state.variables[it] },
        papi = { player, text -> papi?.invoke(player, text) },
        combo = { entity -> state.combo(entity.uniqueId, System.currentTimeMillis(), config.comboWindowMillis) },
        listVariables = { config.listVariables },
        marked = { entity, name -> state.isMarked(entity.uniqueId, name, System.currentTimeMillis()) },
    )

    /** PlaceholderAPI 가 있으면 훅이 채운다. */
    @Volatile
    var papi: ((org.bukkit.entity.Player, String) -> String?)? = null

    val targets = Targets { raw, ctx -> Functions.apply(variables.resolve(raw, ctx), java.util.Random()).text }
    val engine = EnchantEngine(this)
    val statics = com.inmc.enchants.engine.StaticEffects(this)
    val projectiles = com.inmc.enchants.listener.ProjectileStore()

    val lore = LoreRenderer(this)
    val slots = SlotService(this)
    val souls = SoulService(this)
    val items = com.inmc.enchants.item.EnchantItems(this)
    val uses = com.inmc.enchants.item.ItemUses(this)

    val prompts = ChatPrompt(this)

    val verifier = com.inmc.enchants.verify.Verifier(this)

    /** 리스너 인스턴스. 검증기가 가짜 사건을 **우리 리스너에만** 넘길 때 쓴다(다른 플러그인은 못 보게). */
    lateinit var combat: com.inmc.enchants.listener.CombatListener
    lateinit var actions: com.inmc.enchants.listener.ActionListener
    lateinit var projectileListener: com.inmc.enchants.listener.ProjectileListener
    lateinit var fishingSignal: com.inmc.enchants.listener.FishingSignalListener
    lateinit var itemListener: com.inmc.enchants.listener.ItemListener
    lateinit var sources: com.inmc.enchants.listener.SourceListener

    @Volatile
    var ready: Boolean = false
        private set

    fun markReady() {
        ready = true
    }

    fun ph(): Ph = Ph.of()

    /** 설정 글자(MiniMessage·`&` 둘 다) → 컴포넌트. */
    fun text(raw: String): Component = Text.render(raw)

    /** 액션바·메시지에 쓰는 "이름 레벨". */
    fun display(def: EnchantDefinition, level: Int): String {
        val levelText = lore.levelText(def, level)
        return (lore.name(def) + " " + levelText).trim()
    }
}
