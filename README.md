# inmc-enchants

커스텀 인첸트 · 인첸트 아이템 · 세트 효과 엔진. AdvancedEnchantments 의 문법과 기능을 따르되
이름·설명·수치는 전부 새로 썼습니다. 운영 안내는 `GUIDE.md`.

## 파일

| 파일 | 무엇 | GUI |
|---|---|---|
| `config.yml` | 칸 제한·부여서 확률·합치기·출처·영혼·로어 모양·금지 표시(`limitation`)·목록 변수 | 설정 |
| `enchantments.yml` | 인첸트 정의(AE 문법) | 인첸트 |
| `groups.yml` | 등급 | 등급 |
| `items.yml` | 플러그인 아이템의 겉모습 | — |
| `sets.yml` | 옛 방어구 세트·세트 무기 — **커스텀아이템이 있으면 처음 한 번 옮기고 `sets.yml.migrated`** | (커스텀아이템) |
| `mob-heads.yml` | 머리 떨구기 효과의 텍스처 | — |
| `messages.yml` | 메시지 | — |

화면에서 고치면 그 파일을 통째로 다시 씁니다. 파일 맨 위 설명은 코드가 다시 붙이므로 사라지지 않지만,
**중간에 손으로 단 주석은 사라집니다.** 읽지 못한 항목(오타 등)은 원문 그대로 되써 넣습니다.

## 인첸트 한 개

```yaml
lifesteal:
  display: "%group-color%흡혈"
  description: "공격할 때 확률로 체력을 빼앗는다."
  applies-to: "검·도끼"
  type: ATTACK;ATTACK_MOB          # 발동 조건. 여럿이면 ; 로
  group: ELITE
  applies: [ALL_SWORD, ALL_AXE]
  levels:
    1:
      chance: 8
      cooldown: 3
      conditions: ["%victim health% > 4 : %allow%"]
      effects: ["HEAL:2 @Self", "DAMAGE:2 @Victim"]
      data: { fishing.reel-power: 5 }   # 다른 플러그인이 읽는 값(낚싯대면 INMC 낚시가 더한다)
```

효과 줄: `효과:인자:인자 @대상 ~발동조건 <chance>수식</chance> <condition>식 : 결과</condition> location=…`.
`WAIT:틱` 을 만나면 뒤의 줄을 그만큼 늦춥니다. 변수·수식(`%level% * 2`, `<random number>1-5</random number>`)을
어느 인자에나 쓸 수 있습니다. 전체 목록은 관리 화면의 효과 고르기에 설명과 함께 나옵니다.

조건 결과: `%allow%`(돈다) · `%continue%`(다음 조건) · `%stop%`(안 돈다) · `%force%`(대기·확률 무시) ·
`%chance%+10`(확률 가감).

`%roll%` 은 **한 번 발동에 하나**인 0~100 주사위입니다. 여러 줄 중 하나만 돌게 할 때 줄마다 구간을 나눕니다:
`"FREEZE:40 @Victim <condition>%roll% < 34 : %allow%</condition>"` · `"… <condition>%roll% >= 34 && %roll% < 67 : %allow%</condition>"`.

레벨의 `whitelist`/`blacklist` 는 **캔 블록**을 거릅니다(엔진이 봅니다 — 효과가 따로 보지 않아도 됩니다).

### 인첸트 설정(`settings`)

```yaml
detonate:
  settings:
    required-enchants: ['blastmining:3']   # 이게 있어야 붙는다(진화)
    removed-enchants: ['blastmining']      # 붙으면 지운다
    custom-items-only: true                # 커스텀아이템에만(같은 재질의 평범한 곡괭이는 안 된다)
    draw-max-level: 1                      # 뽑기(인챈터·상자·사서·부여대)는 1레벨까지. 위는 연금술사 합치기로
    book-success: 5-15                     # 뽑힌 부여서의 성공률 범위
```

**진화 사슬 규칙**(배포본, `DescriptionContractTest` 가 지킨다): 확률 단계 → 확정 단계 → 상위 단계. 위 단계는 아래 단계의
**최대 레벨**을 요구하고, 붙으면 아래 단계를 지우고, 설명 끝에 "<이름> <레벨> 이/가 있어야 붙는다" 를 적습니다.
설명에 "확률" 이 없는 인첸트는 확률이 100% 입니다(연출 줄만 예외).

## 세트 (커스텀아이템으로 옮겼습니다)

세트는 **커스텀아이템이 관리합니다**(2026-09-25). 커스텀아이템 세트의 벌 수마다 **인첸트 효과**를 붙이면 이 플러그인의 엔진이
돌립니다 — 효과 줄·발동 조건·확률·대기·조건식이 인첸트와 같습니다.

- 고치는 곳: `/커스텀아이템 관리 → 아이템 세트 → 세트 → 몇 벌 효과 → 인첸트 효과`. 화면은 이 플러그인이 그립니다(발동 조건 목록 →
  인첸트 레벨과 같은 편집 화면), 붙을 때·떨어질 때 알림, 꺼진 월드.
- 커스텀아이템 `sets.yml` 에는 이렇게 적힙니다:
  ```yaml
  frost_giant:
    name: "<aqua><bold>서리 거인"
    bonuses:
      '4':
        enchant-effects:
          equipped: ["..."]
          events:
            ATTACK_MOB: { chance: 100, effects: ["INCREASE_DAMAGE:10"] }
            EFFECT_STATIC: { effects: ["POTION:FIRE_RESISTANCE:0"] }
  ```
- 발동 조건마다 재사용 대기가 따로 잡힙니다(`set:<세트>:<벌 수>:<발동조건>`). 세트 효과의 "아이템"은 흉갑입니다.
- **옛 `sets.yml` 옮기기**: 커스텀아이템이 켜져 있으면 처음 한 번. 네 부위·무기는 커스텀아이템 아이템이 되고(인첸트 범위 `1-3` 은
  가장 높은 값), 세트 효과는 **4벌**, 그 세트가 필요한 무기의 효과는 **5벌**(네 부위 + 든 무기), 세트가 필요 없는 무기는 제 이름의
  세트 **1벌**에 붙습니다. AE 의 세트 파일 모양(`items:` · `settings.equipped` · `GOLD`/`CHAIN`)도 읽힙니다.
- 세트 아이템 지급은 `/커스텀아이템 세트지급 <플레이어> <세트>`.

## 다른 플러그인과

- **커스텀아이템** — core `CustomEnchantHook` 공급처. 아이템 편집 화면에서 인첸트를 고릅니다.
- **낚시** — 인첸트 레벨의 `data` 에 `fishing.*` 를 적으면 그 인첸트가 붙은 낚싯대에 더해집니다.
- **PlaceholderAPI** — 조건의 `%…%` 를 풀고, `%inmcenchant_…%` 를 냅니다(`GUIDE.md`).
- **Vault** — 돈 효과·돈 가격. 없으면 그 효과·가격만 꺼집니다.
