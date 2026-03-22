# ko-pic Lobby-GE Internal API Contract v0

## 1. 목적

이 문서는 MVP 기준 `Lobby <-> GE` 내부 계약을 고정한다.

- 범위: Lobby가 GE를 호출하는 3개 API + GE가 Lobby를 호출하는 migration coordination API
- 기준 소스:
  - `../kopic-lobby/src/main/java/io/jhpark/kopic/lobby/engine/app/*`
  - `../kopic-lobby/src/main/java/io/jhpark/kopic/lobby/migration/*`
  - `docs/ko-pic Lobby API Spec v0.md`

---

## 2. 역할 경계

1. Lobby
- owner GE 선택
- room 생성/quick-join 요청 중개
- migration target 할당

2. GE
- room runtime authoritative owner
- 최종 join 승인
- route/joinable/presence 갱신
- migration snapshot/handoff/CAS 갱신 실행

핵심 원칙:

- Redis joinable index는 후보 탐색용
- 최종 승인/반영 권한은 GE

---

## 3. Lobby -> GE 계약

아래 3개는 `kopic-lobby` 포트 시그니처를 기준으로 고정한다.

## 3.1 Private Room 생성

Lobby 포트:

```java
PrivateRoomCreated createPrivateRoom(String engineId, CreatePrivateRoomCommand command)
```

현재 command:

```java
record CreatePrivateRoomCommand(String userId, String name)
```

### v0 확정(정책 반영)

- `PRIVATE` room capacity는 생성 시 입력받아야 한다.
- 따라서 command는 아래로 확장 권장:

```java
record CreatePrivateRoomCommand(String userId, String name, int capacity)
```

검증:

- `capacity` 범위는 GE에서 검증
- 유효하지 않으면 `INVALID_REQUEST` 계열 에러 반환

성공 응답:

- `PrivateRoomCreated(RoomEntry)`
- `RoomEntry`: `roomId`, `roomType=PRIVATE`, `roomCode`, `ownerEngineId`

## 3.2 Random Room 생성

Lobby 포트:

```java
RandomRoomCreated createRandomRoom(String engineId, CreateRandomRoomCommand command)
```

command:

```java
record CreateRandomRoomCommand(String userId, String name)
```

정책:

- `RANDOM` room capacity는 8명 고정
- 요청에서 capacity를 받지 않는다

성공 응답:

- `RandomRoomCreated(RoomEntry)`

## 3.3 Random Quick-Join 승인

Lobby 포트:

```java
QuickJoinResult tryJoinRandomRoom(String engineId, String roomId, JoinUserCommand command)
```

command:

```java
record JoinUserCommand(String userId, String name)
```

응답:

```java
record QuickJoinResult(boolean joined, boolean created, RoomEntry roomEntry)
```

규칙:

- `joined=true`면 GE가 participant 반영을 완료한 상태여야 한다.
- join 성공 후 route/joinable 반영도 GE가 수행한다.
- `joined=false`면 Lobby는 다음 후보 room 재시도 또는 신규 random 생성으로 fallback.

권장 보강(v1 후보):

- `joined=false` 사유 전달 필드(`reason`) 추가
  - `ROOM_FULL`, `ROOM_CLOSED`, `NOT_OWNER`, `MIGRATING` 등

---

## 4. GE -> Lobby 계약

## 4.1 Waiting Room Reassign 요청

API:

- `POST /internal/lobby/migrations/rooms/{roomId}/reassign`

request:

```json
{ "sourceEngineId": "ge-1" }
```

response:

```json
{
  "roomId": "r-123",
  "sourceEngineId": "ge-1",
  "targetEngineId": "ge-3",
  "targetEngineEndpoint": "http://ge-3.ge.svc.cluster.local:8080",
  "reassigned": true
}
```

오류:

- `404 ROOM_NOT_FOUND`
- `409 ROOM_REASSIGNMENT_NOT_ALLOWED`
- `503 NO_AVAILABLE_ENGINE`

규칙:

1. Lobby는 source owner 일치 여부만 검증
2. target GE 선택 후 endpoint 반환
3. snapshot import/CAS owner update는 source/target GE가 수행

---

## 5. Presence 계약 (API가 아닌 데이터 계약)

Lobby는 GE를 polling하지 않고 Redis presence를 읽는다.

필수 필드:

- `engineId`
- `endpoint`
- `status` (`ACTIVE`, `DRAINING`)
- `activeRooms`
- `heartbeatUpdatedAt`

GE는 heartbeat를 주기 갱신하고, stale 판정은 Lobby가 수행한다.

---

## 6. 에러/상태 코드 정렬

Lobby 외부 API 에러코드와 GE 내부 사유를 맞춰야 한다.

권장 매핑:

- GE `no-available-capacity` -> Lobby `409 JOIN_NOT_ALLOWED`
- GE `target-not-available` -> Lobby `503 NO_AVAILABLE_ENGINE`
- GE validation fail -> Lobby `400 INVALID_REQUEST`

---

## 7. 현재 코드 기준 갭

1. `CreatePrivateRoomCommand`에 `capacity` 없음
- 정책상 필수이므로 DTO/포트 확장 필요

2. `QuickJoinResult`에 실패 사유 필드 없음
- fallback 관측성 위해 `reason` 추가 권장

3. 계약 문서상 request/response error body 표준화 필요
- 현재는 도메인 객체 중심, transport 오류 스키마는 약함

---

## 8. 테스트 최소 시나리오

1. private create 성공 (capacity 포함)
2. private create 실패 (capacity 범위 오류)
3. random quick-join 후보 성공
4. random quick-join 후보 실패 후 신규 random 생성 성공
5. reassign 요청 source owner mismatch(409)
6. reassign 요청 성공 후 source가 target endpoint를 수신
