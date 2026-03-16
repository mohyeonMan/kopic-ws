# ko-pic Lobby API Spec v0

## 1. 목적

이 문서는 MVP 기준의 `Client <-> Lobby` 및 `GE <-> Lobby` HTTP API를 정의한다.

범위:

- private room 생성 요청 중개
- random quick-join
- roomCode 기반 room 조회
- scale-in waiting room 재배정

비범위:

- WebSocket runtime 메시지
- game start / draw / guess / snapshot
- running game migration

---

## 2. 기본 원칙

- `Lobby`는 room control plane이다.
- `Lobby`는 room runtime state의 authoritative owner가 아니다.
- `Lobby`는 owner GE 배정, room 생성/참여 요청 중개, 재배정 조정만 담당한다.
- `Lobby`는 owner GE를 선택하고, 해당 GE에 room 생성/참여 요청을 전달한다.
- random quick-join에서 Redis joinable index는 후보 탐색용이고, 최종 join 승인 권한은 owner GE에 있다.

---

## 3. 공통 규칙

### 3.1 Base Path

public API:

- `/api/lobby`

internal API:

- `/internal/lobby`

### 3.2 Content-Type

- request/response body는 기본적으로 `application/json`

### 3.3 인증

MVP에서는 아래 정도의 전제만 둔다.

- public API는 gateway 또는 상위 인증 계층을 거쳐 `userId`가 보강된 상태라고 가정할 수 있다.
- internal API는 GE/Lobby 간 trusted network 또는 내부 인증 헤더를 사용한다.

구체적인 인증 방식은 이 문서 범위 밖이다.

### 3.4 공통 에러 응답

```json
{
  "code": "ROOM_NOT_FOUND",
  "message": "room not found",
  "details": {}
}
```

필드:

- `code`: string, required
- `message`: string, required
- `details`: object, optional

### 3.5 공통 에러 코드

- `INVALID_REQUEST`
- `UNAUTHORIZED`
- `ROOM_NOT_FOUND`
- `ROOM_CODE_NOT_FOUND`
- `ROOM_ALREADY_CLOSED`
- `NO_AVAILABLE_ENGINE`
- `JOIN_NOT_ALLOWED`
- `JOIN_ALREADY_MEMBER`
- `ROOM_REASSIGNMENT_NOT_ALLOWED`
- `INTERNAL_ERROR`

---

## 4. 리소스 개요

### 4.1 Lobby Entry

클라이언트가 WS 연결 전에 받는 최소 entry 메타다.

```json
{
  "roomId": "r-123",
  "roomType": "random",
  "ws": {
    "roomId": "r-123"
  }
}
```

MVP에서는 owner GE route를 직접 클라이언트에 노출하지 않는다.  
클라이언트는 `roomId`를 받아 WS에 연결하고, WS가 Redis routing hint를 조회해 owner GE로 라우팅한다.

### 4.2 Engine Presence

```json
{
  "engineId": "ge-1",
  "status": "ACTIVE",
  "activeRooms": 42,
  "heartbeatUpdatedAt": "2026-03-15T10:15:30Z"
}
```

---

## 5. Public API

## 5.1 Private Room 생성

### `POST /api/lobby/rooms/private`

설명:

- owner GE에 private room 생성을 요청한다.
- owner GE가 room 생성, roomId 생성, roomCode 발급에 성공하면 `Lobby`는 roomCode와 entry를 반환한다.

request:

```json
{
  "userId": "u-1",
  "name": "jihoon"
}
```

필드:

- `userId`: string, required
- `name`: string, required
- private room의 초기 게임 설정은 request에서 받지 않는다.
- owner GE가 기본 설정으로 room을 생성한다.

response `201 Created`:

```json
{
  "roomId": "r-123",
  "roomType": "private",
  "roomCode": "A1B2C3",
  "entry": {
    "roomId": "r-123",
    "roomType": "private",
    "ws": {
      "roomId": "r-123"
    }
  }
}
```

에러:

- `400 INVALID_REQUEST`
- `503 NO_AVAILABLE_ENGINE`

---

## 5.2 Random Quick-Join

### `POST /api/lobby/matchmaking/random`

설명:

- join 가능한 existing random room을 우선 탐색한다.
- 적합한 room이 없으면 owner GE에 새 random room 생성을 요청한다.
- 최종 join 승인 권한은 owner GE에 있다.

request:

```json
{
  "userId": "u-2",
  "name": "guest-1"
}
```

필드:

- `userId`: string, required
- `name`: string, required

response `200 OK`:

```json
{
  "matched": true,
  "created": false,
  "roomId": "r-777",
  "roomType": "random",
  "entry": {
    "roomId": "r-777",
    "roomType": "random",
    "ws": {
      "roomId": "r-777"
    }
  }
}
```

새 room을 만든 경우 예시:

```json
{
  "matched": true,
  "created": true,
  "roomId": "r-888",
  "roomType": "random",
  "entry": {
    "roomId": "r-888",
    "roomType": "random",
    "ws": {
      "roomId": "r-888"
    }
  }
}
```

동작 규칙:

1. `Lobby`가 Redis의 `rooms:random:joinable`에서 후보 room을 조회한다.
2. 각 후보의 `roomId -> ownerEngineId`를 조회한다.
3. owner GE에 실제 join 가능 여부를 질의한다.
4. GE가 join 승인 시 participant 반영과 route/joinable 갱신을 수행한다.
5. 모든 후보가 실패하면 `Lobby`가 owner GE를 고르고 새 random room 생성을 요청한다.
6. GE가 새 random room 생성에 성공하면 route/joinable을 반영한다.

에러:

- `400 INVALID_REQUEST`
- `409 JOIN_NOT_ALLOWED`
- `503 NO_AVAILABLE_ENGINE`

비고:

- 이 API는 큐 등록 API가 아니다.
- 요청 1건은 "기존 random room 참가 시도 또는 신규 random room 생성 요청"으로 즉시 처리된다.

---

## 5.3 Room Lookup By Code

### `GET /api/lobby/rooms/by-code/{roomCode}`

설명:

- roomCode로 private room entry를 조회한다.
- 주 목적은 초대 링크/코드 진입 시 `roomId`를 얻는 것이다.

response `200 OK`:

```json
{
  "roomId": "r-123",
  "roomType": "private",
  "roomCode": "A1B2C3",
  "entry": {
    "roomId": "r-123",
    "roomType": "private",
    "ws": {
      "roomId": "r-123"
    }
  }
}
```

에러:

- `404 ROOM_CODE_NOT_FOUND`

---

## 6. Internal API

## 6.1 Waiting Room Reassignment 요청

### `POST /internal/lobby/migrations/rooms/{roomId}/reassign`

설명:

- source GE가 waiting room 재배정을 요청한다.
- 대상 room은 `LOBBY` 또는 waiting 상태여야 한다.
- `Lobby`는 target GE를 선택하고, source가 target을 직접 호출할 수 있도록 target endpoint를 반환한다.
- `Lobby`는 room snapshot import나 owner pointer 갱신을 수행하지 않는다.

request:

```json
{
  "sourceEngineId": "ge-1"
}
```

필드:

- `sourceEngineId`: string, required

response `200 OK`:

```json
{
  "roomId": "r-123",
  "sourceEngineId": "ge-1",
  "targetEngineId": "ge-3",
  "targetEngineEndpoint": "http://ge-3.ge.svc.cluster.local:8080",
  "reassigned": true
}
```

에러:

- `400 INVALID_REQUEST`
- `409 ROOM_REASSIGNMENT_NOT_ALLOWED`
- `503 NO_AVAILABLE_ENGINE`

규칙:

1. `Lobby`는 source room owner가 요청의 `sourceEngineId`와 일치하는지만 확인한다.
2. target GE는 일반 room 배정과 같은 selection policy로 고른다.
3. source GE는 반환된 endpoint로 target GE에 room snapshot을 직접 전송한다.
4. owner pointer 갱신은 source GE가 target prepare 성공 이후에 Redis에서 CAS로 수행한다.
5. migration 중 `random` room은 `rooms:random:joinable`에서 제외되어야 한다.

---

## 7. Random Quick-Join 정합성 원칙

random quick-join의 핵심은 아래다.

1. `Lobby`는 Redis joinable index를 보고 후보 room을 찾는다.
2. `Lobby`는 candidate room을 최종 확정하지 않는다.
3. owner GE가 메모리 room state 기준으로 join 가능 여부를 최종 승인한다.
4. join 성공 후 Redis joinable index 갱신도 GE가 수행한다.

즉 아래는 금지한다.

- `Lobby`가 Redis 인덱스만 보고 join 확정
- `Lobby`가 GE 승인 전 join 결과를 먼저 반영
- Redis joinable index를 authoritative participant state처럼 간주

---

## 8. 상태 코드 가이드

- `200 OK`: 조회/quick-join 성공/재배정 성공
- `201 Created`: private room 생성 요청 성공
- `400 Bad Request`: 형식/필수값 오류
- `401 Unauthorized`: 인증 실패
- `404 Not Found`: room/roomCode 없음
- `409 Conflict`: 현재 상태에서 처리 불가
- `503 Service Unavailable`: 배정 가능한 GE 없음
- `500 Internal Server Error`: 내부 오류

---

## 9. 향후 확장 포인트

- 공개 room 목록 API
- room 검색/필터 API
- random quick-join candidate selection tuning
- 운영용 engine 상태 조회 API
- internal migration retry/cancel API (필요 시 source/GE 측으로 위임)

---

## 10. 최종 요약

`Lobby` API는 "room entry를 발급하고, room을 찾고, random room에 빠르게 붙이고, 필요 시 room owner를 다시 배정하는 API"로 유지하는 것이 맞다.

이 문서 기준으로:

- public API는 client entry flow를 담당하고
- internal API는 GE scale-in orchestration에서 target 할당/중개를 담당한다.
