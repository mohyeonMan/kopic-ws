# ko-pic GE Domain Model v0.1

이 문서는 `Game Rules`를 안정적으로 구현하기 위한 MVP 기준 도메인 구조 초안이다.

핵심 원칙은 아래와 같다.

- `Room`은 방 자체와 다음 게임 시작 전 설정을 가진다.
- `Game`은 실제로 시작된 한 판의 고정 규칙과 진행 상태를 가진다.
- `Round`와 `Turn`은 전체 히스토리보다 현재 진행 상태 표현에 집중한다.
- `Room`, `Game`, `Round`, `Turn`은 mailbox 안에서만 수정되는 mutable authoritative state로 본다.

---

## 1. Room

방 자체를 표현하는 aggregate root다.

- `roomId`
- `roomCode`
- `roomType`
  - `RANDOM | PRIVATE`
- `participants: Map<UserId, Participant>`
- `state`
  - `LOBBY | RUNNING | MIGRATING | CLOSED`
- `createdAt`
- `hostUserId`
- `settings: GameSettings`
  - 다음 게임 시작 전 설정
- `currentGame: Game?`
- `capacity`
- `ownerEngineId`
- `version`

### Room 주석

- `Room.settings`와 `Game.settings`는 둘 다 유지한다.
- `Room.settings`는 lobby/waiting 상태의 다음 게임 설정이다.
- `Game.settings`는 게임 시작 시 복사된 현재 게임 전용 설정이다.
- `Room`은 mutable authoritative state다.
- 단, runner/mailbox 밖에서 직접 수정하면 안 된다.

---

## 2. Participant

방 참가자 정보다.

- `userId`
- `name`
- `status`
- `wsNodeId`

### Participant 주석

- `status`, `wsNodeId`는 MVP 규칙만 보면 과할 수 있다.
- 하지만 참가자 추적/세션 연계 가능성을 고려해 v0.1에서는 유지한다.

---

## 3. RoomState

- `LOBBY`
- `RUNNING`
- `MIGRATING`
- `CLOSED`

### RoomState 주석

- `RUNNING`이면 `currentGame != null`
- `RANDOM`이면 `hostUserId == null`
- `PRIVATE`이면 `roomCode != null`

---

## 4. GameSettings

게임 설정 값 객체다.

- `roundCount`
- `drawSec`
- `wordChoiceSec`
- `wordChoiceCount`
- `endMode`

### GameSettings 주석

- `hintEnabled`, `hintInterval`은 v0.1에서 넣지 않는다.
- 현재 `Game Rules` 범위 밖이기 때문이다.

---

## 5. Game

실제로 시작된 한 판을 표현한다.

- `gameId`
- `roomId`
- `status`
  - `RUNNING | RESULT_VIEW | ENDED`
- `settings: GameSettings`
- `scores: ScoreBoard`
- `currentRound: Round`
- `startedAt`
- `endedAt`
- `resultViewUntil`

### Game 주석

- `READY`는 두지 않는다.
- 아직 게임 시작 전 상태는 `Room.currentGame == null`로 표현한다.
- `RESULT_VIEW`를 둬서 게임 종료 후 결과 화면 유지 구간을 직접 표현한다.
- `Game`도 mutable authoritative state다.
- 다만 mailbox 안에서만 수정한다.

---

## 6. Round

현재 라운드 진행 상태를 표현한다.

- `roundNo`
- `state`
  - `RUNNING | ENDED`
- `turnCursor`
- `currentTurn: Turn`
- `startedAt`
- `endedAt`

### Round 주석

- `List<Round>`는 두지 않는다.
- 현재 라운드 상태와 전이에 집중한다.
- `READY` 상태는 두지 않는다.
- 라운드 시작 전 대기 개념은 scheduler/transition으로 처리한다.

---

## 7. Turn

현재 턴 상태를 표현한다.

- `turnId`
- `drawerUserId`
- `secretWord`
- `wordChoices: List<String>`
- `state`
  - `WORD_CHOICE | DRAWING | ENDED`
- `correctUserIds: Set<UserId>`
- `endReason`
  - `FIRST_CORRECT | ALL_CORRECT | TIMEOUT | DRAWER_LEFT`
- `canvas: CanvasState`
- `startedAt`
- `endsAt`
- `endedAt`

### Turn 주석

- `wordChoices`는 `WORD_CHOICES -> WORD_CHOICE -> TURN_STARTED` 흐름 때문에 필요하다.
- `correctUserIds`는 중복 정답 처리 방지를 위해 필요하다.
- `endReason`은 follow-up 전이와 이벤트 발행 판단에 필요하다.
- `startedAt/endsAt`는 현재 턴 단계의 deadline이다.
- `Turn`도 mutable authoritative state다.

---

## 8. CanvasState

현재 턴의 그림 상태다.

- `strokes: List<Stroke>`

### CanvasState 주석

- 서버는 현재 턴의 stroke만 유지한다.
- 따라서 캔버스는 `Turn` 밑에 둔다.

---

## 9. Stroke

드로잉 입력 값 객체다.

- `strokeId`
- `tool`
  - `PEN | ERASER`
- `colorIndex`
  - `0..19`
- `size`
  - `1..20`
- `points`
  - normalized coordinate
  - 최대 64개

### Stroke 주석

- `Stroke`는 값 객체로 유지한다.
- 드로잉 입력 제약은 여기서 최대한 빨리 검증한다.

---

## 10. mutable authoritative state 규칙

v0.1 기준으로 아래를 전제로 한다.

- `Room`, `Game`, `Round`, `Turn`은 mutable state entity다.
- authoritative current state는 `RoomSlot` 안에 있다.
- runner가 mailbox를 drain하면서 이 상태를 직접 수정한다.
- runner 밖에서는 이 상태를 직접 수정하지 않는다.
- 권한 검증과 상태 기반 검증도 현재 room 상태를 기준으로 mailbox 안에서 수행한다.

즉 thread-safety의 핵심은 immutable snapshot 교체가 아니라 room별 단일 실행 보장이다.

---

## 11. 보강 불변식

- `Room.state == RUNNING`이면 `Room.currentGame != null`
- 게임 시작 시 `Room.settings`를 복사해 `Game.settings`로 고정한다
- 게임 진행 중에는 반드시 `Game.settings`만 참조한다
- `Turn.state == WORD_CHOICE`이면 `wordChoices`가 비어 있으면 안 된다
- `Turn.state == DRAWING`이면 `secretWord`가 확정돼 있어야 한다
- `Turn.state == ENDED`이면 `endReason`과 `endedAt`이 있어야 한다
- `Turn.correctUserIds`에는 `drawerUserId`가 들어가면 안 된다

---

## 12. v0.1 요약

이 구조는 아래 선택을 명시적으로 한 버전이다.

- `Room.settings`와 `Game.settings`를 둘 다 유지
- participant의 `status`, `wsNodeId`도 유지
- 현재 진행 상태 중심 모델 채택
- 결과 화면 유지 구간을 `Game.status`로 직접 표현
- `Room/Game/Round/Turn`을 mailbox 안에서만 수정되는 mutable authoritative state로 간주

즉 v0.1은 "현재 상태 중심 + room별 순차 실행 전제" 위에서 MVP 규칙을 안정적으로 구현하기 위한 도메인 구조다.
