package com.inmc.enchants.config

import com.inmc.enchants.util.Ph
import kr.inmc.core.config.MessageCatalog
import org.bukkit.configuration.file.YamlConfiguration

/**
 * `messages.yml` 한 벌. 읽고 보내는 부분은 core [MessageCatalog] 가 갖고, 여기는 기본값 표뿐이다.
 * `ResourceTest` 가 배포 파일과 이 표의 키가 **정확히** 같은지 지킨다.
 */
class Messages(values: Map<String, String>) : MessageCatalog<Ph>(values, DEFAULTS) {

    companion object {

        fun from(config: YamlConfiguration): Messages = Messages(merge(DEFAULTS, config))

        val DEFAULTS: Map<String, String> = linkedMapOf(
            PREFIX to "<gradient:#b980ff:#6ec6ff>[ 인첸트 ]</gradient> ",

            // --- 발동 ---------------------------------------------------------------------
            "souls-not-enough" to "<red>영혼이 모자라 {enchant} 이(가) 발동하지 않았습니다. <gray>(필요 {amount})</gray></red>",
            "money-stolen" to "<gold>{player} 에게서 {amount} 을(를) 훔쳤습니다.</gold>",

            // --- 로어 ---------------------------------------------------------------------
            "tracker-stattrak" to "<red>처치: <white>{count}",
            "tracker-mobtrak" to "<red>몹 처치: <white>{count}",
            "tracker-blocktrak" to "<aqua>캔 블록: <white>{count}",
            "tracker-fishtrak" to "<aqua>낚은 물고기: <white>{count}",
            "transmog-suffix" to " <gray>[<white>{count}</white>]</gray>",

            // --- 공용 ---------------------------------------------------------------------
            "no-permission" to "<red>권한이 없습니다.</red>",
            "player-only" to "<red>이 명령어는 플레이어만 사용할 수 있습니다.</red>",
            "player-not-found" to "<red>'{player}' 을(를) 찾을 수 없습니다.</red>",
            "not-ready" to "<gray>플러그인이 아직 준비 중입니다. 잠시 후 다시 시도해주세요.</gray>",
            "reloaded" to "<green>인첸트 {count}개를 다시 불러왔습니다.</green>",
            "unknown-enchant" to "<red>'{enchant}' 라는 인첸트가 없습니다.</red>",
            "hand-empty" to "<red>손에 아이템을 들고 있어야 합니다.</red>",

            // --- 아이템 -----------------------------------------------------------------------
            "book-applied" to "<green>{enchant} 을(를) 붙였습니다!</green>",
            "book-failed" to "<red>부여에 실패했습니다. 부여서만 사라졌습니다.</red>",
            "book-destroyed" to "<dark_red><bold>부여에 실패해 아이템이 부서졌습니다!</bold></dark_red>",
            "book-protected" to "<yellow>부여에 실패했지만 화이트 스크롤이 아이템을 지켰습니다.</yellow>",
            "book-locked" to "<red>이 아이템에는 커스텀 인첸트를 붙일 수 없습니다.</red>",
            "book-wrong-item" to "<red>{enchant} 은(는) 이 아이템에 붙일 수 없습니다.</red>",
            "book-already" to "<red>이미 같거나 더 높은 레벨이 붙어 있습니다.</red>",
            "book-no-slots" to "<red>인첸트 칸이 가득 찼습니다. 칸 확장기로 늘릴 수 있습니다.</red>",
            "book-missing-required" to "<red>{enchant} 을(를) 붙이려면 먼저 필요한 인첸트가 있어야 합니다.</red>",
            "book-conflict" to "<red>이미 붙은 인첸트와 함께 붙을 수 없습니다.</red>",
            "book-combined" to "<green>부여서를 합쳐 {enchant} 이(가) 되었습니다!</green>",
            "book-max-level" to "<red>이미 최대 레벨입니다.</red>",
            "dust-applied" to "<green>부여서의 성공률이 올랐습니다.</green>",
            "dust-wrong-group" to "<red>등급이 맞지 않습니다.</red>",
            "dust-full" to "<red>이미 성공률이 100% 입니다.</red>",
            "white-scroll-applied" to "<white>아이템이 화이트 스크롤로 보호됩니다.</white>",
            "black-scroll-applied" to "<gray>{enchant} 을(를) 뽑아 부여서로 만들었습니다.</gray>",
            "black-scroll-empty" to "<red>뽑아낼 수 있는 인첸트가 없습니다.</red>",
            "random-scroll-applied" to "<green>부여서의 성공률·파괴율을 새로 뽑았습니다.</green>",
            "transmog-applied" to "<light_purple>인첸트가 등급 순으로 정렬되었습니다.</light_purple>",
            "slots-added" to "<aqua>인첸트 칸이 늘어났습니다.</aqua>",
            "slots-maxed" to "<red>더 늘릴 수 없습니다.</red>",
            "orb-applied" to "<aqua>오브의 힘으로 기본 인첸트 칸이 늘어났습니다.</aqua>",
            "orb-weaker" to "<red>이미 같거나 더 큰 오브가 적용되어 있습니다.</red>",
            "tracker-applied" to "<green>추적기를 달았습니다.</green>",
            "souls-moved" to "<dark_red>영혼을 옮겨 담았습니다.</dark_red>",
            "souls-no-tracker" to "<red>영혼 추적기가 달린 아이템에만 옮길 수 있습니다.</red>",
            "item-not-applicable" to "<red>여기에는 쓸 수 없습니다.</red>",
            "nametag-prompt" to "<yellow>새 이름을 채팅으로 적으세요.</yellow>",
            "nametag-moved" to "<red>아이템이 옮겨져 이름을 바꾸지 못했습니다. 이름표는 돌려드렸습니다.</red>",
            "nametag-applied" to "<green>이름을 바꿨습니다.</green>",
            "unopened-empty" to "<red>이 등급에는 나올 인첸트가 없습니다.</red>",
            "item-opened" to "<gold>{item} 이(가) 나왔습니다!</gold>",
            "table-bonus" to "<light_purple>부여대의 기운으로 {enchant} 이(가) 함께 붙었습니다!</light_purple>",
            "scroll-up" to "<green>강화 성공! {enchant}</green>",
            "scroll-kept" to "<yellow>강화에 실패했습니다. 스크롤만 사라졌습니다.</yellow>",
            "scroll-down" to "<red>강화에 실패해 {enchant} (으)로 떨어졌습니다.</red>",
            "scroll-lost" to "<dark_red>강화에 실패해 {enchant} 이(가) 사라졌습니다.</dark_red>",
            "scroll-protected" to "<yellow>강화에 실패했지만 화이트 스크롤이 하락을 막았습니다.</yellow>",
            "scroll-blacklisted" to "<red>{enchant} 은(는) 이 아이템에 스크롤로 붙이거나 올릴 수 없습니다.</red>",

            // --- 화면 ------------------------------------------------------------------------
            "enchanter-bought" to "<light_purple>{group} 미확인 부여서를 샀습니다.</light_purple>",
            "enchanter-cannot-afford" to "<red>모자랍니다. 가격: {value}</red>",
            "souls-withdrawn" to "<dark_red>영혼을 영혼석으로 꺼냈습니다.</dark_red>",
            "admin-bad-id" to "<red>'{value}' 는 쓸 수 없는 id 입니다. 소문자 영문·숫자·밑줄만 됩니다.</red>",
            "admin-id-taken" to "<red>'{value}' 는 이미 있습니다.</red>",
            "admin-bad-condition" to "<red>조건을 읽을 수 없습니다: {value}</red>",
            "admin-bad-range" to "<red>범위를 읽을 수 없습니다: {value} (예: 5-15)</red>",
            "admin-bad-line" to "<red>효과 줄을 읽을 수 없습니다: {value}</red>",
            "admin-enchant-added" to "<green>손에 든 아이템에 {enchant} 을(를) 붙였습니다.</green>",
            "admin-enchant-removed" to "<gray>손에 든 아이템에서 {enchant} 을(를) 뗐습니다.</gray>",
            "enchant-not-on-item" to "<red>손에 든 아이템에 '{enchant}' 이(가) 없습니다.</red>",
            "scroll-registered" to "<green>강화 스크롤을 커스텀아이템 '{item}' 으로 올렸습니다. 겉모습은 커스텀아이템에서 바꿀 수 있습니다.</green>",
            "scroll-register-failed" to "<red>커스텀아이템에 올리지 못했습니다. 커스텀아이템이 켜져 있는지 확인하세요.</red>",
            "tinker-restored" to "<aqua>땜장이에서 바꾼 것을 되돌렸습니다.</aqua>",
            "tinker-restore-no-exp" to "<red>경험치가 모자라 되돌릴 수 없습니다. (필요 {값})</red>",
            "tinker-restore-no-dust" to "<red>그때 받은 비밀 가루가 가방에 다 있어야 되돌릴 수 있습니다.</red>",
            "tinker-restore-gone" to "<red>되돌릴 수 있는 시간이 지났거나 이미 되돌린 것입니다.</red>",

            // --- 검증(/인첸트 검증) ----------------------------------------------------------
            "verify-start" to "<yellow>{value} 검증을 시작합니다 - {count}건.</yellow> <gray>끝날 때까지 그 자리에 있어 주세요. 검증하는 동안 생존 모드가 되고, 끝나면 가방·상태·모드를 전부 되돌립니다.</gray>",
            "verify-busy" to "<red>검증이 이미 돌고 있습니다.</red>",
            "verify-nothing" to "<gray>검증할 것이 없습니다.</gray>",
            "verify-progress" to "<gray>인첸트 검증 중 {count}/{amount}</gray>",
            "verify-aborted" to "<red>검증을 멈췄습니다 - {value}</red>",
            "verify-done" to "<gray>검증 끝 -</gray> <green>통과 {passed}</green> <gray>·</gray> <red>실패 {failed}</red> <gray>·</gray> <yellow>눈으로 확인 {observed}</yellow> <gray>· 건너뜀 {skipped}</gray>",
            "verify-failure" to "<red>✗</red> <white>{item}</white> <gray>- {value}</gray>",
            "verify-more" to "<gray>…실패 {count}건 더</gray>",
            "verify-report" to "<gray>전체 결과: <white>{file}</white></gray>",

            // --- core ChatPrompt 가 요구하는 넷 -------------------------------------------------
            "prompt-enter" to "<yellow>채팅으로 값을 입력하세요. <gray>(취소: 취소)</gray></yellow>",
            "prompt-cancelled" to "<gray>입력을 취소했습니다.</gray>",
            "prompt-timeout" to "<gray>입력 시간이 지났습니다.</gray>",
            "prompt-invalid-number" to "<red>숫자를 입력해주세요.</red>",
        )
    }
}
