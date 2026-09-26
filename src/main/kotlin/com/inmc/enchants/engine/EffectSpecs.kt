package com.inmc.enchants.engine

import com.inmc.enchants.engine.EffectSpec.Acts
import com.inmc.enchants.engine.EffectSpec.Category

/**
 * 효과 전부의 모양. **이름과 인자 순서는 AE 와 같다** — AE 설정 줄을 그대로 읽을 수 있어야 한다.
 *
 * 여기 없는 이름은 적재 때 거부된다. 새 효과를 더하면 `effect/` 에 실행 코드도 있어야 하고,
 * `EffectCoverageTest` 가 둘이 짝이 맞는지 본다.
 */
object EffectSpecs {

    private fun num(key: String, label: String, default: String? = null) =
        ArgSpec(key, label, ArgType.NUMBER, optional = default != null, default = default ?: "")

    private fun int(key: String, label: String, default: String? = null) =
        ArgSpec(key, label, ArgType.INT, optional = default != null, default = default ?: "")

    private fun bool(key: String, label: String, default: String) =
        ArgSpec(key, label, ArgType.BOOL, optional = true, default = default)

    private fun text(key: String, label: String) = ArgSpec(key, label, ArgType.TEXT)

    private fun word(key: String, label: String, default: String? = null) =
        ArgSpec(key, label, ArgType.WORD, optional = default != null, default = default ?: "")

    private fun choice(key: String, label: String, choices: List<String>, default: String? = null) =
        ArgSpec(key, label, ArgType.CHOICE, optional = default != null, default = default ?: "", choices = choices)

    private fun typed(key: String, label: String, type: ArgType, default: String? = null) =
        ArgSpec(key, label, type, optional = default != null, default = default ?: "")

    private val TICKS = "틱(20 = 1초)"

    val ALL: List<EffectSpec> = listOf(
        // --- 피해 (사건을 바꾼다) --------------------------------------------------------
        EffectSpec("INCREASE_DAMAGE", "피해 증가(%)", "이번 공격의 피해를 %만큼 늘린다", Category.DAMAGE,
            listOf(num("amount", "증가율(%)")), Acts.EVENT),
        EffectSpec("DECREASE_DAMAGE", "피해 감소(%)", "이번 공격의 피해를 %만큼 줄인다", Category.DAMAGE,
            listOf(num("amount", "감소율(%)")), Acts.EVENT),
        EffectSpec("DOUBLE_DAMAGE", "피해 두 배", "이번 공격의 피해를 두 배로", Category.DAMAGE, acts = Acts.EVENT),
        EffectSpec("HALF_DAMAGE", "피해 절반", "이번 공격의 피해를 절반으로", Category.DAMAGE, acts = Acts.EVENT),
        EffectSpec("NEGATE_DAMAGE", "피해 무효(고정)", "이번 공격의 피해에서 고정값을 뺀다", Category.DAMAGE,
            listOf(num("amount", "뺄 피해")), Acts.EVENT),
        EffectSpec("IGNORE_ARMOR_PROTECTION", "방어력 무시", "이번 공격은 방어구 경감을 받지 않는다", Category.DAMAGE, acts = Acts.EVENT),
        EffectSpec("IGNORE_ARMOR_DAMAGE", "방어구 내구도 보호", "이번 공격으로 방어구 내구도가 닳지 않는다", Category.DAMAGE, acts = Acts.EVENT),
        EffectSpec("CANCEL_EVENT", "사건 취소", "이번 사건(공격·피해·채굴 등)을 없던 일로", Category.DAMAGE, acts = Acts.EVENT),
        EffectSpec("DISABLE_KNOCKBACK", "넉백 무효(시간)", "정한 시간 동안 넉백을 받지 않는다", Category.DAMAGE,
            listOf(int("ticks", TICKS))),
        EffectSpec("STOP_KNOCKBACK", "넉백 무효(이번)", "이번 공격의 넉백을 없앤다", Category.DAMAGE),
        EffectSpec("DO_HARM", "피해 주기", "피해를 준다(방어구 경감 적용)", Category.DAMAGE,
            listOf(num("health", "피해량"))),
        EffectSpec("REMOVE_HEALTH", "체력 깎기", "방어구를 무시하고 체력을 깎는다(맞는 연출 없음)", Category.DAMAGE,
            listOf(num("health", "깎을 체력"))),
        EffectSpec("REMOVE_HEALTH_DAMAGE", "체력 깎기(연출)", "방어구를 무시하고 체력을 깎는다(맞는 연출 있음)", Category.DAMAGE,
            listOf(num("health", "깎을 체력"))),
        EffectSpec("REMOVE_HEALTH_TOTEM", "체력 깎기(토템 허용)", "체력을 깎되 불사의 토템이 발동할 수 있다", Category.DAMAGE,
            listOf(num("health", "깎을 체력"))),
        EffectSpec("REMOVE_HEALTH_DAMAGE_TOTEM", "체력 깎기(연출·토템)", "연출과 함께 깎고 토템을 허용한다", Category.DAMAGE,
            listOf(num("health", "깎을 체력"))),
        EffectSpec("KILL", "즉사", "대상을 죽인다", Category.DAMAGE),
        EffectSpec("BLEED", "출혈", "일정 간격으로 피해를 준다", Category.DAMAGE,
            listOf(num("damage", "한 번 피해"), int("ticks", "지속 $TICKS"), int("interval", "간격 $TICKS", "20"))),
        EffectSpec("STEAL_HEALTH", "체력 흡수", "대상의 체력을 빼앗아 발동한 쪽에게 준다", Category.DAMAGE,
            listOf(num("health", "빼앗을 체력"))),
        EffectSpec("TNT", "폭발 피해", "블록을 부수지 않는 폭발로 주변에 피해", Category.DAMAGE,
            listOf(num("damage", "피해", "4"), num("radius", "반경", "3")), Acts.LOCATION),
        EffectSpec("EXPLODE", "폭발", "폭발을 일으킨다", Category.DAMAGE,
            listOf(num("power", "위력"), bool("fire", "불 붙이기", "false"), bool("break", "블록 파괴", "false")), Acts.LOCATION),
        EffectSpec("LIGHTNING", "번개", "번개를 내리친다(false 면 연출만)", Category.DAMAGE,
            listOf(bool("damage", "피해·화재", "true")), Acts.LOCATION),

        // --- 체력·상태 ---------------------------------------------------------------
        EffectSpec("ADD_HEALTH", "체력 회복", "체력을 더한다(최대치까지)", Category.HEALTH, listOf(num("health", "회복량"))),
        EffectSpec("ADD_FOOD", "허기 채우기", "허기를 더하거나 뺀다", Category.HEALTH, listOf(int("amount", "허기"))),
        EffectSpec("AIR", "산소 더하기", "남은 산소를 더하거나 뺀다", Category.HEALTH, listOf(int("amount", "산소 $TICKS"))),
        EffectSpec("SET_AIR", "산소 정하기", "남은 산소를 정한다", Category.HEALTH, listOf(int("amount", "산소 $TICKS"))),
        EffectSpec("BURN", "불 붙이기", "불을 붙인다", Category.HEALTH, listOf(int("ticks", TICKS))),
        EffectSpec("EXTINGUISH", "불 끄기", "불을 끈다", Category.HEALTH),
        EffectSpec("FREEZE", "빙결", "움직이지 못하게 얼린다", Category.HEALTH, listOf(int("ticks", TICKS))),
        EffectSpec("SCREEN_FREEZE", "화면 빙결", "느려지고 화면에 서리가 낀다", Category.HEALTH, listOf(int("ticks", TICKS))),
        EffectSpec("SNOWBLIND", "눈보라", "앞이 보이지 않고 느려진다", Category.HEALTH, listOf(int("ticks", TICKS, "60"))),
        EffectSpec("INVINCIBLE", "무적", "피해를 받지 않는다(시간을 비우면 착용 동안)", Category.HEALTH, listOf(int("ticks", TICKS, "0")), reversible = true),
        EffectSpec("REVIVE", "부활", "죽음을 막고 되살린다", Category.HEALTH, triggers = setOf(Trigger.DEATH)),
        // AE 의 KEEP_ON_DEATH(사망 시 보존)는 일부러 없다 - 인벤 보호는 inmc-invkeeper 가 맡는다(운영자 결정).
        EffectSpec("CURE", "해제", "물약 효과를 없앤다(영구 효과는 남김)", Category.POTION, listOf(typed("potion", "물약", ArgType.POTION))),
        EffectSpec("CURE_PERMANENT", "영구 효과 해제", "영구(무한) 물약 효과를 없앤다", Category.POTION, listOf(typed("potion", "물약", ArgType.POTION))),

        // --- 물약 ----------------------------------------------------------------------
        EffectSpec("POTION", "물약 효과", "물약 효과를 건다(지속형 트리거에서 시간을 비우면 착용 동안)", Category.POTION,
            listOf(typed("potion", "물약", ArgType.POTION), int("level", "단계(0 = I)"), int("ticks", TICKS, "100")), reversible = true),
        EffectSpec("POTION_OVERRIDE", "물약 효과(덮어쓰기)", "이미 있어도 강제로 덮어쓴다", Category.POTION,
            listOf(typed("potion", "물약", ArgType.POTION), int("level", "단계(0 = I)"), int("ticks", TICKS, "100")), reversible = true),

        // --- 이동 ----------------------------------------------------------------------
        EffectSpec("BOOST", "튕겨내기", "정한 방향으로 날린다", Category.MOVEMENT,
            listOf(choice("direction", "방향", Names.DIRECTIONS, "UP"), num("amount", "세기", "1"))),
        EffectSpec("PULL_AWAY", "밀어내기", "발동한 쪽에서 멀리 밀어낸다", Category.MOVEMENT, listOf(num("distance", "세기"))),
        EffectSpec("PULL_CLOSER", "끌어당기기", "발동한 쪽으로 끌어당긴다", Category.MOVEMENT, listOf(num("amount", "세기"))),
        EffectSpec("TELEPORT", "순간이동", "location= 위치로 옮긴다", Category.MOVEMENT),
        EffectSpec("TELEPORT_BEHIND", "등 뒤로 이동", "상대의 등 뒤로 옮긴다", Category.MOVEMENT),
        EffectSpec("FLY", "비행", "날 수 있게 한다(시간을 비우면 착용 동안)", Category.MOVEMENT, listOf(int("ticks", TICKS, "0")), reversible = true),
        EffectSpec("FLY_SPEED", "비행 속도 정하기", "비행 속도(-1~1)를 정한다", Category.MOVEMENT, listOf(num("speed", "속도")), reversible = true),
        EffectSpec("ADD_FLY_SPEED", "비행 속도 더하기", "비행 속도에 더한다", Category.MOVEMENT, listOf(num("speed", "더할 속도")), reversible = true),
        EffectSpec("WALK_SPEED", "이동 속도 정하기", "걷는 속도(-1~1)를 정한다", Category.MOVEMENT, listOf(num("speed", "속도")), reversible = true),
        EffectSpec("ADD_WALK_SPEED", "이동 속도 더하기", "걷는 속도에 더한다", Category.MOVEMENT, listOf(num("speed", "더할 속도")), reversible = true),
        EffectSpec("WATER_WALKER", "물 위 걷기", "물 위를 걷는다", Category.MOVEMENT, reversible = true),
        EffectSpec("LAVA_WALKER", "용암 위 걷기", "용암 위를 걷는다", Category.MOVEMENT, reversible = true),
        EffectSpec("WEB_WALKER", "거미줄 통과", "거미줄에서 느려지지 않는다", Category.MOVEMENT, reversible = true),

        // --- 블록·채굴 -----------------------------------------------------------------
        EffectSpec("BREAK_BLOCK", "블록 부수기", "대상 블록을 부순다(보호 구역 존중)", Category.BLOCK, acts = Acts.BLOCK),
        EffectSpec("BREAK_TREE", "나무 통째로 베기", "이어진 원목(과 잎)을 한 번에 벤다", Category.BLOCK,
            listOf(int("logs", "최대 원목", "128"), int("leaves", "최대 잎", "0")), Acts.BLOCK),
        EffectSpec("SET_BLOCK", "블록 바꾸기", "대상 블록을 바꾼다", Category.BLOCK,
            listOf(typed("material", "블록", ArgType.MATERIAL)), Acts.BLOCK),
        EffectSpec("PLANT_SEEDS", "씨앗 심기", "주변 경작지에 씨앗을 심는다(가방의 씨앗을 쓴다)", Category.BLOCK,
            listOf(int("radius", "반경"), choice("seed", "작물", Names.SEEDS, "SEEDS")), Acts.BLOCK),
        EffectSpec("SMELT", "자동 제련", "캔 블록의 드랍을 제련한다", Category.BLOCK, acts = Acts.EVENT, triggers = setOf(Trigger.MINING)),
        EffectSpec("MORE_DROPS", "드랍 배수", "드랍을 몇 배로 늘린다", Category.BLOCK,
            listOf(num("amount", "배수")), Acts.EVENT, setOf(Trigger.MINING, Trigger.KILL_MOB, Trigger.KILL, Trigger.CATCH_FISH)),
        EffectSpec("TP_DROPS", "드랍 가방으로", "드랍이 바로 가방에 들어온다", Category.BLOCK, acts = Acts.EVENT,
            triggers = setOf(Trigger.MINING, Trigger.KILL_MOB, Trigger.KILL, Trigger.KILL_PLAYER)),
        EffectSpec("EXP", "경험치 떨구기", "경험치 구슬을 떨군다", Category.ECONOMY, listOf(int("amount", "경험치")), Acts.LOCATION),

        // --- 아이템·장비 ---------------------------------------------------------------
        EffectSpec("ADD_DURABILITY_CURRENT_ITEM", "든 아이템 내구도", "발동한 아이템의 내구도(+ 수리, - 손상)", Category.ITEM, listOf(int("amount", "내구도"))),
        EffectSpec("ADD_DURABILITY_ARMOR", "방어구 내구도", "입은 방어구 전부의 내구도(+ 수리, - 손상). MOST_DAMAGED 면 가장 많이 닳은 하나만", Category.ITEM,
            listOf(int("amount", "내구도"), choice("which", "어느 것", listOf("ALL", "MOST_DAMAGED"), "ALL"))),
        EffectSpec("DAMAGE_ARMOR", "방어구 손상", "입은 방어구 내구도를 깎는다(옛 이름)", Category.ITEM, listOf(int("amount", "손상"))),
        EffectSpec("ADD_DURABILITY_ITEM", "칸 아이템 내구도", "정한 칸의 아이템 내구도", Category.ITEM, listOf(int("slot", "칸 번호"), int("amount", "내구도"))),
        EffectSpec("REPAIR", "수리", "발동한 아이템을 완전히 수리한다", Category.ITEM),
        EffectSpec("DISARM", "무장 해제", "손에 든 것을 떨어뜨리게 한다(플레이어는 가방으로)", Category.ITEM),
        EffectSpec("DROP_HEAD", "머리 떨구기", "대상의 머리를 떨군다", Category.ITEM),
        EffectSpec("DROP_HELD_ITEM", "든 것 떨구기", "손에 든 아이템을 땅에 떨군다", Category.ITEM),
        EffectSpec("GIVE_ITEM", "아이템 주기", "아이템을 준다(재질 또는 inmc:아이템)", Category.ITEM,
            listOf(typed("item", "아이템", ArgType.ITEM), int("amount", "개수", "1"))),
        EffectSpec("TAKE_AWAY", "아이템 빼앗기", "가방에서 아이템을 가져간다", Category.ITEM,
            listOf(typed("material", "재질", ArgType.MATERIAL), int("amount", "개수", "1"))),
        EffectSpec("DELETE_ITEM", "든 아이템 줄이기", "발동한 아이템을 개수만큼 없앤다", Category.ITEM, listOf(int("amount", "개수", "1"))),
        EffectSpec("PUMPKIN", "호박 씌우기", "머리에 호박을 씌웠다가 되돌린다", Category.ITEM, listOf(int("ticks", TICKS))),
        EffectSpec("REMOVE_ARMOR", "방어구 벗기기", "정한 방어구를 벗긴다", Category.ITEM, listOf(choice("slot", "부위", Names.ARMOR_SLOTS))),
        EffectSpec("REMOVE_RANDOM_ARMOR", "무작위 방어구 벗기기", "방어구 하나를 무작위로 벗긴다", Category.ITEM),
        EffectSpec("SHUFFLE_HOTBAR", "단축바 뒤섞기", "단축바 아이템 순서를 섞는다", Category.ITEM),
        EffectSpec("CANCEL_USE", "사용 금지", "정한 아이템을 한동안 못 쓰게 한다", Category.ITEM,
            listOf(typed("material", "재질", ArgType.MATERIAL), int("ticks", TICKS))),
        EffectSpec("OPEN_CRAFTING_TABLE", "작업대 열기", "어디서나 작업대를 연다", Category.ITEM),
        EffectSpec("OPEN_ENDERCHEST", "엔더 상자 열기", "어디서나 엔더 상자를 연다", Category.ITEM),
        EffectSpec("AUTO_REEL", "자동 감기", "입질이 오면 자동으로 감는다", Category.ITEM, triggers = setOf(Trigger.BITE_HOOK)),
        EffectSpec("SET_MAX_CATCH_TIME", "최대 입질 시간", "입질까지 걸리는 최대 시간", Category.ITEM,
            listOf(int("ticks", TICKS)), triggers = setOf(Trigger.ROD_CAST)),
        EffectSpec("SET_MIN_CATCH_TIME", "최소 입질 시간", "입질까지 걸리는 최소 시간", Category.ITEM,
            listOf(int("ticks", TICKS)), triggers = setOf(Trigger.ROD_CAST)),

        // --- 소환·투사체 ---------------------------------------------------------------
        EffectSpec("GUARD", "호위 소환", "대상을 지키는 호위를 소환한다(뒤바꾸면 대상을 공격)", Category.SPAWN,
            listOf(typed("entity", "종류(BABY_ 접두사 가능)", ArgType.ENTITY_TYPE), int("seconds", "지속(초)"),
                int("amount", "수", "1"), word("name", "이름", ""), bool("switch", "역할 뒤바꾸기", "false"))),
        EffectSpec("SPAWN_ENTITY", "엔티티 소환", "엔티티를 소환한다", Category.SPAWN,
            listOf(typed("entity", "종류", ArgType.ENTITY_TYPE)), Acts.LOCATION),
        EffectSpec("PROJECTILE", "투사체 발사", "바라보는 방향으로 투사체를 쏜다", Category.SPAWN,
            listOf(typed("entity", "투사체 종류", ArgType.ENTITY_TYPE))),
        EffectSpec("FIREBALL", "화염구", "바라보는 방향으로 화염구를 쏜다", Category.SPAWN),
        EffectSpec("SPAWN_ARROWS", "화살비", "위에서 화살을 쏟아붓는다", Category.SPAWN, listOf(int("amount", "화살 수", "10")), Acts.LOCATION),
        EffectSpec("SPAWN_BLOCKS", "낙석", "위에서 블록을 떨어뜨려 피해를 준다", Category.SPAWN,
            listOf(typed("material", "블록", ArgType.MATERIAL), num("damage", "피해")), Acts.LOCATION),

        // --- 연출 ----------------------------------------------------------------------
        EffectSpec("PARTICLE", "입자", "입자를 뿌린다", Category.VISUAL,
            listOf(typed("particle", "입자", ArgType.PARTICLE), int("amount", "개수", "10"), num("offset", "퍼짐", "0.5")), Acts.LOCATION),
        EffectSpec("PARTICLE_LINE", "입자 선", "발동한 쪽에서 대상까지 입자 선을 긋는다", Category.VISUAL,
            listOf(typed("particle", "입자", ArgType.PARTICLE), int("amount", "점마다 개수", "1"), int("points", "점 수", "20")), Acts.LOCATION),
        EffectSpec("BLOOD", "피 튀기기", "피 튀는 연출", Category.VISUAL, acts = Acts.LOCATION),
        EffectSpec("CACTUS", "가시 연출", "선인장 가시 연출", Category.VISUAL, acts = Acts.LOCATION),
        EffectSpec("FIREWORK", "폭죽", "피해 없는 폭죽을 터뜨린다", Category.VISUAL,
            listOf(typed("color", "색", ArgType.COLOR, "RED"), typed("fade", "사라지는 색", ArgType.COLOR, "WHITE"),
                choice("type", "모양", Names.FIREWORK_TYPES, "BALL"), int("power", "높이", "0"),
                bool("trail", "꼬리", "false"), bool("flicker", "반짝임", "false")), Acts.LOCATION),
        EffectSpec("PLAY_SOUND", "소리(본인)", "대상에게만 소리를 들려준다", Category.VISUAL,
            listOf(typed("sound", "소리", ArgType.SOUND), num("pitch", "높낮이", "1"), num("volume", "크기", "1"))),
        EffectSpec("PLAY_SOUND_OUTLOUD", "소리(주변)", "그 자리에서 소리를 낸다", Category.VISUAL,
            listOf(typed("sound", "소리", ArgType.SOUND), num("pitch", "높낮이", "1"), num("volume", "크기", "1")), Acts.LOCATION),

        // --- 메시지·명령 ---------------------------------------------------------------
        EffectSpec("MESSAGE", "채팅 메시지", "채팅으로 알린다", Category.MESSAGE, listOf(text("text", "내용"))),
        EffectSpec("ACTION_BAR", "액션바", "액션바로 알린다", Category.MESSAGE, listOf(text("text", "내용"))),
        EffectSpec("TITLE", "타이틀", "화면 가운데 큰 글씨", Category.MESSAGE, listOf(text("text", "내용"))),
        EffectSpec("SUBTITLE", "부제목", "화면 가운데 작은 글씨", Category.MESSAGE, listOf(text("text", "내용"))),
        EffectSpec("CONSOLE_COMMAND", "콘솔 명령", "콘솔로 명령을 실행한다(%player name% 치환)", Category.MESSAGE, listOf(text("command", "명령"))),
        EffectSpec("PLAYER_COMMAND", "플레이어 명령", "대상이 명령을 실행하게 한다", Category.MESSAGE, listOf(text("command", "명령"))),
        EffectSpec("PERMISSION", "권한 부여", "권한을 준다(지속형이면 벗을 때 회수)", Category.MESSAGE, listOf(text("node", "권한 노드")), reversible = true),

        // --- 경제·경험치 ---------------------------------------------------------------
        EffectSpec("ADD_MONEY", "돈 주기", "돈을 준다(Vault)", Category.ECONOMY, listOf(num("amount", "금액"))),
        EffectSpec("REMOVE_MONEY", "돈 빼기", "돈을 뺀다(Vault)", Category.ECONOMY, listOf(num("amount", "금액"))),
        EffectSpec("STEAL_MONEY", "돈 훔치기", "상대의 돈을 가져온다(Vault)", Category.ECONOMY, listOf(num("amount", "금액"))),
        EffectSpec("STEAL_EXP", "경험치 훔치기", "상대의 경험치를 가져온다", Category.ECONOMY, listOf(int("amount", "경험치"))),

        // --- 인첸트·영혼 ---------------------------------------------------------------
        EffectSpec("ADD_ENCHANT", "인첸트 붙이기", "발동한 아이템에 인첸트를 붙인다", Category.ENCHANT,
            listOf(typed("enchant", "인첸트", ArgType.ENCHANT), int("level", "레벨"))),
        EffectSpec("REMOVE_ENCHANT", "인첸트 떼기", "발동한 아이템에서 인첸트를 뗀다", Category.ENCHANT,
            listOf(typed("enchant", "인첸트", ArgType.ENCHANT))),
        EffectSpec("ADD_SOULS", "영혼 더하기", "발동한 아이템의 영혼을 더한다", Category.ENCHANT, listOf(int("amount", "영혼"))),
        EffectSpec("REMOVE_SOULS", "영혼 빼기", "발동한 아이템의 영혼을 뺀다", Category.ENCHANT, listOf(int("amount", "영혼"))),
        EffectSpec("DISABLE_ACTIVATION", "발동 봉인", "대상의 인첸트 발동을 막는다(이름 또는 ALL)", Category.ENCHANT,
            listOf(word("enchant", "인첸트(ALL = 전부)"), num("seconds", "초"))),
        EffectSpec("UNSEAL", "봉인 풀기", "대상에게 걸린 인첸트 봉인(발동 봉인)을 전부 푼다", Category.ENCHANT),

        // --- AE jar 에만 있고 문서에 없던 것 ---------------------------------------------
        EffectSpec("BROADCAST", "전체 공지", "서버 전체에 알린다", Category.MESSAGE, listOf(text("text", "내용"))),
        EffectSpec("BROADCAST_PERMISSION", "권한자 공지", "권한이 있는 사람에게만 알린다", Category.MESSAGE,
            listOf(word("node", "권한 노드"), text("text", "내용"))),
        EffectSpec("DROP_ITEM", "아이템 떨구기", "그 자리에 아이템을 떨군다(재질 또는 inmc:아이템)", Category.ITEM,
            listOf(typed("item", "아이템", ArgType.ITEM), int("amount", "개수", "1")), Acts.LOCATION),
        EffectSpec("TOTEM", "토템 연출", "불사의 토템 연출을 보여준다", Category.VISUAL),
        EffectSpec("PLAY_ENTITY", "엔티티 연출", "엔티티 연출(HURT·TOTEM_RESURRECT 등)을 재생한다", Category.VISUAL,
            listOf(word("effect", "연출 이름", "HURT"))),
        EffectSpec("RESET_COMBO", "콤보 초기화", "연속 타격 수를 0 으로", Category.SCRIPT),
        EffectSpec("MARK", "표식 남기기", "대상에 이름 붙은 표식을 몇 초 남긴다 - 조건에서 %victim has mark 이름% 으로 읽는다", Category.SCRIPT,
            listOf(word("name", "표식 이름"), num("seconds", "지속(초)"))),
        EffectSpec("STEAL_GUARD", "호위 빼앗기", "상대가 소환한 호위를 내 편으로 돌린다", Category.SPAWN),

        // --- 스크립트 ------------------------------------------------------------------
        EffectSpec("SET_VARIABLE", "변수 정하기", "변수에 값을 넣는다(%custom_이름% 으로 읽음)", Category.SCRIPT,
            listOf(word("name", "이름"), text("value", "값"))),
        EffectSpec("INVERT_VARIABLE", "변수 뒤집기", "참/거짓 변수를 뒤집는다", Category.SCRIPT, listOf(word("name", "이름"))),
        EffectSpec("WAIT", "기다리기", "다음 효과를 이만큼 늦춘다", Category.SCRIPT, listOf(int("ticks", TICKS))),
    )

    private val byName: Map<String, EffectSpec> = buildMap {
        for (spec in ALL) {
            put(spec.name, spec)
            for (alias in spec.aliases) put(alias, spec)
        }
    }

    fun of(name: String): EffectSpec? = byName[name.trim().uppercase()]

    /** [EffectLine.parse] 에 넘기는 칸 수 조회. */
    fun shape(name: String): Pair<Int, Boolean>? = of(name)?.let { it.args.size to it.restLast }
}
