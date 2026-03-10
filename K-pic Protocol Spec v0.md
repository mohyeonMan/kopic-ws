# K-pic Protocol Spec v0 (MVP v0.2)

## 1. 목적

이 문서는 MVP 기준의 `Client <-> WS` 메시지 규격을 정의한다.

- 방 참여/게임 진행/그림-추측 전달
- 중간입장 상태 복구(`GAME_SNAPSHOT`)
- 간단하고 해석 여지 없는 필드 규격

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

---

## 3. 전송/진행 원칙

- 전달 모델: `at-most-once`
- WS는 재전송을 보장하지 않음
- 턴/라운드/게임 전환은 서버 타이머로 진행(클라 ACK 대기 없음)
- 상태 불일치 시 클라가 `106 GAME_SNAPSHOT_REQUEST`로 복구

전환 지연:

- 턴 종료 -> 다음 턴 시작: 3초
- 라운드 종료 -> 다음 라운드 시작: 4초
- 게임 종료 결과 화면 유지: 8초

---

## 4. 이벤트 코드 대역

```text
0xx  연결/공통
1xx  방/대기실 입력
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

## 5.2 방/대기실 입력 (Client -> WS)

### 101 ROOM_CREATE_PRIVATE

```json
{
  "e": 101,
  "rid": "c-1",
  "p": { "nickname": "jhp" }
}
```

응답 성공 시:

- `301 ROOM_STATE`
- `408 GAME_SNAPSHOT` (게임 전이면 `game.status = "LOBBY"`)

### 102 ROOM_JOIN

```json
{
  "e": 102,
  "rid": "c-2",
  "p": {
    "roomCode": "A1B2C3",
    "nickname": "jhp"
  }
}
```

규칙:

- private 초대링크 진입도 내부적으로 `roomCode`를 포함한 동일 요청 사용.
- random 확장 시 `roomCode` 없는 매칭요청 모드 허용 가능.

응답 성공 시:

- `301 ROOM_STATE`
- `408 GAME_SNAPSHOT` 즉시 전송

### 103 ROOM_LEAVE

```json
{ "e": 103, "rid": "c-3", "p": {} }
```

### 105 GAME_START_REQUEST

```json
{
  "e": 105,
  "rid": "c-4",
  "p": {
    "roundCount": 3,
    "drawSec": 20,
    "wordChoiceCount": 3,
    "endMode": "FIRST_CORRECT"
  }
}
```

검증:

- `roundCount`: 3..10
- `drawSec`: 20..60
- `wordChoiceCount`: 3..5
- `endMode`: `FIRST_CORRECT` | `TIME_OR_ALL_CORRECT`
- `wordChoiceSec`: 10 고정(입력값 받지 않음)

게임 시작 후 다시 요청하면 `GAME_ALREADY_STARTED`.

### 106 GAME_SNAPSHOT_REQUEST

```json
{ "e": 106, "rid": "c-5", "p": {} }
```

응답:

- `408 GAME_SNAPSHOT`

---

## 5.3 게임 입력 (Client -> WS)

### 201 DRAW_STROKE

```json
{
  "e": 201,
  "rid": "c-6",
  "p": {
    "turnId": "t-1",
    "strokeId": "s-1",
    "tool": 1,
    "colorIndex": 3,
    "size": 4,
    "points": [[0.12001, 0.25000], [0.13000, 0.27000]]
  }
}
```

필드 규칙:

- `turnId`: 현재 턴 식별자
- `tool`: `1=PEN`, `2=ERASER`
- `colorIndex`: 0..19
- `size`: 1..20
- `points`: 좌표 배열, 최대 64개
- 좌표계: 정규화(0.0..1.0), 소수점 최대 5자리

제한:

- 사용자당 `DRAW_STROKE` 초당 최대 20회
- 메시지 최대 8KB

권한:

- drawer만 전송 가능

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

### 204 GUESS_SUBMIT

```json
{
  "e": 204,
  "rid": "c-8",
  "p": { "text": "사 과!" }
}
```

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

---

## 5.4 상태 이벤트 (Server -> Client)

### 301 ROOM_STATE

```json
{
  "e": 301,
  "p": {
    "roomId": "r1",
    "roomType": "private",
    "roomCode": "A1B2C3",
    "hostUserId": "u1",
    "participants": [
      { "userId": "u1", "name": "aaa" },
      { "userId": "u2", "name": "jhp" }
    ]
  }
}
```

규칙:

- random room은 `hostUserId`를 `null` 또는 미포함.

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

턴 시작 시 서버는 캔버스를 초기화한다.

```json
{
  "e": 304,
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

### 305 TURN_ENDED

```json
{
  "e": 305,
  "p": {
    "gameId": "g1",
    "round": 1,
    "turn": 1,
    "reason": "FIRST_CORRECT"
  }
}
```

`reason`:

- `FIRST_CORRECT`
- `ALL_CORRECT`
- `TIMEOUT`
- `DRAWER_LEFT`

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

---

## 5.5 화면/결과 이벤트 (Server -> Client)

### 401 CANVAS_STROKE

```json
{
  "e": 401,
  "p": {
    "turnId": "t-1",
    "drawerUserId": "u1",
    "strokeId": "s-1",
    "tool": 1,
    "colorIndex": 3,
    "size": 4,
    "points": [[0.12001, 0.25000], [0.13000, 0.27000]]
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
  "p": { "userId": "u2", "name": "jhp", "text": "바나나" }
}
```

가시성:

- 미정답자 메시지: 전체
- 정답자/ drawer 메시지: 정답자 + drawer만

### 404 GUESS_CORRECT

```json
{
  "e": 404,
  "p": { "userId": "u2", "name": "jhp" }
}
```

### 405 SCORE_UPDATED

MVP 더미 점수 규칙:

- guesser 정답 1회당 `+1`
- drawer는 정답자 1명당 `+1`

```json
{
  "e": 405,
  "p": {
    "scores": [
      { "userId": "u1", "score": 2 },
      { "userId": "u2", "score": 1 }
    ]
  }
}
```

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
      "hostUserId": "u1"
    },
    "participants": [
      { "userId": "u1", "name": "aaa" },
      { "userId": "u2", "name": "jhp" }
    ],
    "game": {
      "status": "RUNNING",
      "gameId": "g1",
      "settings": {
        "roundCount": 3,
        "drawSec": 20,
        "wordChoiceSec": 10,
        "wordChoiceCount": 3,
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
        {
          "strokeId": "s-1",
          "tool": 1,
          "colorIndex": 3,
          "size": 4,
          "points": [[0.12001, 0.25000], [0.13000, 0.27000]]
        }
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

- `game.status = "LOBBY"`
- `canvas.strokes = []`

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
```

---

## 7. 룸 코드/세션 규칙

- roomCode는 6자 문자열
- 생성 시 중복 불가
- 방 종료 후 재사용 가능
- MVP에서는 연결 종료 시 즉시 leave 처리(재접속 유예 없음)

---

## 8. 처리 흐름 요약

1. Client -> WS (`e`, `p`, optional `rid`)
2. WS 기본 검증/세션 결합
3. RoomServer/GE 라우팅 (roomId 기준 일관 라우팅)
4. Server -> Client 상태/화면 이벤트 송신
5. 입장 성공 시 `301 + 408` 즉시 전송
6. 불일치 시 `106` 요청으로 `408` 재동기화
