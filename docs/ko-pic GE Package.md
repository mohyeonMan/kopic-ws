# GE Package

스프링부트 기반 `kopic-ge`는 "게임 룰과 room runtime state의 authoritative owner"다.
핵심 원칙은 `GE`가 control plane이 아니라 runtime plane이라는 점을 유지하는 것이다.

현재 기준의 GE 내부 실행 구조는 아래와 같다.

- room 생성은 `Lobby -> GE lifecycle` 경로로 별도 처리한다.
- 이미 존재하는 room에 들어오는 이벤트만 `room mailbox` 경로를 탄다.
- command handler는 분기만 하고, 이벤트별 handler가 room job을 만든다.
- room job은 `RoomRunner`를 통해 room별 mailbox에 적재된다.
- 실제 상태 수정은 worker가 mailbox를 drain하면서 수행한다.
- 후속 작업은 즉시 재-submit 또는 scheduler 등록 후 재-submit 한다.

즉 `GE`는 아래 역할을 책임진다.

- room runtime state authoritative ownership
- room별 순차 실행 보장
- 권한/상태 전이 검증
- 턴/라운드/게임 진행
- score/canvas/guess 판정
- snapshot(`408`) 생성
- route/joinable/presence 갱신
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

  room
    domain
      Room
      RoomState
      RoomType
      Participant
      ParticipantStatus
      GameSettings
    app
      RoomLifecycleService
      RoomRunner
      RoomJob
      RoomJobResult
      RoomFollowUp
      RoomSlot
      RoomSlotRepository
      RoomRegistry
    infra
      InMemoryRoomRegistry

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
    app
      GameStartService
      DrawCommandService
      GuessCommandService
      SnapshotService

  command
    dto
      EngineEnvelopeRequest
      SessionLifecycleEvent
      EngineAck
    app
      EngineCommandDispatcher
      RoomEventType
      RoomEventContext
      RoomEventHandler
      RoomEventHandlerRegistry
      AbstractRoomEventHandler

  outbound
    app
      OutboundPublisher
      AudienceResolver

  directory
    app
      RoomRoutingUpdater
      RoomCodeIndexUpdater
      RandomJoinableIndexUpdater
      EnginePresencePublisher

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
- `createPrivateRoom`, `createRandomRoom`은 `RoomLifecycleService`가 `Room`과 `RoomSlot`을 만들고 repository에 등록한다.
- 그 이후에 들어오는 기존 room 대상 요청만 `RoomRunner.submit(roomId, job)` 구조를 탄다.

즉 `create room`은 lifecycle이고, `mutate existing room`은 runner 경로다.

### 2. room별 순차 실행은 RoomRunner가 보장한다

- 동일 `roomId`의 모든 job은 동일 `RoomSlot.mailbox`로 들어간다.
- `RoomRunner`는 slot을 찾아 job enqueue만 한다.
- slot이 실행 중이 아니면 executor에 `drain(slot)` 작업 하나를 등록한다.
- worker는 mailbox를 끝까지 비운 뒤 종료한다.

즉 executor는 queue들을 감시하지 않는다.
`RoomRunner`가 필요할 때만 executor에 "이 slot mailbox를 비워라"라고 요청한다.

### 3. authoritative state는 RoomSlot 안의 mutable Room이다

- `RoomSlot`은 `Room + mailbox + running + lastTouchedAt`를 가진다.
- `Room`은 immutable snapshot이 아니라 mutable authoritative state로 본다.
- `Game`, `Round`, `Turn`도 같은 방향으로 mutable state entity로 다룬다.
- runner 밖에서 직접 수정하면 안 되고, mailbox 안에서 실행되는 room job만 수정할 수 있다.

즉 thread-safety의 핵심은 immutable 복사가 아니라 `room별 단일 실행 보장`이다.

### 4. WS lifecycle는 GE 내부 room join/leave로 변환한다

- WS는 transport/session 관점에서 `JOIN`, `LEAVE` lifecycle 이벤트를 GE에 전달한다.
- GE는 이를 내부적으로 room 도메인 의미인 `ROOM_JOIN`, `ROOM_LEAVE`로 취급한다.
- 즉 WS의 연결 사건을 그대로 쓰지 않고, GE 안에서는 방 참여/이탈 의미를 기준으로 본다.

### 5. handler는 분기와 job 조립만 담당한다

- `EngineCommandDispatcher`는 event code를 `RoomEventType`으로 변환한다.
- session lifecycle은 `JOIN/LEAVE`를 내부 `ROOM_JOIN/ROOM_LEAVE`로 변환한다.
- registry에서 해당 handler를 찾는다.
- handler는 payload/context를 읽고 room job을 만든다.
- 실제 room 상태 수정은 직접 하지 않고 `RoomRunner.submit(...)`에 맡긴다.

즉 역할은 아래와 같이 분리된다.

- `dispatcher = 분기`
- `handler = room job 조립`
- `runner = room mailbox 적재`
- `worker = 실제 실행`

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
반드시 다시 `RoomRunner.submit(roomId, nextJob)` 경로를 타게 한다.

즉:

- 즉시 follow-up -> 바로 submit
- 지연 follow-up -> scheduler에 등록 후 시간이 되면 submit

이 구조 덕분에 턴/라운드/게임 전이를 mailbox 순서 안에서 일관되게 처리할 수 있다.

예:

- 턴 종료 -> 3초 뒤 다음 턴
- 라운드 종료 -> 4초 뒤 다음 라운드
- 게임 종료 -> 8초 뒤 결과 화면 종료

### 8. RoomJobResult는 호출자용 결과가 아니라 worker 내부 제어 신호다

`RoomJobResult`는 `future` 대체물이 아니다.
호출자가 기다리기 위한 값도 아니다.

이 값은 worker가 job을 실행한 뒤 runner에게 아래 정보를 알려주기 위해 쓴다.

- 이 slot을 유지할지 삭제할지
- follow-up job이 있는지
- follow-up이 즉시인지 지연인지

즉 비동기 구조와 충돌하지 않는다.
호출자는 여전히 fire-and-forget이고, `RoomJobResult`는 worker 내부 후처리용 값이다.

### 9. room 삭제 정책은 도메인 정책과 운영 정책을 분리해 본다

현재 논의 기준으로는 아래가 자연스럽다.

- `RANDOM`
  - empty 상태를 유지할지 즉시 삭제할지는 join policy와 joinable index 정책에 맞춰 결정한다.
- `PRIVATE`
  - idle TTL 기준 cleanup 가능

`lastTouchedAt`는 idle cleanup의 기준으로 유지한다.

정책 자체는 바뀔 수 있지만, 구조적으로는 `RoomJobResult`의 outcome과 cleanup scheduler로 양쪽 모두 처리 가능하게 둔다.

---

## 실행 흐름

### 1. room 생성

1. Lobby가 GE에 private/random room 생성을 요청한다
2. `RoomLifecycleService`가 `Room` 생성
3. `RoomSlot` 생성
4. `RoomSlotRepository`에 저장
5. room 정보 반환

### 2. 기존 room 대상 이벤트

1. WS/GE command 수신
2. dispatcher가 event code를 `RoomEventType`으로 변환
3. registry에서 handler 조회
4. handler가 room job 생성
5. `RoomRunner.submit(roomId, job)`
6. `RoomSlot.mailbox`에 enqueue
7. worker가 drain하면서 room 상태 검증/수정
8. 결과로 follow-up 있으면 즉시 submit 또는 scheduler 등록

---

## 피해야 할 구조

- room 생성까지 runner/event enum에 억지로 넣는 구조
- handler가 repository를 직접 수정하는 구조
- handler가 room current state를 직접 읽고 권한 검증까지 끝내는 구조
- follow-up을 직접 함수 호출로 연결하는 구조
- room runner 밖에서 mutable room/game/turn을 직접 수정하는 구조
- executor가 모든 room queue를 상시 스캔하는 구조

---

## 현재 기준 구현 우선순위

### Phase 1

- `RoomSlot`, `RoomSlotRepository`, `RoomRunner`, `RoomJobResult`, scheduler 뼈대
- dispatcher -> event handler -> runner 경로 정리
- room 생성은 lifecycle 경로로 유지

### Phase 2

- join/leave/game start/draw/guess/snapshot 이벤트를 handler 기반으로 이관
- follow-up으로 turn/round/game 전이 연결
- joinable/route/presence 갱신 정리

### Phase 3

- random/private 삭제 정책 정교화
- cleanup scheduler 정리
- migration/runtime 운영성 강화
