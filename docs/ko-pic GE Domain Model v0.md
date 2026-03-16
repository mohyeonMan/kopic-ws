# ko-pic GE Domain Model v0 (Draft)

## 1. 목적

이 문서는 `kopic-ge` MVP 구현을 위한 최소 도메인 모델 초안을 정의한다.

- 목표: 룰/프로토콜을 일관되게 처리할 수 있는 최소 상태 모델 확보
- 비목표: 이벤트 소싱, 영구 저장소 스키마 확정, in-game migration

---

## 2. 모델 경계

GE는 아래 상태를 authoritative하게 소유한다.

- room lifecycle (`LOBBY`, `RUNNING`, `MIGRATING`, `CLOSED`)
- participant membership
- game/turn 진행 상태
- score
- current turn canvas strokes

GE가 소유하지 않는 것:

- WS 세션 연결 객체
- Lobby entry orchestration 상태
- 클라이언트 pre-entry 요청 상태

---

## 3. Aggregate 구성

### 3.1 `RoomAggregate`

`RoomAggregate`가 room 단위 consistency boundary다.

핵심 필드:

- `roomId: RoomId`
- `roomType: RoomType` (`RANDOM`, `PRIVATE`)
- `roomCode: RoomCode?` (private only)
- `ownerEngineId: EngineId`
- `state: RoomState` (`LOBBY`, `RUNNING`, `MIGRATING`, `CLOSED`)
- `participants: Map<UserId, Participant>`
- `hostUserId: UserId?` (private only)
- `settings: GameSettings`
- `game: GameRuntime?`
- `version: long` (낙관적 동시성/스냅샷 버전)

불변식:

- `RUNNING`이면 `game != null`
- `RANDOM`이면 `hostUserId == null`
- `PRIVATE`면 `roomCode != null`
- `MIGRATING`이면 새 `GAME_START_REQUEST` 거절

### 3.2 `GameRuntime`

- `gameId: GameId`
- `status: GameStatus` (`LOBBY`, `RUNNING`, `ENDED`)
- `round: RoundRuntime`
- `turn: int`
- `turnState: TurnRuntime?`
- `scores: ScoreBoard`
- `correctUsersInTurn: Set<UserId>`
- `resultViewUntil: Instant?`

### 3.3 `RoundRuntime`

- `roundNo: int` (1..`settings.roundCount`)
- `state: RoundState` (`READY`, `RUNNING`, `ENDED`)
- `startedAt: Instant`
- `endedAt: Instant?`
- `turnCursor: int`
### 3.4 `TurnRuntime`

- `turnId: TurnId`
- `drawerUserId: UserId`
- `secretWord: String`
- `startedAt: Instant`
- `endsAt: Instant`
- `canvas: CanvasState`
- `phase: TurnPhase` (`WORD_CHOICE`, `DRAWING`, `ENDED`)

### 3.5 `CanvasState`

- `strokes: List<Stroke>`
- 턴 시작 시 항상 빈 리스트로 초기화

### 3.6 `ScoreBoard`

- `scores: Map<UserId, int>`
- MVP 점수 규칙
  - guesser 첫 정답: `+1`
  - drawer 정답자 1명당: `+1`

---

## 4. Value Object

- `RoomId`, `GameId`, `TurnId`, `UserId`, `EngineId`
- `RoomCode` (6자, 대문자+숫자 권장)
- `GameSettings`
  - `roundCount: 3..10`
  - `drawSec: 20..60`
  - `wordChoiceSec: 10` (고정)
  - `wordChoiceCount: 3..5`
  - `endMode: FIRST_CORRECT | TIME_OR_ALL_CORRECT`
- `Stroke`
  - `strokeId`
  - `tool: PEN | ERASER`
  - `colorIndex: 0..19`
  - `size: 1..20`
  - `points: max 64`

---

## 5. Command 모델 (입력)

`EngineEnvelopeRequest`에서 파싱된 도메인 명령.

- `JoinRoomCommand(roomId, userId, name, nodeId)`
- `LeaveRoomCommand(roomId, userId, nodeId)`
- `StartGameCommand(roomId, userId, settings, rid)`
- `DrawStrokeCommand(roomId, userId, turnId, stroke, rid)`
- `DrawClearCommand(roomId, userId, turnId, rid)`
- `GuessSubmitCommand(roomId, userId, text, rid)`
- `WordChoiceCommand(roomId, userId, choiceIndex, rid)`
- `SnapshotRequestCommand(roomId, userId, rid)`

---

## 6. Domain Event 모델 (출력)

클라이언트 이벤트 코드 기준으로 도메인 이벤트를 모델링한다.

- `RoomStateEmitted` -> `301`
- `GameStarted` -> `302`
- `RoundStarted` -> `303`
- `TurnStarted` -> `304`
- `TurnEnded` -> `305`
- `RoundEnded` -> `306`
- `GameEnded` -> `307`
- `CanvasStrokeBroadcasted` -> `401`
- `CanvasCleared` -> `402`
- `GuessMessageBroadcasted` -> `403`
- `GuessCorrectBroadcasted` -> `404`
- `ScoreUpdated` -> `405`
- `WordChoicesIssued` -> `406`
- `GameSnapshotBuilt` -> `408`

에러 이벤트:

- `EngineErrorOccurred(code, message, requestEvent, rid)` -> `3 ERROR`

---

## 7. 상태 전이 규칙(최소)

### Room 상태

- `LOBBY -> RUNNING` : 게임 시작 성공
- `RUNNING -> LOBBY` : 게임 종료 + 결과 화면 종료
- `LOBBY -> MIGRATING` : scale-in handoff 시작
- `MIGRATING -> LOBBY` : handoff 실패/롤백
- `MIGRATING -> CLOSED` : handoff 성공 후 source 제거
- `* -> CLOSED` : room 제거 정책 충족

### Round 상태

- `READY -> RUNNING` : `ROUND_STARTED(303)` 발행
- `RUNNING -> ENDED` : round의 마지막 턴 종료
- `ENDED -> READY` : 다음 round 준비(게임 미종료 시)

### Turn 상태

- `WORD_CHOICE -> DRAWING` : drawer 선택 또는 timeout 랜덤 선택
- `DRAWING -> ENDED` : FIRST_CORRECT / ALL_CORRECT / TIMEOUT / DRAWER_LEFT

---

## 8. 게임 룰 불변식(보강)

아래 항목은 `Game Rules`/`Protocol` 기준으로 GE가 반드시 강제해야 하는 규칙이다.

1. 입장/중간입장
- join 직후 즉시 `player`로 편입한다.
- MVP에서 spectator 역할은 사용하지 않는다.
- 닉네임 중복은 허용한다.
- 중간입장자는 현재 턴에서 `guess` 가능하다.
- 중간입장자는 현재 턴의 `drawer`가 될 수 없다.
- join 성공 직후 `301 + 408` 발행을 기본 흐름으로 한다.

2. 시작/설정
- `PRIVATE` room의 `GAME_START_REQUEST`는 host만 허용한다.
- `RANDOM` room은 host 개념 없이 GE 정책으로 auto-start 가능하다.
- 설정값 검증은 `roundCount/drawSec/wordChoiceCount/endMode`에 대해 수행한다.
- `wordChoiceSec`는 10초 고정값으로 처리한다.
- room capacity 정책
  - `RANDOM`: 8명 고정
  - `PRIVATE`: room 생성 시 capacity 입력값 사용

3. 턴/라운드/게임 전환 지연
- 턴 종료 후 다음 턴 시작: 3초 지연
- 라운드 종료 후 다음 라운드 시작: 4초 지연
- 게임 종료 후 결과 화면 유지: 8초

4. drawer/권한 규칙
- `DRAW_STROKE`, `DRAW_CLEAR`, `WORD_CHOICE`는 drawer만 허용한다.
- drawer 이탈 시 현재 턴을 `DRAWER_LEFT`로 즉시 종료한다.

5. guess 판정 규칙
- 정답 비교는 lowercase -> 공백 제거 -> `.,!?` 제거 -> 완전일치 순서로 정규화한다.
- 동일 턴에서 이미 정답 처리된 유저는 추가 정답 판정 대상에서 제외한다.

6. 메시지 가시성 규칙
- 미정답자 메시지: 전체 공개
- 정답자 메시지: 정답자 + drawer 공개
- drawer 메시지: 정답자 + drawer 공개
- 정답 텍스트는 미정답자에게 노출하지 않는다.

7. 점수/턴 종료 규칙
- `FIRST_CORRECT`: 첫 정답자 발생 즉시 턴 종료
- `TIME_OR_ALL_CORRECT`: 제한시간 종료 또는 전원 정답 시 턴 종료
- 점수는 MVP 규칙(guesser +1, drawer는 정답자 수만큼 +1)을 따른다.
- `FIRST_CORRECT`에서는 첫 정답자와 drawer만 점수 획득한다.

8. 드로잉 제한
- `DRAW_STROKE.points` 최대 64
- 좌표는 0.0..1.0, 소수점 최대 5자리
- `colorIndex` 0..19, `size` 1..20

9. 연결 정책
- 연결 끊김 시 즉시 leave 처리한다(재접속 유예 없음, MVP).

10. rate-limit 처리
- `DRAW_STROKE`: 사용자당 초당 최대 20회, 메시지 최대 8KB
- `GUESS_SUBMIT`: 사용자당 초당 최대 5회
- 위반 시 `910 RATE_LIMITED`를 반환한다.

11. random auto-start 규칙
- auto-start 트리거는 대기시간이 아니라 참여 인원 수로만 판단한다.
- 기본값은 `2명 이상` 입장 시 시작 가능 상태로 전이한다.
- 실제 시작 시점은 actor 큐 순서 안에서 한 번만 결정한다.

12. dedup TTL 정책
- draw/guess command dedup TTL은 `30초`를 기본값으로 사용한다.
- 운영 중 중복률이 높으면 `60초`까지 상향 가능하다.

## 9. 에러코드 매핑 초안

프로토콜 코드 기준으로 command handling에서 사용.

- `900 INVALID_EVENT`
- `901 INVALID_PAYLOAD`
- `902 ROOM_NOT_FOUND`
- `903 ROOM_FULL`
- `904 NOT_ROOM_HOST`
- `905 GAME_ALREADY_STARTED`
- `906 NOT_DRAWER`
- `907 GAME_NOT_RUNNING`
- `908 UNAUTHORIZED`
- `909 INTERNAL_ERROR`
- `910 RATE_LIMITED`

추가 내부코드(권장):

- `911 NOT_OWNER` (stale routing 복구 유도)
- `912 ROOM_MIGRATING` (재조회/백오프 유도)

---

## 10. 저장/인덱스 책임

GE가 갱신하는 Redis 키:

- `room:{roomId}:owner = engineId`
- `room:code:{roomCode} = roomId` (private)
- `rooms:random:joinable` (`ZSET`)
- `engine:presence:{engineId}`

원칙:

- participant 변경 성공 후에만 joinable 인덱스 반영
- migration 중 random room은 joinable에서 제외
- owner pointer는 migration prepare 성공 후 CAS 갱신

---

## 11. 코드 스켈레톤 예시

```java
public final class RoomAggregate {
    private RoomId roomId;
    private RoomType roomType;
    private RoomState state;
    private Map<UserId, Participant> participants;
    private GameSettings settings;
    private GameRuntime game;
    private long version;

    public List<DomainEvent> handle(StartGameCommand cmd) { /* ... */ }
    public List<DomainEvent> handle(DrawStrokeCommand cmd) { /* ... */ }
    public List<DomainEvent> handle(GuessSubmitCommand cmd) { /* ... */ }
}
```

```java
public enum RoomState {
    LOBBY,
    RUNNING,
    MIGRATING,
    CLOSED
}
```

---

## 12. 다음 설계 입력값

다음 단계(상세 설계) 전에 확정할 항목:

1. timer 구현 방식 (wheel scheduler vs scheduled executor)
2. private room capacity 허용 범위(최소/최대)
3. dedup key 구성(`roomId+userId+rid` 외 보조 키 필요 여부)
4. migration payload를 코드 공용 DTO로 고정할지 여부
