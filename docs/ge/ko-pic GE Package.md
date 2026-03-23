# GE Package

스프링부트 기반 `kopic-ge`는 "게임 룰과 room runtime state의 authoritative owner"다.
핵심 원칙은 `GE`가 control plane이 아니라 runtime plane이라는 점을 유지하는 것이다.

현재 기준의 GE 내부 실행 구조는 아래와 같다.

- room 생성은 `LobbyRpcGrpcService -> RoomService` 경로로 별도 처리한다.
- 이미 존재하는 room에 들어오는 이벤트만 `room mailbox` 경로를 탄다.
- WS runtime 입력은 `WsRpcGrpcService -> GameFlowService.dispatch(...)` 경로를 탄다.
- `GameFlowService`가 inbound 이벤트를 분기하고, 필요한 `RoomJob`을 만들어 `RoomService.submit(...)`으로 넘긴다.
- 서버가 WS로 발행하는 프로토콜 이벤트는 `OutboundRoomEventType`으로 별도 관리한다.
- room job은 `RoomService.submit(...)`을 통해 room별 mailbox에 적재된다.
- 실제 상태 수정은 worker가 mailbox를 drain하면서 수행한다.
- 후속 작업은 즉시 재-submit 또는 scheduler 등록 후 재-submit 한다.
- outbound routing은 room domain이 아니라 session presence를 기준으로 결정한다.

즉 `GE`는 아래 역할을 책임진다.

- room runtime state authoritative ownership
- room별 순차 실행 보장
- 권한/상태 전이 검증
- 턴/라운드/게임 진행
- score/canvas/guess 판정
- snapshot(`408`) 생성
- route/joinable/presence 갱신
- session presence 조회와 WS node routing
- migration source/target runtime 처리

반대로 아래는 `GE` 책임이 아니다.

- room 생성 요청 수신과 owner GE 선택
- client-facing pre-entry API
- room control plane orchestration
- WS connection/session transport 자체

---

## 추천 패키지 구조

```text
io.jhpark.kopic.ge
  KopicGeApplication

  config
    RoomRunnerConfig
    GrpcServerConfig

  api
    internal
      WsRpcGrpcService
      LobbyRpcGrpcService
      MigrationApi
      DefaultMigrationApi

  room
    domain
      Room
      RoomState
      RoomType
      Participant
      ParticipantStatus
      GameSettings
    app
      RoomService
      RoomClosingPort
      RoomRunner
      RoomJob
      RoomJobOutcome
      RoomJobResult
      RoomFollowUp
      RoomSlot
      RoomSlotRepository

  game
    domain
      Game
      GameStatus
      Round
      RoundState
      Turn
      TurnState
      TurnEndReason
      ScoreBoard
      CanvasState
      Stroke

  command
    dto
      EngineEnvelopeRequest
      SessionLifecycleEvent
      EngineAck
    app
      GameFlowService
      InboundRoomEventType
      OutboundRoomEventType
      RoomEventContext

  outbound
    app
      OutboundPublisher
      BroadcastService
    dto
      ServerEnvelope
      TargetedDelivery
    infra
      LoggingOutboundPublisher

  directory
    app
      RoomRoutingUpdater
      RoomCodeIndexUpdater
      RandomJoinableIndexUpdater
      EnginePresencePublisher
      SessionPresenceRepository
      SessionPresenceResolver
    infra
      RedisSessionPresenceRepository
      CachedSessionPresenceResolver

  migration
    app
      MigrationSourceService
      MigrationTargetService
      MigrationOwnerPointerUpdater
```

---

## 핵심 설계 원칙

### 1. room 생성과 room 이벤트 실행을 분리한다

- room 생성은 아직 mailbox 대상 room이 없으므로 `Lobby -> GE lifecycle` 경로로 처리한다.
- `createPrivateRoom`, `createRandomRoom`은 `RoomService`가 `Room`과 `RoomSlot`을 만들고 repository에 등록한다.
- 그 이후에 들어오는 기존 room 대상 요청만 `RoomService.submit(roomId, job)` 구조를 탄다.

즉 `create room`은 lifecycle이고, `mutate existing room`은 runner 경로다.

### 2. room별 순차 실행은 RoomRunner가 보장한다

- 동일 `roomId`의 모든 job은 동일 `RoomSlot.mailbox`로 들어간다.
- `RoomRunner`는 slot을 찾아 job enqueue만 한다.
- slot이 실행 중이 아니면 executor에 `drain(slot)` 작업 하나를 등록한다.
- worker는 mailbox를 끝까지 비운 뒤 종료한다.

즉 executor는 queue들을 감시하지 않는다.
`RoomRunner`가 필요할 때만 executor에 "이 slot mailbox를 비워라"라고 요청한다.

현재 외부에서 보이는 facade는 `RoomService`이고, `RoomRunner`는 그 내부 실행 구성요소로 사용된다.

### 3. authoritative state는 RoomSlot 안의 mutable Room이다

- `RoomSlot`은 `Room + mailbox + running + lastTouchedAt`를 가진다.
- `Room`은 immutable snapshot이 아니라 mutable authoritative state로 본다.
- `Game`, `Round`, `Turn`도 같은 방향으로 mutable state entity로 다룬다.
- runner 밖에서 직접 수정하면 안 되고, mailbox 안에서 실행되는 room job만 수정할 수 있다.

즉 thread-safety의 핵심은 immutable 복사가 아니라 `room별 단일 실행 보장`이다.

### 4. WS lifecycle는 GE 내부 room join/leave로 변환하고 session presence를 갱신한다

- WS는 transport/session 관점에서 `JOIN`, `LEAVE` lifecycle 이벤트를 GE에 전달한다.
- GE는 `JOIN`일 때 payload의 `nickname`을 읽고, `wsNodeId`가 있으면 session presence를 갱신한다.
- session presence는 Redis를 authoritative source로 두고, key는 `session:presence:user:{userId}`를 사용한다.
- GE는 이를 내부적으로 inbound room 이벤트 의미인 `ROOM_JOIN`, `ROOM_LEAVE`로 취급한다.
- 즉 WS의 연결 사건을 그대로 쓰지 않고, GE 안에서는 방 참여/이탈 의미를 기준으로 본다.

### 5. GameFlowService는 WS runtime orchestration을 담당한다

- `WsRpcGrpcService`는 client event code를 `InboundRoomEventType`으로 변환한다.
- session lifecycle은 `JOIN/LEAVE`를 내부 `ROOM_JOIN/ROOM_LEAVE`로 변환한다.
- `GameFlowService`는 payload/context를 읽고 필요한 room job을 만든다.
- 실제 room 상태 수정은 직접 하지 않고 `RoomService.submit(...)`에 맡긴다.
- outbound는 `BroadcastService`에 userId 목록을 넘기고, 실제 WS node routing은 session presence 기반으로 resolve한다.

즉 역할은 아래와 같이 분리된다.

- `ws rpc grpc service = transport validate + inbound 매핑`
- `game flow service = runtime orchestration + room job 조립`
- `room service = submit facade`
- `runner = room mailbox 적재`
- `worker = 실제 실행`
- `broadcast service = userId 대상 publish`
- `session presence resolver = userId -> wsNodeId resolve`

여기서 중요한 점은 inbound와 outbound를 섞지 않는 것이다.

- `InboundRoomEventType`
  - 클라이언트나 WS lifecycle이 GE로 전달하는 입력 이벤트
  - `GameFlowService.dispatch(...)`가 처리
- `OutboundRoomEventType`
  - GE가 WS로 발행하는 프로토콜 이벤트
  - `BroadcastService` / `OutboundPublisher`가 처리

예:

- inbound: `GAME_SETTINGS_UPDATE_REQUEST`
- outbound: `GAME_SETTINGS_UPDATED`

### 6. 권한 검증은 현재 room 상태 기준으로 mailbox 안에서 수행한다

- host 여부
- drawer 여부
- current turn/phase 검증
- 이미 정답 처리된 사용자 여부

이런 검증은 stale state를 피하기 위해 runner 안에서 해야 한다.

반면 아래는 바깥에서 가능하다.

- payload 형식 검증
- enum parse
- null/빈 문자열 확인
- transport-level rate-limit

### 7. follow-up은 다시 runner 경로로 들어와야 한다

job 실행 결과로 아래 두 가지가 생길 수 있다.

- 즉시 후속 작업
- 몇 초 뒤 후속 작업

이때 후속 작업은 직접 함수 호출로 이어붙이지 않는다.
반드시 다시 `RoomService.submit(roomId, nextJob)` 경로를 타게 한다.

즉:

- 즉시 follow-up -> 바로 submit
- 지연 follow-up -> scheduler에 등록 후 시간이 되면 submit

이 구조 덕분에 턴/라운드/게임 전이를 mailbox 순서 안에서 일관되게 처리할 수 있다.

예:

- 턴 종료 -> 3초 뒤 다음 턴
- 라운드 종료 -> 즉시 다음 라운드
- 게임 종료 -> 8초 뒤 결과 화면 종료

### 8. 사용자 입력이 없는 구간은 미리 선언된 internal flow job으로 진행한다

턴/라운드/게임 진행에는 사용자의 추가 입력이 없어도 서버가 스스로 계속 진행해야 하는 구간이 있다.

예:

- `GAME_STARTED` 후 첫 `ROUND_STARTED`
- `ROUND_STARTED` 후 첫 `TURN_STARTED`
- `WORD_CHOICES` 후 timeout
- `DRAWING_STARTED` 후 timeout
- `TURN_ENDED` 후 다음 턴/라운드/게임 종료

이 구간은 `GameFlowService`가 즉석에서 전부 계산해 이어붙이는 대신, 미리 선언된 internal flow job을 follow-up으로 예약하는 방식으로 처리한다.

즉:

- `GameFlowService`는 현재 입력을 처리한다
- 그 결과로 다음에 필요한 internal flow job을 선택한다
- 선택된 job은 `RoomService.submit(...)` 경로로 다시 mailbox에 들어간다

이 구조를 쓰면 사용자 입력과 서버 주도 진행을 같은 room mailbox 위에서 일관되게 다룰 수 있다.

### 9. RoomJobResult는 호출자용 결과가 아니라 worker 내부 제어 신호다

`RoomJobResult`는 `future` 대체물이 아니다.
호출자가 기다리기 위한 값도 아니다.

이 값은 worker가 job을 실행한 뒤 runner에게 아래 정보를 알려주기 위해 쓴다.

- 이 slot을 유지할지 삭제할지
- follow-up job이 있는지
- follow-up이 즉시인지 지연인지

즉 비동기 구조와 충돌하지 않는다.
호출자는 여전히 fire-and-forget이고, `RoomJobResult`는 worker 내부 후처리용 값이다.

### 10. room 삭제 정책은 도메인 정책과 운영 정책을 분리해 본다

현재 논의 기준으로는 아래가 자연스럽다.

- `RANDOM`
  - empty 상태가 되면 즉시 삭제한다.
- `PRIVATE`
  - empty 상태가 되면 30초 뒤 삭제한다.

`lastTouchedAt`는 idle cleanup의 기준으로 유지한다.

정책 자체는 바뀔 수 있지만, 구조적으로는 `RoomJobResult`의 outcome과 cleanup scheduler로 양쪽 모두 처리 가능하게 둔다.

---

## 실행 흐름

### 1. room 생성

1. Lobby가 GE에 private/random room 생성을 요청한다
2. `RoomService`가 `Room` 생성
3. `RoomSlot` 생성
4. `RoomSlotRepository`에 저장
5. room 정보 반환

### 2. 기존 room 대상 이벤트

1. WS/GE gRPC command 수신
2. `WsRpcGrpcService`가 event code를 `InboundRoomEventType`으로 변환
3. `GameFlowService.dispatch(...)`
4. `GameFlowService`가 room job 생성
5. `RoomService.submit(roomId, job)`
6. `RoomSlot.mailbox`에 enqueue
7. worker가 drain하면서 room 상태 검증/수정
8. 결과로 follow-up 있으면 즉시 submit 또는 scheduler 등록

---

## 피해야 할 구조

- room 생성까지 runner/event enum에 억지로 넣는 구조
- `GameFlowService`가 repository를 직접 수정하는 구조
- `GameFlowService`가 room current state를 직접 읽고 권한 검증까지 끝내는 구조
- follow-up을 직접 함수 호출로 연결하는 구조
- room runner 밖에서 mutable room/game/turn을 직접 수정하는 구조
- executor가 모든 room queue를 상시 스캔하는 구조

---

## 현재 기준 구현 우선순위

### Phase 1

- `RoomSlot`, `RoomSlotRepository`, `RoomRunner`, `RoomJobResult`, scheduler 뼈대
- `WsRpcGrpcService -> GameFlowService -> RoomService` 경로 정리
- room 생성은 `LobbyRpcGrpcService -> RoomService.create...` 경로로 유지

### Phase 2

- join/leave/game start/draw/guess/snapshot 이벤트를 `GameFlowService` 기준으로 정리
- follow-up으로 turn/round/game 전이 연결
- joinable/route/presence 갱신 정리

### Phase 3

- random/private 삭제 정책 정교화
- cleanup scheduler 정리
- migration/runtime 운영성 강화
