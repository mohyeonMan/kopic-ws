# Lobby Package

스프링부트 기반 `kopic-lobby`는 게임 진행 서버가 아니라 "room entry / allocation / migration coordination application"으로 두는 것이 맞다.  
핵심 원칙은 `lobby`가 runtime plane이 아니라 control plane이라는 점을 끝까지 유지하는 것이다.

즉 `controller/service/repository` 식 범용 분류보다, `Lobby`가 실제로 맡는 책임 경계대로 패키지를 자르는 편이 유지보수에 유리하다.

`Lobby`는 아래 역할만 책임진다.

- private room 생성 요청 중개
- random quick-join 처리
- roomCode 기반 방 조회
- owner GE 선택
- GE에 room 생성/참여 요청 전달
- scale-in 시 waiting room 재배정 coordinator

반대로 아래는 `Lobby` 책임이 아니다.

- WebSocket runtime 처리
- 게임 시작/턴/라운드 진행
- 캔버스/점수/정답 판정
- room audience 계산
- running game snapshot 생성/복원

즉 `Lobby`는 "유저를 어느 room에 넣고, 그 room을 어느 GE가 맡을지 결정하는 서버"다.

## 추천 패키지 구조

```text
io.jhpark.kopic.lobby
  KopicLobbyApplication

  common
    error
    logging
    id
    time

  config
    JacksonConfig
    RedisConfig
    WebConfig
    SecurityConfig

  room
    domain
      RoomEntry
      RoomType
      RoomCode
    app
      PrivateRoomCreateService
      RoomLookupService
      RoomCodeLookupReader
    dto
      CreatePrivateRoomRequest
      CreatePrivateRoomResponse
      RoomEntryResponse
    infra
      RedisRoomCodeIndex

  matchmaking
    domain
      QuickJoinCandidate
      QuickJoinResult
    app
      RandomQuickJoinService
      RandomRoomCandidateFinder
      RandomRoomJoinCoordinator
    dto
      RandomQuickJoinRequest
      RandomQuickJoinResponse
    infra
      RedisRandomRoomIndex

  allocation
    domain
      EngineCandidate
      EngineSelectionPolicy
    app
      EngineAllocationService
    infra
      LeastActiveRoomsPolicy

  directory
    domain
      RoomOwnership
    app
      RoomRoutingReader
      RoomOwnershipUpdater
    infra
      RedisRoomDirectory

  engine
    domain
      EngineStatus
      EnginePresence
    app
      EnginePresenceReader
      EnginePrivateRoomPort
      EngineRandomRoomPort
      EngineQuickJoinPort
    infra
      RedisEnginePresenceStore
      HttpEnginePrivateRoomClient
      HttpEngineRandomRoomClient
      HttpEngineQuickJoinClient

  migration
    domain
      MigrationRequest
      MigrationPlan
    app
      RoomMigrationCoordinator
    dto
      ReassignRoomRequest
      ReassignRoomResponse

  api
    publicapi
      RoomCommandController
      RoomQueryController
      MatchmakingController
    internal
      MigrationAdminController

  observability
    metric
    tracing
    audit
```

## 핵심 책임

### 1. `room`

- room entry 응답 shaping을 담당한다.
- GE가 생성한 roomCode 기반 조회를 담당한다.
- `Lobby`가 직접 다뤄야 하는 room 정보는 입장과 owner selection에 필요한 최소 정보뿐이다.
- 즉 이 패키지는 runtime room 모델이 아니라 entry용 room record를 다루는 패키지다.
- 최소 정보 예시는 아래와 같다.
  - `roomId`
  - `roomType`
  - `roomCode`
  - `ownerEngineId`
- turn/canvas/score/participant/runtime 상태는 `Lobby`가 소유하지 않는다.

### 2. `matchmaking`

- random mode의 quick-join 로직을 담당한다.
- 핵심은 "2명 큐 매칭"이 아니라 "join 가능한 기존 random room을 우선 찾고, 없으면 새 room을 만드는 것"이다.
- host 없는 random room으로 quick-join을 중개하고, quick-join 성공 시 그 room의 owner GE로 유저를 보낸다.
- random quick-join은 아래 순서로 동작한다.
  1. `Lobby`가 Redis 인덱스에서 join 가능한 random room 후보를 조회
  2. owner GE를 확인하고 실제 join 가능 여부를 GE에 질의
  3. GE가 메모리 room state 기준으로 최종 승인
  4. 성공 시 GE가 로컬 상태와 Redis joinable index를 갱신
  5. 실패 시 `Lobby`가 다음 후보를 재시도
  6. 끝까지 실패하면 owner GE에 새 random room 생성을 요청
- 즉 Redis는 후보 탐색용이고, 최종 join 승인 권한은 GE에 있다.

초기 MVP에서는 아래 정도로 충분하다.

- join 가능한 random room 후보 조회
- owner GE에 실제 join 요청
- 실패 시 다음 후보 재시도
- 적합한 방이 없으면 owner GE에 새 random room 생성 요청

향후 확장 포인트:

- skill/MMR 기반 매칭
- region/device 필터링

### 3. `allocation`

- 새 room 생성 요청이나 migration 대상의 owner GE를 고르는 책임이다.
- `Lobby`가 GE 내부 room 상태를 상세 조회하지 않더라도, Redis presence 수준의 최소 정보만으로 배정 판단은 가능해야 한다.
- 현재 기준의 선택 규칙은 아래와 같다.
  1. `ACTIVE` 상태 GE만 후보
  2. stale presence GE 제외
  3. `activeRooms` 가장 적은 GE 우선
- 이 정책은 room 생성 시뿐 아니라 scale-in migration target 선택에도 재사용한다.

즉 `allocation`은 "새 room 배정"과 "기존 room 재배정" 모두의 공통 정책 패키지다.

### 4. `directory`

- `Lobby`가 읽는 routing hint 접근을 담당한다.
- 필요 시 `roomCode -> roomId` 인덱스 조회도 함께 다룬다.
- WS나 다른 서비스가 참조하는 owner 정보의 기준점은 Redis routing hint다.
- `Lobby`는 owner selection을 위해 routing hint를 참조하고, migration 시 source owner 검증만 수행한다.

중요한 점은 아래다.

- `Lobby`는 "누가 room을 맡아야 하는가"를 결정한다.
- 실제 route를 만들고 지우는 것은 GE다.
- 즉 `directory` 패키지는 route lifecycle owner가 아니라, `Lobby` 입장에서 필요한 routing hint access 경계다.
- `Lobby`는 배정자이고, `GE`는 room/runtime/routing의 owner다.

### 5. `engine`

- GE presence 조회와 engine 상태 필터링을 담당한다.
- `Lobby`는 GE의 상세 room state를 풀스캔하지 않고, 최소 메타만 읽는 방향이 맞다.
- 각 GE node가 자신의 presence를 Redis에 직접 갱신하는 구조가 맞다.
- 권장 presence 필드는 아래와 같다.
  - `engineId`
  - `status` (`ACTIVE`, `DRAINING`)
  - `activeRooms`
  - `heartbeatUpdatedAt`

추가로 `Lobby -> GE` 요청 경계도 이 패키지에 둔다.

- private room 생성 요청
- random room 생성 요청
- random quick-join 승인 요청

즉 `engine` 패키지는 presence read와 GE command port를 함께 가지는 외부 GE 경계 패키지로 두는 편이 맞다.

### 6. `migration`

- scale-in 시 waiting room 재배정을 조정하는 control plane 패키지다.
- `Lobby`는 migration coordinator가 될 수 있지만, room snapshot의 생성/복원 자체를 소유하지는 않는다.
- 따라서 `migration` 패키지의 주 역할은 아래와 같다.
  - source GE의 재배정 요청 접수
  - target GE 선택
  - source GE에 target endpoint 전달

현재 전략에서 migration 가능 조건:

- room이 `LOBBY` 또는 waiting 상태
- 현재 running game이 없음

불가능 조건:

- `RUNNING` game 보유
- handoff 중인 room
- 이미 `MIGRATING` 상태

이 패키지가 중요한 이유는 `Lobby`가 scale-in에서 target 선정/중개를 일관되게 수행하기 때문이다.

### 7. `api`

`Lobby`는 public API와 internal API를 분리하는 편이 좋다.

public API 예시:

- private room 생성
- roomCode 조회
- random quick-join 요청

internal API 예시:

- GE -> Lobby room reassign 요청

이 둘을 같은 controller에 섞기 시작하면 인증/권한/모델이 빠르게 오염된다.

## 패키지 설계 원칙

스프링부트에서는 프로젝트 전체를 `app / domain / infra`로 가로 분할하는 것보다, 각 bounded package 내부에서만 수직 분할하는 편이 좋다.

예:

- `allocation.app.EngineAllocationService`
- `allocation.domain.EngineCandidate`
- `allocation.infra.LeastActiveRoomsPolicy`

같은 책임 안에서 수직 분할하는 방식이 `Lobby`에도 더 잘 맞는다.

이유는 `Lobby`가 생각보다 여러 종류의 "조정"을 하기 때문이다.

- room entry 생성 조정
- random quick-join 조정
- owner selection 조정
- migration 조정

이것들을 `service` 한곳에 몰아넣으면 빠르게 거대한 orchestration 덩어리가 된다.

## 데이터 모델 경계

`Lobby`가 직접 소유해야 하는 데이터와, 참조만 해야 하는 데이터를 구분해야 한다.

`Lobby`가 소유:

- 없음(현재 설계 기준으로 migration runtime 상태는 source/target GE가 관리)

`Lobby`가 참조만 함:

- room entry 정보
- roomCode -> roomId 인덱스
- GE presence
- random joinable room 인덱스
- room routing 정보
- GE 상세 room runtime state
- running game snapshot

즉 `Lobby` 저장소에 turn, score, canvas를 넣기 시작하면 경계가 깨진다.

## 인터페이스 경계

`Lobby`와 외부 시스템의 경계는 포트 인터페이스로 고정하는 것이 좋다.

```java
public interface RoomCodeLookupReader {
    Optional<String> findRoomIdByCode(String roomCode);
}

public interface EnginePresenceReader {
    List<EnginePresence> findActiveCandidates();
    Optional<EnginePresence> findById(String engineId);
}

public interface EnginePrivateRoomPort {
    PrivateRoomCreated createPrivateRoom(String engineId, CreatePrivateRoomCommand command);
}

public interface EngineRandomRoomPort {
    RandomRoomCreated createRandomRoom(String engineId, CreateRandomRoomCommand command);
}

public interface RandomRoomIndex {
    List<QuickJoinCandidate> findJoinableCandidates(int limit);
}

public interface EngineQuickJoinPort {
    QuickJoinResult tryJoinRandomRoom(String engineId, String roomId, JoinUserCommand command);
}
```

이렇게 두면 구현체는 나중에 Redis/HTTP/gRPC로 갈아끼우면 된다.

중요한 점은 아래다.

- `Lobby` application service가 `RedisTemplate`, `RestClient`, `WebClient`를 직접 알지 않게 한다.
- migration coordinator가 GE snapshot schema를 직접 조작하지 않게 한다.
- room assignment 서비스가 engine 선택 로직을 하드코딩하지 않게 한다.
- random quick-join 서비스가 Redis joinable index를 authoritative room state처럼 신뢰하지 않게 한다.

## 권장 요청 흐름

### 1. Private room 생성

1. Client -> `POST /api/lobby/rooms/private`
2. `allocation`이 owner GE를 선택한다.
3. `engine` outbound port가 owner GE에 private room 생성을 요청한다.
4. GE가 room 생성, roomId 생성, roomCode 발급, route 반영에 성공한다.
5. GE가 `roomId`, `roomCode`, `roomType`을 반환한다.
6. `Lobby`가 entry를 구성해 client에 응답한다.

### 2. Random quick-join

1. Client -> `POST /api/lobby/matchmaking/random`
2. `matchmaking`이 Redis의 random room 인덱스에서 join 가능한 후보를 조회한다.
3. routing 정보로 각 후보 room의 owner GE를 확인한다.
4. `engine` outbound port가 owner GE에 실제 join 가능 여부를 질의한다.
5. GE가 메모리 room state 기준으로 join을 최종 승인하면, GE가 participant 반영과 Redis joinable index 갱신까지 수행한다.
6. `Lobby`는 join 성공한 room 정보를 client에 응답한다.
7. 모든 후보가 실패하면 `Lobby`가 새 owner GE를 고른 뒤, 해당 GE에 random room 생성을 요청한다.
8. GE가 random room 생성과 route/joinable 반영에 성공하면 `Lobby`가 entry를 응답한다.

### 3. Room lookup by code

1. Client -> `GET /api/lobby/rooms/by-code/{roomCode}`
2. `room`이 roomCode 인덱스를 조회한다.
3. `directory`가 roomId 기준 routing hint를 조회한다.
4. room entry 정보를 응답한다.
5. 클라이언트는 이 정보로 WS 연결에 필요한 `roomId`를 얻는다.

### 4. Scale-in waiting room reassignment

1. source GE가 drain 중 waiting room handoff 필요를 감지한다.
2. source GE -> `Lobby` internal API로 재배정 요청
3. source GE는 대상 room을 로컬 `MIGRATING`으로 잠그고, `random` room이면 joinable index에서 제외한다.
4. `allocation`이 target GE를 선택한다.
5. `Lobby`는 source GE에 `targetEngineId + targetEngineEndpoint`를 반환한다.
6. source GE가 target GE에 snapshot을 직접 전달해 prepare를 완료한다.
7. source GE가 Redis owner pointer를 CAS로 `source -> target` 갱신한다.
8. owner 갱신 성공 후 source GE가 기존 room actor/state를 제거한다.

중요:

- `Lobby`는 migration coordinator지만 runtime state owner는 아니다.
- `Lobby`는 source/target 데이터 전달이나 owner 갱신을 수행하지 않는다.
- failure 시 source owner를 유지한 채 source/target GE가 복구한다.

### 5. Random quick-join에서 Redis와 GE의 역할 분리

- Redis에는 random room의 joinable candidate index만 유지한다.
- 실제 authoritative participant state는 owner GE 메모리에 있다.
- 따라서 `Lobby`는 Redis를 보고 후보 room을 찾되, 실제 입장 가능 여부는 owner GE에 최종 확인해야 한다.
- GE join 성공 후에만 Redis joinable index를 갱신해야 한다.
- `Lobby`가 join 성공을 가정하고 join 결과를 먼저 반영하면 권위 경계가 무너질 수 있다.

권장 Redis 정보 예시:

- `rooms:random:joinable` (`ZSET`)
- `room:{roomId}:owner`

`ZSET`의 score는 `participantCount` 기반이어도 되고, 운영상 더 단순하게는 GE가 넣는 join priority 값이어도 된다.  
다만 이 score는 후보 정렬용일 뿐, 최종 입장 승인 조건은 아니다.

## Migration 설계에서 `Lobby`가 알아야 하는 것

`Lobby`는 아래까지만 알면 된다.

- 어떤 room이 재배정 대상인가
- source GE가 누구인가
- target GE가 누구인가
- target endpoint가 무엇인가

`Lobby`가 몰라도 되는 것:

- stroke 목록의 의미
- 현재 턴의 정답자 집합
- timer freeze/resume 세부 로직
- runtime snapshot 내부 필드 해석

즉 `Lobby`는 "state를 이해하는 서버"가 아니라 "handoff를 확정하는 서버"여야 한다.

## Redis 관점의 책임 분리

`Lobby`가 읽거나 migration 조정에 관여하는 키 예시는 아래와 같다.

- `room:code:{roomCode}`
- `room:{roomId}:owner`
- `rooms:random:joinable`

여기서 중요한 구분:

- `room:{roomId}:owner`는 WS와 Lobby가 참조하는 핵심 routing pointer다.
- `rooms:random:joinable`은 quick-join 후보 탐색용 인덱스다.
- `room:code:{roomCode}`는 GE가 생성/삭제하고 Lobby가 조회하는 초대 코드 인덱스다.
- random room joinable index 역시 실제 join/leave 결과를 알고 있는 GE가 갱신 주체가 되는 편이 맞다.
- `room:{roomId}:owner` route 역시 room 생성/삭제와 함께 GE가 생성/삭제 주체가 되는 편이 맞다.
- migration 중 joinable 제거/복구와 owner pointer CAS 갱신도 source/target GE가 수행하는 편이 맞다.

즉 `Lobby`는 entry와 배정 정보를 다루고, GE는 실제 room registry와 routing을 든다.

## 피해야 할 구조

- `Lobby` 안에서 game rule enum, 점수 계산 로직, turn timer를 들고 있는 구조
- private room 생성과 migration orchestration이 한 거대 service로 뭉개진 구조
- `Lobby`가 GE room snapshot 내부 스키마를 직접 조작하는 구조
- `Lobby`가 WS session 정보를 직접 소유하려는 구조
- `RoomController`, `MatchController`, `MigrationController`가 모두 동일 giant facade service를 호출하는 구조
- `Lobby`가 room entry 정보나 roomCode 인덱스를 직접 저장하려는 구조
- `Lobby`가 GE의 owned room 목록을 authoritative source로 소유하려는 구조
- `Lobby`가 random room 인덱스만 보고 join 가능 여부를 최종 확정하는 구조
- GE join 성공 전 `Lobby`가 join 결과를 먼저 반영하는 구조
- migration에서 target import 성공 전 owner pointer를 먼저 바꾸는 구조
- route/joinable 생성·삭제를 `Lobby`가 책임지는 구조

## 현실적인 시작점

현재 레포에서는 아래 정도부터 시작하는 것이 현실적이다.

```text
io.jhpark.kopic.lobby
  config
  common
  room
  matchmaking
  allocation
  directory
  engine
  migration
  api
```

`observability`는 실제 migration/매칭 운영이 붙기 시작할 때 추가하면 된다.

## 구현 우선순위 제안

### Phase 1

- private room 생성 요청 중개
- roomCode 조회
- random quick-join 후보 조회
- owner GE join 요청
- random room 신규 생성 요청 fallback
- owner GE 선택

### Phase 2

- room entry 조회 확장
- internal engine presence 조회
- migration coordinator 뼈대

### Phase 3

- waiting room reassignment 완성
- source/target GE handoff 실패 복구 보강
- 운영 지표/관리 API 보강

## 최종 기준

`Lobby`는 "방으로 들어갈 entry를 발급하고, 방의 owner를 정하고, 필요할 때 그 owner를 안전하게 다시 배정하는 서버"로 유지하는 것이 맞다.

이 경계를 지키면:

- `WS`는 runtime gateway
- `GE`는 runtime state owner
- `Lobby`는 room control plane

으로 역할이 선명하게 나뉜다.
