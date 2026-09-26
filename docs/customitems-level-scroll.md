# 커스텀아이템에서 강화 스크롤 다루기

> inmc-enchants 쪽에서 열어 둔 길과, 커스텀아이템(inmc-customitems)에 "강화 스크롤" 항목이 제대로 나타나게 하려면 커스텀아이템 쪽에서
> 할 일을 적은 글입니다. 인첸트 쪽 작업은 끝났습니다(2026-09-26).

## 지금 되는 것

강화 스크롤은 인첸트의 **아이템 역할**(core `ItemRoles`)로 커스텀아이템에 나옵니다. 새 역할이 아니라 기존 역할의 새 종류입니다.

| 항목 | 값 |
|---|---|
| 역할 key | `enchants.item` (owner `인첸트`, 이름 "인첸트 아이템") |
| 종류 칸 | `kind` = `level-scroll` (보이는 이름 "강화 스크롤") |
| 칸 `enchant` | `Choice` — 강화할 인첸트. 값은 우리 인첸트 id(`lifesteal`) 또는 바닐라 id(`minecraft:sharpness`). 보기는 인첸트 약 370개 + 바닐라 약 40개 |
| 칸 `success` | `Number` 0~100, 기본 50 — 강화 성공 확률(%) |
| 칸 `downgrade` | `Number` 0~100, 기본 0 — 실패했을 때 한 레벨 떨어질 확률(%) |
| 만드는 쪽 | 인첸트(`Role.factory`). 커스텀아이템이 지급할 때 `ItemRegistry.create` → factory 가 **진짜 스크롤**을 돌려준다 |
| 겉모습 | 커스텀아이템 아이템의 재질·모델·모델 번호. **인첸트마다 따로** — 같은 `enchant` 값의 아이템 겉모습을 쓴다 |
| 이름·설명 | 인첸트의 `items.yml` → `level-scroll` (`{enchant}` `{success}` `{downgrade}` `{max-level}`) |

세 칸(`enchant`·`success`·`downgrade`)은 `kind` 가 `level-scroll` 일 때만 보입니다(`Field.visible`).
`enchant` 가 비어 있으면 factory 가 null 을 돌려줍니다 — 그 아이템은 아직 만들 수 없는 상태입니다.

### 인첸트 쪽에서 등록하기 (지금 바로 쓰는 길)

`/인첸트 관리 → 강화 스크롤 → 커스텀아이템에 올리기` — 인첸트 → 성공 확률 → 하락 확률을 고르면
`ItemRoles.adopt(스크롤, "강화스크롤_<인첸트 id>")` → `ItemRoles.assign(ref, "enchants.item", {kind, enchant, success, downgrade})` 로
커스텀아이템 아이템이 하나 생깁니다. 상점은 이 아이템을 팝니다(`/커스텀아이템 지급 <플레이어> <개수> <이름>` 도 진짜 스크롤을 줍니다).

## 커스텀아이템 쪽에서 할 일

### 1. 긴 `Choice` 를 고르기 화면으로 (꼭 필요)

지금 `RoleEditMenu` 는 `Choice` 를 **좌/우클릭으로 하나씩 돌리고, 보기 전체를 설명 줄에 늘어놓습니다**(`Editors.cycle` · `Editors.optionList`).
`enchant` 칸은 보기가 400개가 넘어서 이대로는 고를 수 없고, 아이콘 설명도 400줄이 됩니다.

제안: 보기가 많은 `Choice`(예: 20개 넘음)는 누르면 **페이지 고르기 화면**을 연다.

- 한 칸 = 보기 하나(이름은 `second`), 45칸씩 페이지, 이름으로 찾기(채팅) 있으면 좋음
- 누르면 `put(field.key, option.first)` 후 `RoleEditMenu` 로 돌아온다
- 아이콘의 설명 줄에는 전체 목록 대신 **지금 값 하나**만
- core 에 두면(예: `Editors.pickPaged`) 다른 플러그인의 긴 보기에도 쓴다. 역할이 보기 수를 알려 줄 필요는 없다 — 화면이 `options().size` 로 정한다

보기의 글자(`second`)는 `흡혈 (lifesteal)` · `바닐라 sharpness` 모양입니다. 바닐라 이름을 한국어로 보이려면 인첸트 쪽에서
`<lang:enchantment.minecraft.sharpness>` 같은 MiniMessage 를 넘길 수도 있습니다 — 커스텀아이템이 보기 이름을 MiniMessage 로 그린다면
알려 주세요. 지금은 평문이라 id 를 씁니다.

### 2. 목록·아이콘에서 알아보기 쉽게 (있으면 좋음)

- 역할 목록의 설명에 `강화할 인첸트: <이름>` · `성공 50% · 하락 10%` 가 나오면 같은 모양 스크롤 여럿을 구분하기 쉽습니다
  (지금도 `RoleListMenu` 가 보이는 칸을 `label: 값` 으로 늘어놓으므로 1번이 되면 그대로 나옵니다)
- 미리보기(`ItemRegistry.preview`)는 factory 를 거치지 않아 **이름·설명이 인첸트의 것이 아닙니다.** 역할에 factory 가 있으면 미리보기도
  `create(id, 1)` 로 만들면 실제로 받는 것과 같아집니다

### 3. 하지 말 것

- 스크롤 아이템에 커스텀아이템의 이름·설명·능력치를 **덧씌우지 않는다** — 스크롤의 뜻은 인첸트가 적은 PDC(`inmc_enchant:item`·
  `scroll_enchant`·`amount`·`scroll_downgrade`)에 있고, 설명은 인첸트의 `items.yml` 이 확률을 보여 줍니다
- 칸 이름(`kind`·`enchant`·`success`·`downgrade`)과 값 `level-scroll` 을 바꾸지 않는다 — 이미 등록된 아이템이 읽지 못하게 됩니다

## 확인하는 법

1. `/인첸트 관리 → 강화 스크롤 → 커스텀아이템에 올리기` 로 하나 만들고 커스텀아이템 목록에 `강화스크롤_…` 이 생겼는지
2. 그 아이템의 연동 역할 → 인첸트 · 인첸트 아이템 → 종류가 "강화 스크롤", 인첸트·성공·하락이 고른 값인지
3. `/커스텀아이템 지급 <나> 3 <그 아이템>` → 받은 스크롤 셋이 한 칸에 겹치고, 검 위에 끌어다 놓으면 인첸트가 오르는지
4. 커스텀아이템에서 그 아이템의 모델을 바꾸고 다시 지급 → 새 스크롤이 새 모델인지(다른 인첸트의 스크롤은 그대로인지)
