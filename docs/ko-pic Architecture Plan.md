# ko-pic k3s Architecture Plan (Draft v0.2)

## 1. 목표

- `Game Rules.md`와 `ko-pic Protocol Spec v0.md`를 k3s 환경에서 안정적으로 구현하기 위한 서버 아키텍처 초안
- MVP 우선: 낮은 복잡도로 시작하고, 트래픽 증가 시 분리/확장 가능한 구조 채택

---

## 2. 핵심 아키텍처 선택

`Room-Actor` 기반 실시간 아키텍처를 채택한다.

- 각 room은 단일 Authoritative Game Engine 인스턴스(논리적 actor)가 상태를 관리
- 턴/라운드 타이머, 점수, 권한 검증(drawer 여부), 턴 종료 조건 등은 서버 단일 권한으로 처리
- room 단위 일관 라우팅으로 이벤트 순서성과 상태 일관성을 확보

이 선택은 다음 규칙과 직접 연결된다.

- 서버 타이머 주도 진행(ACK 대기 없음)
- `at-most-once` 전달 모델
- 불일치 시 `106 GAME_SNAPSHOT_REQUEST -> 408 GAME_SNAPSHOT` 복구

---

## 3. 필수 노드(확정)

현재 아키텍처에서 필수 노드는 아래 3개다.

1. `WS` (WebSocket 서버)
- 클라이언트 연결, 최소 검증, rate limit, 라우팅 담당

2. `GE` (Game Engine 서버)
- room actor 기반 상태/게임 진행/판정 담당

3. `Lobby` 서버
- 클라이언트의 HTTP entry 담당
- 방 생성, 랜덤 매칭, room owner GE 배정 담당

보조 인프라:

- `Redis` (Directory/캐시)
- `MQ` (선택, GE->WS fan-out 확장용)

---

## 4. 컴포넌트 책임 분리

1. Ingress Controller (Traefik 또는 Nginx)
- WebSocket 업그레이드/타임아웃/keepalive 처리

2. WS (stateless 지향)
- WS 연결 수용
- WS 연결 시 클라이언트로부터 `roomId`를 받아 세션에 결합
- `afterConnectionEstablished` 단계에서 내부 join을 GE에 전달
- 최소 검증(Envelope 파싱 가능 여부, 인증/세션, 프레임 크기/전역 rate limit)
- 세션 최소 상태 유지(`userId`, `roomId`, 연결/활동 시각)
- `roomId -> owner GE` 조회 후 라우팅
- `PING/PONG` 등 연결 레벨 처리

3. GE (stateful)
- room actor의 authoritative 상태 소유
- 도메인 검증(이벤트별 payload/rule/state transition)
- 턴/라운드 타이머, 점수/권한 판정, snapshot(`408`) 생성
- room actor는 전용 스레드가 아니라 mailbox + shared worker pool 방식

4. Lobby
- `ROOM_CREATE_PRIVATE` 처리
- 랜덤 매칭 큐 관리/성립
- room 생성 시 owner GE 배정
- Directory 등록(`roomId -> owner GE`)
- GE 내부 room runtime 상태를 소유하지 않음
- client-facing HTTP API를 제공하고, WS runtime path에는 참여하지 않음

5. Redis
- room 디렉토리(`room:{id} -> ownerGE`)
- owner lease/TTL heartbeat
- snapshot 캐시(선택), 보조 presence(선택)

6. 영속 저장소(선택)
- PostgreSQL: 전적/게임 결과/운영 로그 저장

---

## 5. WS-GE 통신 패턴

1. WS -> GE (명령 경로)
- 기본: 동기 RPC(gRPC)
- 이유: 요청 수락 여부와 즉시 에러 응답이 단순
- RPC 응답은 `ack/error`까지만 담당하고, 실제 client-facing event는 포함하지 않음

2. GE -> WS (브로드캐스트 경로)
- 초기부터 publish-subscribe 경로를 기준으로 설계
- 확장: MQ(NATS/RabbitMQ 등) publish-subscribe

3. 라우팅 정합성
- 라우팅 키는 `roomId`
- stale 라우팅 시 `NOT_OWNER -> directory 재조회 -> 1회 재전송`

---

## 6. k3s 배포 단위

- `ws` Deployment + Service
- `ge` Deployment + Service
- `lobby` Deployment + Service
- `redis` StatefulSet + PVC + Service
- (선택) `postgres` StatefulSet 또는 외부 관리형 DB
- IngressRoute/Ingress + TLS

리소스 분리 원칙:

- WS는 연결 수/네트워크 I/O 중심
- GE는 CPU/메모리(게임 상태/타이머) 중심

---

## 7. 스케일 전략

1. WS 수평 확장
- HPA 기준: CPU + 연결 수 기반 커스텀 메트릭

2. GE 수평 확장
- HPA 기준: CPU + `active_rooms` 커스텀 메트릭
- room 이동(리샤딩)은 초기엔 최소화, 장기적으로 drain + migrate 전략 도입

3. 장애 시
- GE Pod 장애 발생 시 room directory TTL 만료/재할당
- 클라이언트는 `106`으로 재동기화

4. WS 연결 분산
- `Client -> WS` 연결은 room affinity 없이 일반 LB/round-robin으로 수용
- `roomId` affinity는 `WS -> GE` 구간에만 적용
- k3s/Ingress 레벨에서 `roomId` 기반 sticky routing은 사용하지 않음

5. Directory 캐시 정책
- 전역 authoritative directory는 Redis를 사용한다.
- 각 노드는 로컬 조회 최적화를 위해 Caffeine cache를 사용한다.
- directory는 2단 캐시로 관리한다.
  - `roomId -> ownerEngineId`
  - `engineId -> EngineRoute`
- `roomId -> ownerEngineId` cache TTL은 `15분`으로 잡는다.
- `engineId -> EngineRoute` cache TTL은 `24시간`으로 잡는다.
- 두 캐시 모두 `cache.get(key, loader)` 기반으로 조회 coalescing 되도록 구성한다.
- `NOT_OWNER` 발생 시 `roomId -> ownerEngineId` cache는 즉시 invalidate 후 재조회한다.
- route connect failure 발생 시 `engineId -> EngineRoute` cache는 즉시 invalidate 후 재조회한다.
- `nodeId`는 WS pod의 고유값을 그대로 사용한다(예: pod name).

6. GE room registry
- 각 GE는 자신이 소유한 room actor/state를 메모리에 유지한다.
- GE 내부 room registry는 idle room 정리를 위해 Caffeine 같은 in-memory cache를 사용할 수 있다.
- room registry의 목적은 authoritative runtime 상태 보유 + 미사용 room 자동 정리다.
- 제거 기준은 단순 access 하나로 고정하지 않고 room 상태에 따라 나눈다.
  - 생성 후 아직 입장자가 없는 pending room
  - 모든 참여자가 떠난 empty room
  - 게임 종료 후 정리 가능한 ended room

---

## 8. 검증 책임 분리(합의)

1. WS가 담당하는 최소 검증
- Envelope 파싱/필수 필드 확인(`e`, `p`)
- 인증/세션 유효성
- 메시지 크기/전역 rate limit
- 모든 runtime 요청은 세션에 결합된 `roomId`를 공통 라우팅 키로 사용
- 별도 entry/event 분류기보다 공통 라우팅 파이프라인을 우선한다

2. GE가 담당하는 도메인 검증
- 이벤트별 payload 형식/범위 검증(`INVALID_PAYLOAD`)
- 룰 검증(`NOT_DRAWER`, `GAME_NOT_RUNNING`, `turnId` 일치 등)
- 상태 전이 검증(턴/라운드/게임 단계)

보강 원칙:

- WS는 공통 envelope만 파싱하고 이벤트별 payload 의미는 해석하지 않는다.
- `DRAW_STROKE`, `WORD_CHOICE` 등 이벤트별 payload parsing과 semantic validation은 GE가 담당한다.

---

## 9. 프로토콜/룰 반영 포인트

1. 권한
- `DRAW_STROKE`, `DRAW_CLEAR`, `WORD_CHOICE`는 drawer만 허용

2. 레이트리밋
- `DRAW_STROKE`: 사용자당 초당 최대 20회, 메시지 최대 8KB
- `GUESS_SUBMIT`: 사용자당 초당 최대 5회

3. 상태 전환 지연
- 턴 종료 후 3초
- 라운드 종료 후 4초
- 게임 종료 후 결과 화면 8초

4. 스냅샷
- 입장 성공 직후 `301 + 408`을 GE가 비동기 outbound event로 발행
- 불일치 복구 시 `106 -> 408`

5. 세션 위치 메타데이터
- WS 연결 성립 시점에 `roomId`, `userId`가 세션 컨텍스트로 확정된다.
- WS는 `afterConnectionEstablished` 내부 join과 이후 runtime 요청 전달 시 위 메타데이터를 GE에 함께 전달한다.
- GE는 participant 상태와 delivery 대상 `userId`를 함께 관리
- 현재 MVP에서는 room 이동을 연결 종료 후 재연결/재입장으로 처리하므로, 세션 위치 lifecycle은 `join 시 등록`, `disconnect 시 제거`로 단순화

6. GE -> WS delivery
- room audience 계산은 GE가 authoritative하게 수행
- WS는 room fan-out 주체가 아니라, 자신에게 연결된 사용자 세션으로만 targeted delivery를 수행
- WS는 `EngineEventSubscriber` 같은 outbound 수신 포트를 통해 이를 받아 처리한다.
- GE -> WS RabbitMQ publish 시 routing key는 `ws.node.{nodeId}` 규칙을 사용한다.

---

## 10. 관측성(필수 지표)

- `active_rooms`
- `active_connections`
- `turn_timeout_count`
- `snapshot_build_latency_ms`
- `event_process_latency_ms`
- `rate_limited_count`
- `error_code_count{code}`

로그 상관관계 키:

- `roomId`, `gameId`, `turnId`, `userId`, `rid`

---

## 11. 단계별 구현 제안

### Phase 1 (MVP 빠른 구현)

- WS/GE/Lobby 3노드를 논리적으로 분리해 구현
- 필요 시 운영 단순화를 위해 WS+GE를 동일 Pod로 배치 가능
- Redis는 room directory 중심 최소 도입
- 목표: 룰/프로토콜 정확도 우선

### Phase 2 (분리/확장)

- WS/GE 물리 분리 고정
- room 샤딩 및 라우팅 고도화
- 커스텀 메트릭 기반 HPA 적용

### Phase 3 (고가용성/운영성 강화)

- MQ 도입(필요 시, GE->WS fan-out 전용)
- room drain/migration
- 장애 복구 자동화, 운영 대시보드 강화

---

## 12. 다음 구체화 TODO

1. room 라우팅 방식 확정
- `consistent-hash` vs `redis directory` 중 선택

2. Engine 상태 모델 정의
- `RoomState`, `GameState`, `TurnState`, `ScoreState` 구조체/클래스 명세

3. Snapshot 스키마와 생성 타이밍 확정
- `408` 생성 비용/빈도/캐시 정책

4. k3s 매니페스트 초안 작성
- Deployment/Service/HPA/ConfigMap/Secret

5. 부하 목표 정의
- 동시 접속자, room 수, room당 이벤트량 기준치

6. Lobby -> GE 배정 지표 명세
- `status`, `activeRooms`, `heartbeatUpdatedAt` 기준으로 owner GE 선택
- 필요 시 `loadScore` 같은 단일 부하지표로 확장

---

## 13. 현재까지 합의된 결정사항

1. 시스템 성격
- 전체는 혼합 구조로 운영한다.
- `WS`는 stateless에 가깝게 유지하고, `GE`는 stateful로 room 상태를 소유한다.

2. Actor 적용 범위
- actor 모델은 `GE` 내부 room 관리에만 적용한다.
- `transport`, `validation` 계층은 actor 대상이 아니다.
- `RoomActor = mailbox + state`이며 actor별 전용 스레드는 두지 않는다.
- 공용 worker pool과 actor별 단일 실행 보장(CAS/running flag)으로 순차 처리한다.

3. 라우팅 원칙
- 라우팅 키는 `roomId`를 사용한다.
- 동일 `roomId` 이벤트는 항상 동일 owner `GE`로 전달한다(affinity).
- owner 조회는 Directory(예: Redis `roomId -> engineId`)를 사용한다.
- stale 라우팅 시 `NOT_OWNER -> 재조회 -> 1회 재전송`으로 복구한다.

4. WS가 유지할 최소 컨텍스트
- WS는 room 내부 게임 상태를 알 필요가 없다.
- 대신 라우팅을 위해 최소한 다음 바인딩을 유지한다.
- `user/session -> roomId`
- `roomId -> owner GE`

5. Lobby와 GE 역할 분리
- Lobby가 담당: 방 생성, 랜덤 매칭 큐 처리, owner GE 배정, directory 등록
- GE가 담당: 배정된 room의 턴/점수/캔버스/타이머/스냅샷 처리
- Lobby는 GE의 상세 room runtime 상태를 추적하지 않는다.
- room lifecycle owner는 GE다.
- client는 방 생성/매칭을 위해 Lobby의 HTTP API를 직접 호출한다.
- WS는 방 생성 이후 참여 및 게임 진행을 위한 runtime gateway로 동작한다.

6. 오토스케일 운영 원칙
- WS는 연결 수/네트워크 지표 중심으로 스케일한다.
- GE는 `active_rooms`/이벤트량/CPU 중심으로 스케일한다.
- scale-in은 drain 우선으로 처리한다(신규 room 배정 중단 후 종료).
- GE drain 시간은 `30분`을 기본으로 잡는다.

7. 재연결 정책
- 재연결 유지 기능은 후속 단계로 미룬다.
- MVP는 현재 규칙대로 연결 종료 시 leave 처리 정책을 유지한다.

8. WS 책임 경계
- WS는 게임 규칙, 점수, 턴 상태, room audience 계산을 소유하지 않는다.
- WS는 envelope validation, 세션 유지, 라우팅, targeted delivery만 담당한다.
- WS는 연결 후 `afterConnectionEstablished` 단계에서 내부 join을 처리하고, 이후 runtime 요청은 모두 세션의 `roomId`를 기준으로 동일 경로로 라우팅한다.
- GE는 room 상태, 권한, 타이머, 점수, snapshot, recipient 계산을 담당한다.

9. WS liveness 처리
- `PING/PONG`은 transport-level liveness로 취급한다.
- WS는 유효 inbound 수신 시 세션 `lastSeenAt`을 갱신한다.
- 별도 고급 presence 시스템은 MVP 범위에서 제외한다.

10. WS drain/scale-in 운영 원칙
- WS Pod가 drain 상태에 들어가면 신규 연결은 차단한다.
- drain 중인 WS는 신규 delivery 대상에서 제외되어야 한다.
- 현재 시스템은 `at-most-once` 전달 모델이므로, drain 중 일부 미전달 이벤트는 허용한다.
- 이벤트 전달 완전성보다 authoritative state 기반 복구 가능성을 우선한다.

11. Lobby의 GE 선택 원칙
- Lobby는 GE 내부 방 상태를 상세 조회하지 않는다.
- 각 GE는 주기적으로 자신의 최소 상태를 heartbeat로 게시한다.
  - `engineId`
  - `status` (`ACTIVE`, `DRAINING`)
  - `activeRooms`
  - `heartbeatUpdatedAt`
- Lobby는 owner GE 배정 시 아래 순서로 후보를 고른다.
  1. `ACTIVE` 상태인 GE만 후보로 선택
  2. heartbeat가 오래된 GE 제외
  3. `activeRooms`가 가장 적은 GE 우선 선택
- 필요 시 `activeRooms` 대신 단일 `loadScore` 지표로 확장할 수 있다.
- 방 생성 후 runtime 상태 관리 책임은 GE로 넘어가며, Lobby는 directory만 유지한다.

12. GE 내부 room lifecycle 관리
- `room actor 1개 = room 1개` 기준으로 관리한다.
- room 생성 시 owner GE가 room state를 생성한다.
- private room 생성 직후 아직 입장자가 없는 상태도 GE가 소유한다.
- pending/empty/ended room은 GE 내부 정책에 따라 자동 정리할 수 있다.
- idle room 자동 정리를 위해 Caffeine eviction을 사용할 수 있으나, 제거 기준은 room 상태를 고려한 정책으로 제어한다.
