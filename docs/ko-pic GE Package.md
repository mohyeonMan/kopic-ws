# GE Package

스프링부트 기반 `kopic-ge`는 "게임 룰과 room runtime state의 authoritative owner"로 두는 것이 맞다.
핵심 원칙은 `GE`가 control plane이 아니라 runtime plane이라는 점을 유지하는 것이다.

즉 `controller/service/repository` 식 범용 분류보다, GE가 맡는 runtime 책임 경계대로 패키지를 나누는 편이 유지보수에 유리하다.

`GE`는 아래 역할을 책임진다.

- room actor 기반 상태 소유
- 룰 검증(권한, 상태 전이, semantic validation)
- 턴/라운드/게임 타이머 진행
- 점수/캔버스/정답 판정
- snapshot(`408`) 생성
- route/joinable/presence 갱신
- scale-in migration source/target 실행

반대로 아래는 `GE` 책임이 아니다.

- client 연결/세션 관리 (WS)
- room entry orchestration (Lobby)
- owner GE 선택 정책 (Lobby)
- client-facing pre-entry API (Lobby)

## 추천 패키지 구조

```text
io.jhpark.kopic.ge
  KopicGeApplication

  common
    error
    id
    time
    json

  config
    ClockConfig
    SchedulerConfig
    RedisConfig
    RpcConfig
    MessagingConfig

  room
    domain
      Room
      RoomState
      RoomType
      Participant
      GameSettings
    app
      RoomLifecycleService
      RoomJoinService
      RoomLeaveService
      RoomRegistry
    infra
      InMemoryRoomRegistry

  game
    domain
      GameState
      RoundState
      TurnState
      TurnEndReason
      WordChoice
      ScoreBoard
      Stroke
    app
      GameStartService
      RoundOrchestrator
      DrawCommandService
      GuessCommandService
      TurnOrchestrator
      SnapshotService

  command
    dto
      EngineEnvelopeRequest
      SessionLifecycleEvent
      EngineAck
    app
      EngineCommandDispatcher
      CommandValidator

  outbound
    dto
      EngineOutboundEvent
      TargetedDelivery
    app
      OutboundPublisher
      AudienceResolver
    infra
      RabbitOutboundPublisher

  directory
    app
      RoomRoutingUpdater
      RoomCodeIndexUpdater
      RandomJoinableIndexUpdater
      EnginePresencePublisher
    infra
      RedisRoomRoutingStore
      RedisRoomCodeStore
      RedisJoinableStore
      RedisPresenceStore

  migration
    domain
      MigrationState
      MigrationSnapshot
      MigrationResult
    app
      MigrationSourceService
      MigrationTargetService
      MigrationOwnerPointerUpdater
    infra
      InternalMigrationClient

  api
    internal
      EngineCommandController
      MigrationController
      HealthController

  observability
    metric
    tracing
    audit
```

## 핵심 설계 원칙

### 1. `room actor 1개 = room 1개`

- 동일 `roomId`의 모든 명령은 동일 actor(mailbox)로 직렬 처리한다.
- actor별 전용 스레드는 두지 않고, shared worker pool + running flag(CAS)로 순차 실행 보장.
- GE 내부에서 순차성을 보장하고, WS/네트워크는 at-most-once 전제에서 동작한다.

### 1.1 round 진행은 독립 상태로 관리

- `GameState` 안에서 `round`를 단순 숫자로만 두지 않고 `RoundState`로 명시한다.
- `RoundOrchestrator`는 `ROUND_STARTED(303)`/`ROUND_ENDED(306)` 발행과 다음 round 전환(4초 지연)을 책임진다.
- 턴 종료 집합이 round 종료 조건을 만족하면 round 상태를 종료시키고 다음 round 또는 game 종료로 전이한다.

### 2. 검증 책임은 GE가 최종 소유

- WS는 envelope/세션/전역 rate-limit까지만 검증.
- GE는 이벤트별 payload 의미 검증과 상태 전이 검증을 담당.
- 예: `DRAW_STROKE` drawer 검증, `WORD_CHOICE` 제한시간 검증, `GAME_START_REQUEST` 상태 검증.

### 3. outbound recipient 계산은 GE 책임

- 누가 어떤 이벤트를 받아야 하는지는 GE가 계산한다.
- WS는 전달만 담당하고 audience rule을 알지 않는다.
- 예: 정답자/drawer 전용 메시지, 미정답자 공개 메시지.

### 4. Redis key lifecycle owner는 GE

- `room:{roomId}:owner`
- `room:code:{roomCode}`
- `rooms:random:joinable`

위 키들의 생성/삭제/갱신은 room runtime 사실을 알고 있는 GE가 수행한다.

### 5. 확정된 운영 기본값

- room capacity
  - `RANDOM`: 8명 고정
  - `PRIVATE`: room 생성 요청 시 전달받은 capacity 사용
- random auto-start는 인원 기준으로만 판단한다(기본 2명 이상).
- command dedup TTL 기본값은 30초, 필요 시 60초까지 상향한다.
- migration payload는 별도 문서 스키마보다 source/target 공용 DTO 계약을 우선한다.

## 내부 API 경계(권장)

GE로 들어오는 입력은 두 종류로 고정한다.

1. runtime command (WS -> GE)
- `EngineEnvelopeRequest(roomId, userId, occurredAt, envelope)`

2. session lifecycle (WS -> GE)
- `SessionLifecycleEvent(roomId, userId, occurredAt, type=CONNECTED|DISCONNECTED)`

GE에서 나가는 출력도 두 종류로 분리한다.

1. RPC ack/error (WS로 즉시 응답)
2. async outbound event (`301~408`, `3 ERROR`) publish

관련 계약 문서:

- `docs/ko-pic GE Internal API Contract v0.md` (WS <-> GE)
- `docs/ko-pic Lobby-GE Internal API Contract v0.md` (Lobby <-> GE)

## 피해야 할 구조

- WS처럼 transport concern이 GE 내부에 섞이는 구조
- 하나의 giant service가 join/draw/guess/timer/migration을 모두 처리하는 구조
- actor 우회 직접 상태 수정 (thread-safe 보장 붕괴)
- Lobby가 owner인 것처럼 route/joinable을 GE 밖에서 갱신하는 구조
- migration에서 target prepare 전에 owner pointer를 먼저 갱신하는 구조

## 구현 우선순위 제안

### Phase 1

- room registry + actor mailbox
- join/leave, game start, draw/guess/word-choice
- 301/302/304/305/401/403/404/405/406/408 발행
- route/joinable/presence 최소 갱신

### Phase 2

- random/private room lifecycle 정책 고도화
- 에러코드 정밀화 및 rate-limit 연동
- metrics/tracing 추가

### Phase 3

- scale-in migration source/target 구현
- owner pointer CAS, 실패 복구 시나리오
- 운영성 강화(관리 API, 대시보드)
