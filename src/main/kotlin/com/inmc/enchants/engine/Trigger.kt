package com.inmc.enchants.engine

/**
 * 인첸트를 발동시키는 사건. 이름은 **AE 와 같다** — AE 설정을 그대로 읽을 수 있어야 한다.
 *
 * [offHand] 가 false 면 왼손 아이템은 보지 않는다(AE: MINING·ATTACK·ATTACK_MOB 은 왼손 제외).
 * 주 손과 방어구는 **항상** 본다 — 부츠에 붙은 ATTACK 인첸트(물속에서 두 배 피해 등)가 있다.
 *
 * [static] 은 "입고 있는 동안" 형이다. 효과는 착용 때 걸고 벗을 때 되돌린다(`%is removed%`).
 */
enum class Trigger(
    val label: String,
    val description: String,
    val category: Category,
    val offHand: Boolean = true,
    val static: Boolean = false,
) {
    ATTACK("공격(플레이어)", "플레이어를 때렸을 때", Category.COMBAT, offHand = false),
    ATTACK_MOB("공격(몹)", "몹을 때렸을 때", Category.COMBAT, offHand = false),
    DEFENSE("방어(플레이어)", "플레이어에게 맞았을 때", Category.COMBAT),
    DEFENSE_MOB("방어(몹)", "몹에게 맞았을 때", Category.COMBAT),
    DEFENSE_PROJECTILE("방어(플레이어 투사체)", "플레이어의 화살·삼지창에 맞았을 때", Category.COMBAT),
    DEFENSE_MOB_PROJECTILE("방어(몹 투사체)", "몹의 투사체에 맞았을 때", Category.COMBAT),
    SHOOT("사격(플레이어)", "쏜 투사체가 플레이어를 맞혔을 때", Category.RANGED),
    SHOOT_MOB("사격(몹)", "쏜 투사체가 몹을 맞혔을 때", Category.RANGED),
    ARROW_HIT("투사체 적중", "쏜 투사체가 어딘가에 닿았을 때(블록 포함)", Category.RANGED),
    BOW_FIRE("활 발사", "활·쇠뇌를 끝까지 당겨 쐈을 때", Category.RANGED),
    KILL("처치", "무엇이든 처치했을 때", Category.COMBAT, offHand = false),
    KILL_MOB("처치(몹)", "몹을 처치했을 때", Category.COMBAT, offHand = false),
    KILL_PLAYER("처치(플레이어)", "플레이어를 처치했을 때", Category.COMBAT, offHand = false),
    DEATH("사망", "죽을 때(부활 효과는 여기서만 듣는다)", Category.COMBAT),
    PASSIVE_DEATH("번개 맞음", "번개에 맞았을 때", Category.ENVIRONMENT),
    EXPLOSION("폭발 피해", "폭발 피해를 받았을 때", Category.ENVIRONMENT),
    FALL_DAMAGE("낙하 피해", "낙하 피해를 받았을 때", Category.ENVIRONMENT),
    FIRE("불 피해", "불·용암 피해를 받았을 때", Category.ENVIRONMENT),
    ELYTRA_FLY("겉날개 비행 시작", "겉날개로 날기 시작할 때", Category.MOVEMENT),
    ELYTRA_FLY_DAMAGE("비행 중 피해", "겉날개로 나는 중 피해를 받았을 때", Category.MOVEMENT),
    JUMP("점프", "점프했을 때", Category.MOVEMENT),
    SHIFT("웅크리기", "웅크리기를 켜고 끌 때", Category.MOVEMENT, static = true),
    SPRINT("달리기", "달리기를 켜고 끌 때", Category.MOVEMENT, static = true),
    MINING("채굴", "블록을 부쉈을 때", Category.TOOL, offHand = false),
    SWING("휘두르기", "손을 휘둘렀을 때(좌클릭)", Category.TOOL, offHand = false),
    RIGHT_CLICK("우클릭", "블록·허공을 우클릭했을 때", Category.TOOL),
    RIGHT_CLICK_ENTITY("엔티티 우클릭", "엔티티를 우클릭했을 때", Category.TOOL),
    ITEM_BREAK("아이템 파손", "아이템이 부서질 때", Category.TOOL),
    EAT("먹기", "음식을 먹었을 때", Category.MISC),
    BREW_POTION("양조", "물약을 양조했을 때", Category.MISC),
    ROD_CAST("낚싯대 던지기", "낚싯대를 던졌을 때", Category.FISHING),
    BITE_HOOK("입질", "찌에 입질이 왔을 때", Category.FISHING),
    CATCH_FISH("낚기", "물고기를 낚았을 때(inmc-fishing 포함)", Category.FISHING),
    HOOK_ENTITY("엔티티 낚기", "낚싯바늘로 엔티티를 걸었을 때", Category.FISHING),
    SHIELD_BLOCK("방패 막기", "방패로 공격을 막았을 때", Category.COMBAT),
    CUSTOM_MOB_DEFENSE("커스텀 몹 공격", "inmc-monster 의 커스텀 몹을 때렸을 때", Category.COMBAT, offHand = false),
    HORN("뿔나팔", "염소 뿔나팔을 불었을 때", Category.MISC),
    COMMAND("명령어", "정한 명령어를 입력했을 때", Category.MISC),
    JOIN("접속", "누군가 접속했을 때", Category.MISC),
    QUIT("퇴장", "누군가 나갔을 때", Category.MISC),
    HELD("손에 듦", "손에 들고 있는 동안", Category.PASSIVE, static = true),
    EFFECT_STATIC("착용", "입고 있는 동안", Category.PASSIVE, static = true),
    REPEATING("반복", "들거나 입고 있는 동안 일정 주기마다", Category.PASSIVE),
    ;

    enum class Category(val label: String) {
        COMBAT("전투"), RANGED("원거리"), ENVIRONMENT("환경"), MOVEMENT("이동"),
        TOOL("도구"), FISHING("낚시"), PASSIVE("지속"), MISC("기타"),
    }

    companion object {

        /**
         * AE 인첸트 파일에 남아 있는 옛 이름. 조건 변수 표(`BOW`·`DEFENSE_BOW` …)와 오래된
         * 팩이 아직 쓴다. 읽을 때만 바꾸고 쓸 때는 새 이름으로 쓴다.
         */
        private val ALIASES = mapOf(
            "BOW" to SHOOT,
            "BOW_MOB" to SHOOT_MOB,
            "DEFENSE_BOW" to DEFENSE_PROJECTILE,
            "DEFENSE_BOW_MOB" to DEFENSE_MOB_PROJECTILE,
            "FISHING" to CATCH_FISH,
            "SNEAK" to SHIFT,
            "STATIC" to EFFECT_STATIC,
            "PLAYER_JOIN" to JOIN,
        )

        fun of(name: String): Trigger? {
            val key = name.trim().uppercase()
            return entries.firstOrNull { it.name == key } ?: ALIASES[key]
        }

        /** `ATTACK;ATTACK_MOB` — AE 의 `type` 칸. 모르는 이름은 [unknown] 으로 모은다. */
        fun parseList(text: String, unknown: MutableList<String> = mutableListOf()): List<Trigger> =
            text.split(';', ',').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { name ->
                of(name).also { if (it == null) unknown += name }
            }.distinct()
    }
}
