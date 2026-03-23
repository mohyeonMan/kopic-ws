# ko-pic Protocol Spec v0 (MVP v0.2)

## 1. 목적

이 문서는 MVP 기준의 `Client <-> WS` runtime 메시지 규격을 정의한다.

- 방 참여/게임 진행/그림-추측 전달
- 중간입장 상태 복구(`GAME_SNAPSHOT`)
- 간단하고 해석 여지 없는 필드 규격

범위:

- private 방 생성, random quick-join 요청은 `Lobby`의 HTTP API가 담당한다.
- 이 문서는 방 생성 이후, 참여한 클라이언트와 WS 사이의 runtime 프로토콜만 다룬다.
- 클라이언트는 WS 연결 시 `roomId`를 함께 전달한다.
- WS는 `afterConnectionEstablished` 단계에서 내부 join 처리를 GE에 전달하며, 연결 성립 이후 room 참여를 완료한다.
- 내부 join RPC는 `ack/error`만 반환하고, 실제 서버 이벤트(`301`, `408` 등)는 GE -> WS outbound 경로로 전달된다.

---

## 2. 공통 Envelope

모든 이벤트는 동일 구조를 사용한다.

```json
{
  "e": 102,
  "p": {},
  "rid": "c-1"
}
```

- `e` : 이벤트 코드 (int, required)
- `p` : payload (object, required)
- `rid` : request id (string, optional)

규칙:

- 클라이언트가 보낸 `rid`는 서버 응답 이벤트/에러에 가능한 한 그대로 포함.
- 정의되지 않은 이벤트 코드는 `ERROR(3)`로 응답.
- 클라가 보내면 안 되는 메타(`userId`, `roomId`, server time)는 서버가 채움.
- `roomId`는 runtime envelope 안에서 매번 보내지 않고, WS 연결 컨텍스트에서 확정한다.

---

## 3. 전송/진행 원칙

- 전달 모델: `at-most-once`
- WS는 재전송을 보장하지 않음
- 턴/라운드/게임 전환은 서버 타이머로 진행(클라 ACK 대기 없음)
- 상태 불일치 시 클라가 `106 GAME_SNAPSHOT_REQUEST`로 복구

전환 지연:

- 턴 종료 -> 다음 턴 시작: 3초
- 라운드 종료 -> 다음 라운드 시작: 즉시
- 게임 종료 결과 화면 유지: 8초

---

## 4. 이벤트 코드 대역

```text
0xx  연결/공통
1xx  room/runtime 입력
2xx  게임 입력
3xx  상태 이벤트
4xx  화면/결과 이벤트
9xx  에러 코드
```

---

## 5. 이벤트 정의

## 5.1 연결/공통

### 1 PING

```json
{ "e": 1, "p": {} }
```

### 2 PONG

```json
{ "e": 2, "p": {} }
```

### 3 ERROR

```json
{
  "e": 3,
  "rid": "c-1",
  "p": {
    "code": 901,
    "message": "INVALID_PAYLOAD",
    "requestEvent": 201,
    "details": {}
  }
}
```

### 4 ACK

- MVP 기본 흐름에서는 미사용(예약 코드).

---

## 5.2 room/runtime 입력 (Client -> WS)

### 101 RESERVED

- private 방 생성과 random quick-join 요청은 WS가 아니라 `Lobby`의 HTTP API가 처리한다.
- 이벤트 코드 `101`은 WS runtime 프로토콜에서 사용하지 않는다.

### 102 RESERVED

- client-originated `ROOM_JOIN` 이벤트는 사용하지 않는다.
- `roomId`는 WS handshake 컨텍스트에서 받는다.
- 실제 join 처리는 WS가 `afterConnectionEstablished` 단계에서 내부 `JOIN` lifecycle 이벤트로 GE에 전달한다.
- 내부 `JOIN` lifecycle payload에는 `nickname`이 필수다.
- `wsNodeId`는 선택값이며, WS가 알고 있으면 함께 전달해 session presence를 갱신한다.

### 103 RESERVED

- client-originated `ROOM_LEAVE` 이벤트는 사용하지 않는다.
- 사용자가 방에서 나가면 WS 세션을 종료하고, WS는 내부 `LEAVE` lifecycle 이벤트를 GE에 전달한다.

### 105 GAME_START_REQUEST

```json
{
  "e": 105,
  "rid": "c-4",
  "p": {}
}
```

규칙:

- 서버는 request payload가 아니라 현재 `Room.settings`를 읽어 게임을 시작한다.
- settings 변경은 사전에 `107 GAME_SETTINGS_UPDATE_REQUEST`로 반영한다.

게임 시작 후 다시 요청하면 `GAME_ALREADY_STARTED`.

### 106 GAME_SNAPSHOT_REQUEST

```json
{ "e": 106, "rid": "c-5", "p": {} }
```

응답:

- `408 GAME_SNAPSHOT`

### 107 GAME_SETTINGS_UPDATE_REQUEST

private room 대기실에서 host가 다음 게임 설정을 바꿀 때 사용한다.

```json
{
  "e": 107,
  "rid": "c-5-1",
  "p": {
    "roundCount": 3,
    "drawSec": 20,
    "wordChoiceSec": 10,
    "wordChoiceCount": 3,
    "drawerOrderMode": "JOIN_ORDER",
    "endMode": "FIRST_CORRECT"
  }
}
```

검증:

- `PRIVATE` room만 허용
- host만 허용
- `LOBBY` 상태에서만 허용
- `roundCount`: 3..10
- `drawSec`: 20..60
- `wordChoiceSec`: 5..15
- `wordChoiceCount`: 3..5
- `drawerOrderMode`: `JOIN_ORDER` | `RANDOM`
- `endMode`: `FIRST_CORRECT` | `TIME_OR_ALL_CORRECT`

---

## 5.3 게임 입력 (Client -> WS)

### 201 DRAW_STROKE

```json
{
  "e": 201,
  "rid": "c-6",
  "p": {
    "turnId": "t-1",
    "stroke": ["s-1", 1, 3, 4, [[0.12001, 0.25000], [0.13000, 0.27000]]]
  }
}
```

필드 규칙:

- `turnId`: 현재 턴 식별자
- `stroke`: `[strokeId, tool, colorIndex, size, points]`
- `tool`: `1=PEN`, `2=ERASER`
- `colorIndex`: 0..19
- `size`: 1..20
- `points`: 좌표 배열, 최대 64개
- 좌표계: 정규화(0.0..1.0), 소수점 최대 5자리

제한:

- 사용자당 `DRAW_STROKE` 초당 최대 30회
- 메시지 최대 8KB

권장:

- stroke는 배열 포맷을 사용한다.
- 다른 형태의 입력이 있더라도 WS 쪽에서 배열 포맷으로 변환해 전달하는 것이 가장 단순하다.

권한:

- drawer만 전송 가능
- 서버는 non-drawer participant에게만 `401 CANVAS_STROKE`를 발행한다.

### 202 DRAW_CLEAR

```json
{
  "e": 202,
  "rid": "c-7",
  "p": { "turnId": "t-1" }
}
```

규칙:

- drawer만 허용
- 서버는 non-drawer participant에게만 `402 CANVAS_CLEAR`를 발행한다.

### 204 GUESS_SUBMIT

```json
{
  "e": 204,
  "rid": "c-8",
  "p": { "turnId": "t-1", "text": "사 과!" }
}
```

의미:

- `GUESS_SUBMIT`은 클라이언트가 보내는 단일 입력 이벤트다.
- 이 입력은 일반 채팅, 오답 추측, drawer 채팅, 정답 후 전용 채팅을 모두 포함한다.
- 서버는 이를 판정한 뒤
  - 일반 메시지/오답/전용 채팅이면 `403 GUESS_MESSAGE`
  - 정답이면 `404 GUESS_CORRECT`
  로 발행한다.

정규화 비교:

1. lowercase
2. 공백 제거
3. `.`, `,`, `!`, `?` 제거
4. equals 비교

레이트리밋:

- 사용자당 초당 최대 5회

### 205 WORD_CHOICE

```json
{
  "e": 205,
  "rid": "c-9",
  "p": { "choiceIndex": 1 }
}
```

규칙:

- drawer만 허용
- 제한시간 10초 내 미선택 시 서버 랜덤 선택
- 서버는 사용자 선택 입력을 처리할 때도 먼저 현재 deadline 초과 여부를 확인한다.
- 이미 deadline이 지났고 turn이 아직 `WORD_CHOICE` 상태면 서버 강제 선택을 우선 적용한다.

---

## 5.4 상태 이벤트 (Server -> Client)

### 301 ROOM_JOINED

```json
{
  "e": 301,
  "p": {
    "userId": "u2",
    "nickname": "jhp"
  }
}
```

규칙:

- 기존 participant들에게만 발행한다.
- joiner 자신에게는 전체 초기 동기화용 `408 GAME_SNAPSHOT`을 발행한다.
- 이미 같은 `userId`가 room participant에 있으면 중복 join으로 간주하고 추가 발행 없이 무시한다.

### 302 GAME_STARTED

```json
{
  "e": 302,
  "p": {
    "gameId": "g1",
    "settings": {
      "roundCount": 3,
      "drawSec": 20,
      "wordChoiceSec": 10,
      "wordChoiceCount": 3,
      "drawerOrderMode": "JOIN_ORDER",
      "endMode": "FIRST_CORRECT"
    }
  }
}
```

### 303 ROUND_STARTED

```json
{
  "e": 303,
  "p": { "gameId": "g1", "round": 1 }
}
```

### 304 TURN_STARTED

이번 턴에 누가 drawer인지 알린다.

```json
{
  "e": 304,
  "p": {
    "gameId": "g1",
    "round": 1,
    "turn": 1,
    "turnId": "t-1",
    "drawerUserId": "u1"
  }
}
```

규칙:

- same room participant 전체에게 발행한다.
- 이 이벤트는 "이번 턴에 누가 그릴 차례인지"만 알린다.

### 305 TURN_ENDED

```json
{
  "e": 305,
  "p": {
    "gameId": "g1",
    "round": 1,
    "turn": 1,
    "turnId": "t-1",
    "reason": "FIRST_CORRECT",
    "earnedScores": [
      { "userId": "u1", "score": 1 },
      { "userId": "u2", "score": 1 }
    ],
    "scores": [
      { "userId": "u1", "score": 2 },
      { "userId": "u2", "score": 1 }
    ]
  }
}
```

`reason`:

- `FIRST_CORRECT`
- `ALL_CORRECT`
- `TIMEOUT`
- `DRAWER_LEFT`

규칙:

- 턴 종료와 함께 이번 턴 획득 점수와, 이번 턴 반영이 끝난 최신 scoreboard를 함께 보낸다.
- `earnedScores`에는 이번 턴에서 누가 몇 점을 얻었는지 담는다.
- 정답자가 1명 이상이면 drawer도 해당 턴 점수를 얻을 수 있다.
- 이 이벤트를 최소 3초간 보여준 뒤에만 다음 턴/라운드/게임 종료 후속 전이로 넘어간다.

### 306 ROUND_ENDED

```json
{
  "e": 306,
  "p": { "gameId": "g1", "round": 1 }
}
```

### 307 GAME_ENDED

```json
{
  "e": 307,
  "p": {
    "gameId": "g1",
    "ranking": [
      { "userId": "u2", "score": 3 },
      { "userId": "u1", "score": 2 }
    ],
    "resultViewSec": 8
  }
}
```

### 308 GAME_SETTINGS_UPDATED

대기실에서 설정이 변경되면 room 전체에 전파한다.

```json
{
  "e": 308,
  "p": {
    "settings": {
      "roundCount": 3,
      "drawSec": 20,
      "wordChoiceSec": 10,
      "wordChoiceCount": 3,
      "endMode": "FIRST_CORRECT"
    }
  }
}
```

### 309 ROOM_LEFT

```json
{
  "e": 309,
  "p": {
    "userId": "u2"
  }
}
```

규칙:

- leave 이후 남아 있는 participant들에게 발행한다.
- 떠난 사용자 자신에게는 별도 room 상태 이벤트를 보내지 않는다.

### 310 DRAWING_STARTED

drawer에게 드로잉 phase 시작과 실제 그리기 시간 시작을 알린다.

```json
{
  "e": 310,
  "p": {
    "gameId": "g1",
    "round": 1,
    "turn": 1,
    "turnId": "t-1",
    "drawerUserId": "u1",
    "durationSec": 20
  }
}
```

규칙:

- drawer 1명에게만 발행한다.

### 311 TURN_STATE

현재 턴 phase 상태를 non-drawer participant에게 알린다.

```json
{
  "e": 311,
  "p": {
    "gameId": "g1",
    "round": 1,
    "turn": 1,
    "turnId": "t-1",
    "drawerUserId": "u1",
    "phase": "WORD_CHOICES_GIVEN",
    "timeoutSec": 10
  }
}
```

`phase`:

- drawer에게 `406 WORD_CHOICES`를 보낸 직후, 나머지 participant에게 `phase=WORD_CHOICES_GIVEN`
- `WORD_CHOICES_GIVEN`
- `DRAWING_STARTED`

예시:

- drawer에게 `406 WORD_CHOICES`를 보낸 직후, 나머지 participant에게 `311 TURN_STATE(phase=WORD_CHOICES_GIVEN)`
- drawer가 선택하거나 timeout 자동선택이 끝난 뒤, non-drawer participant에게 `311 TURN_STATE(phase=DRAWING_STARTED)`

---

## 5.5 화면/결과 이벤트 (Server -> Client)

### 401 CANVAS_STROKE

```json
{
  "e": 401,
  "p": {
    "turnId": "t-1",
    "drawerUserId": "u1",
    "stroke": ["s-1", 1, 3, 4, [[0.12001, 0.25000], [0.13000, 0.27000]]]
  }
}
```

### 402 CANVAS_CLEAR

```json
{
  "e": 402,
  "p": { "turnId": "t-1", "drawerUserId": "u1" }
}
```

### 403 GUESS_MESSAGE

오답 또는 일반 메시지.

```json
{
  "e": 403,
  "p": { "userId": "u2", "nickname": "jhp", "text": "바나나", "turnId": "t-1" }
}
```

가시성:

- 미정답자 메시지: 전체
- 정답 처리된 사용자 이후 메시지: 현재 턴의 정답자 집합 + drawer만
- drawer 메시지: 현재 턴의 정답자 집합 + drawer만

### 404 GUESS_CORRECT

```json
{
  "e": 404,
  "p": { "userId": "u2", "nickname": "jhp", "turnId": "t-1" }
}
```

### 405 RESERVED

- 점수 변경은 별도 이벤트로 분리하지 않는다.
- 최신 scoreboard는 `305 TURN_ENDED` payload 안에 포함한다.

### 406 WORD_CHOICES

drawer 전용.

```json
{
  "e": 406,
  "p": {
    "choices": ["사과", "바나나", "자동차"],
    "timeoutSec": 10
  }
}
```

### 408 GAME_SNAPSHOT

입장 성공 직후/재요청 시 현재 상태를 1회 전달.

```json
{
  "e": 408,
  "p": {
    "room": {
      "roomId": "r1",
      "roomType": "private",
      "roomCode": "A1B2C3",
      "hostUserId": "u1",
      "state": "RUNNING"
    },
    "participants": [
      { "userId": "u1", "nickname": "aaa" },
      { "userId": "u2", "nickname": "jhp" }
    ],
    "game": {
      "status": "RUNNING",
      "gameId": "g1",
      "settings": {
      "roundCount": 3,
      "drawSec": 20,
      "wordChoiceSec": 10,
      "wordChoiceCount": 3,
      "drawerOrderMode": "JOIN_ORDER",
      "endMode": "FIRST_CORRECT"
    },
      "round": 1,
      "turn": 1,
      "turnId": "t-1",
      "drawerUserId": "u1",
      "remainingSec": 12
    },
    "canvas": {
      "strokes": [
        ["s-1", 1, 3, 4, [[0.12001, 0.25000], [0.13000, 0.27000]]]
      ]
    },
    "scores": [
      { "userId": "u1", "score": 2 },
      { "userId": "u2", "score": 1 }
    ]
  }
}
```

게임 시작 전 대기실 상태에서는:

- `room.state = "LOBBY"`
- `canvas.strokes = []`

규칙:

- `106 GAME_SNAPSHOT_REQUEST`도 room mailbox 안에서 현재 authoritative state를 읽어 응답한다.
- 현재 room participant가 아닌 사용자의 `106 GAME_SNAPSHOT_REQUEST`는 무시한다.

---

## 6. 에러 코드

```text
900 INVALID_EVENT
901 INVALID_PAYLOAD
902 ROOM_NOT_FOUND
903 ROOM_FULL
904 NOT_ROOM_HOST
905 GAME_ALREADY_STARTED
906 NOT_DRAWER
907 GAME_NOT_RUNNING
908 UNAUTHORIZED
909 INTERNAL_ERROR
910 RATE_LIMITED
911 NOT_OWNER
912 ROOM_MIGRATING
```

---

## 7. 룸 코드/세션 규칙

- roomCode는 6자 문자열
- 생성 시 중복 불가
- 방 종료 후 재사용 가능
- MVP에서는 연결 종료 시 즉시 leave 처리(재접속 유예 없음)

---

## 8. 처리 흐름 요약

1. Client -> Lobby HTTP (방 생성/매칭 등 pre-entry flow, 본 문서 범위 밖)
2. Lobby가 roomId 발급 및 owner GE 배정
3. Client -> WS 연결 시 `roomId` 전달
4. WS가 `afterConnectionEstablished` 단계에서 내부 join을 GE에 전달
5. owner GE가 join을 수락하고 room 참여를 처리
6. 참여 완료 후 기존 participant에게는 `301`, joiner에게는 `408`이 GE -> WS outbound 경로로 전달
7. 이후 모든 runtime 요청은 세션의 `roomId` 기준으로 GE에 라우팅
8. Server -> Client 상태/화면 이벤트 송신
9. 불일치 시 `106` 요청으로 `408` 재동기화
