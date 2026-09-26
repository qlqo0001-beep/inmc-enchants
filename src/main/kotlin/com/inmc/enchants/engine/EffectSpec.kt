package com.inmc.enchants.engine

/**
 * 효과 하나의 **모양** — 이름, 인자, 무엇에 닿는지. 실행 코드는 [com.inmc.enchants.effect] 에 따로 있다.
 *
 * 모양을 실행과 떼어 둔 것은 세 곳이 이것을 읽기 때문이다: 효과 줄 파서(인자 몇 개로 자를지),
 * 적재 검사(숫자 칸에 글자가 들었는지), GUI 효과 빌더(칸마다 어떤 편집기를 띄울지).
 * **셋 다 서버 없이 돈다** — 서버를 요구하면 테스트할 수 없다.
 */
data class EffectSpec(
    val name: String,
    val label: String,
    val description: String,
    val category: Category,
    val args: List<ArgSpec> = emptyList(),
    val acts: Acts = Acts.ENTITY,
    /** 이 효과가 의미 있는 발동 조건. 비어 있으면 어디서나. 다른 데서 쓰면 경고만 한다. */
    val triggers: Set<Trigger> = emptySet(),
    val aliases: List<String> = emptyList(),
    /**
     * 지속형(들기·착용·웅크리기·달리기)을 벗을 때 되돌리는가. 아니면 벗을 때 **아무것도 안 한다** —
     * 안 그러면 "웅크리면 튕겨 오른다"가 웅크림을 풀 때 한 번 더 튕긴다.
     */
    val reversible: Boolean = false,
) {

    /** 필수 인자 수. 뒤의 선택 인자는 빠져도 된다. */
    val requiredArgs: Int get() = args.count { !it.optional }

    /** 마지막 인자가 남은 글자 전부를 먹는가(메시지·명령어처럼 `:` 가 들어갈 수 있는 것). */
    val restLast: Boolean get() = args.lastOrNull()?.type == ArgType.TEXT

    enum class Acts(val label: String) {
        ENTITY("엔티티"),
        BLOCK("블록"),
        LOCATION("위치"),

        /** 대상이 아니라 **지금 일어나는 사건**을 바꾼다(피해량·취소·드랍). 대상 표시는 무시된다. */
        EVENT("사건"),
    }

    enum class Category(val label: String) {
        DAMAGE("피해"), HEALTH("체력·상태"), POTION("물약"), MOVEMENT("이동"),
        BLOCK("블록·채굴"), ITEM("아이템·장비"), SPAWN("소환·투사체"), VISUAL("연출"),
        MESSAGE("메시지·명령"), ECONOMY("경제·경험치"), ENCHANT("인첸트·영혼"), SCRIPT("스크립트"),
    }
}

/** 효과 인자 한 칸. */
data class ArgSpec(
    val key: String,
    val label: String,
    val type: ArgType,
    val optional: Boolean = false,
    val default: String = "",
    /** [ArgType.CHOICE] 의 보기. */
    val choices: List<String> = emptyList(),
)

enum class ArgType(val label: String) {
    NUMBER("숫자"), INT("정수"), BOOL("참/거짓"),

    /** 남은 글자 전부. 항상 마지막 칸이다. */
    TEXT("글자"),
    WORD("낱말"),
    POTION("물약 효과"), SOUND("소리"), PARTICLE("입자"), MATERIAL("재질"),
    ENTITY_TYPE("엔티티 종류"), CHOICE("보기 중 하나"), ENCHANT("커스텀 인첸트"),
    COLOR("색"), ITEM("아이템 참조"),
}
