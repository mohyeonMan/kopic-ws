# ko-pic Game Flow v0

## 1. 목적

이 문서는 `ko-pic Game Rules`의 보완 자료다.

- `Game Rules`가 규칙을 정의한다.
- 이 문서는 실제 처리 순서를 정의한다.

즉 이 문서는 "어떤 입력이 들어오면, GE가 room authoritative state를 어떤 순서로 바꾸고, 누구에게 어떤 이벤트를 발행하고, 다음 작업을 언제 예약하는가"만 설명한다.

---

## 2. 전제

- authoritative current state는 `RoomSlot` 안에 있다.
- `Room`, `Game`, `Round`, `Turn`은 mutable state entity다.
- 모든 상태 변경은 room mailbox 안에서만 수행한다.
- 후속 작업은 직접 함수 호출로 이어붙이지 않고, 다시 room mailbox로 들어오게 한다.
- snapshot은 별도 저장소를 두지 않고, 응답 시점에 현재 room authoritative state를 읽어 `408 GAME_SNAPSHOT` payload로 조립한다.

클라이언트/WS lifecycle 입력:

- `ROOM_JOIN`
- `ROOM_LEAVE`
- `GAME_SETTINGS_UPDATE_REQUEST`
- `GAME_START_REQUEST`
- `GAME_SNAPSHOT_REQUEST`
- `DRAW_STROKE`
- `DRAW_CLEAR`
- `GUESS_SUBMIT`
- `WORD_CHOICE`

서버 발행 이벤트:

- `301 ROOM_JOINED`
- `302 GAME_STARTED`
- `303 ROUND_STARTED`
- `304 TURN_STARTED`
- `305 TURN_ENDED`
- `306 ROUND_ENDED`
- `307 GAME_ENDED`
- `308 GAME_SETTINGS_UPDATED`
- `309 ROOM_LEFT`
- `310 DRAWING_STARTED`
- `311 TURN_STATE`
- `401 CANVAS_STROKE`
- `402 CANVAS_CLEAR`
- `403 GUESS_MESSAGE`
- `404 GUESS_CORRECT`
- `406 WORD_CHOICES`
- `408 GAME_SNAPSHOT`

전환 지연:

- 게임 시작 -> 첫 라운드 시작: 2초
- 라운드 시작 -> 첫 word choice turn 시작: 2초
- 턴 종료 -> 다음 턴 시작: 3초
- 라운드 종료 -> 다음 라운드 시작: 즉시
- 게임 종료 -> 결과 화면 유지: 8초

---

## 3. 공통 처리 원칙

### 3.0 내부 flow job

사용자 입력이 없는 동안에도 게임은 계속 진행돼야 한다.

따라서 아래 종류의 내부 flow job을 미리 선언해두고, `GameFlowService`는 필요할 때 이 job들을 `followUp`으로 예약하는 방식으로 동작한다.

- round start job
- word choice turn start job
- word choice timeout job
- drawing timeout job
- turn end job
- round end job
- game end job
- result view end job

즉 `GameFlowService`는 긴 진행 로직을 끝까지 직접 들고 있지 않고, "현재 입력을 처리한 뒤 다음에 필요한 내부 job이 무엇인지"만 결정해 mailbox에 다시 넣는다.

### 3.1 stale event 방어

게임 입력을 처리할 때는 현재 room 상태 기준으로 아래를 확인한다.

1. current game이 존재하는지
2. current round가 존재하는지
3. current turn이 존재하는지
4. current turn state가 기대 상태와 같은지
5. payload의 `turnId`가 있으면 current turn과 같은지
6. 이미 종료된 turn이 아닌지
7. 이미 정답 처리된 user가 아닌지

### 3.2 발행 순서 원칙

한 입력이 여러 서버 이벤트를 만들 수 있으면, 클라이언트가 이해하기 쉬운 순서로 발행한다.

예:

1. 정답 판정
2. `404 GUESS_CORRECT`
3. 필요 시 즉시 `305 TURN_ENDED`

---

## 4. 입장 플로우

### 4.1 room join

입력:

- WS lifecycle `JOIN`

흐름:

1. payload에 `nickname`이 있는지 확인한다.
2. room에 participant를 추가한다.
2. 이미 같은 `userId`가 참여 중이면 중복 join으로 간주하고 no-op 처리한다.
3. participant 목록을 최신화한다.
4. `Room.version`을 증가시킨다.
5. 기존 participant들에게 `301 ROOM_JOINED`를 발행한다.
6. 이때 `301 ROOM_JOINED`에는 `userId`, `nickname`을 담는다.
7. joiner에게 현재 room authoritative state를 읽어 `408 GAME_SNAPSHOT`을 발행한다.
8. 이때 `408 GAME_SNAPSHOT`에는 room 메타, participants(`userId`, `nickname`), current game, current round, current turn, current canvas, current scores를 담는다.

---

## 5. 대기실 설정 변경 플로우

### 5.1 private room settings update

입력:

- `107 GAME_SETTINGS_UPDATE_REQUEST`

흐름:

1. room type이 `PRIVATE`인지 확인한다.
2. 요청 사용자가 host인지 확인한다.
3. room state가 `LOBBY`인지 확인한다.
4. 입력 settings 값 범위를 검증한다.
5. `Room.settings`를 새 값으로 갱신한다.
6. `Room.version`을 증가시킨다.
7. same room participant 전체에게 `308 GAME_SETTINGS_UPDATED`를 발행한다.
8. 이때 `308 GAME_SETTINGS_UPDATED`에는 `roundCount`, `drawSec`, `wordChoiceSec`, `wordChoiceCount`, `drawerOrderMode`, `endMode`중 변경된 것만 담는다.

---

## 6. 게임 시작 플로우

### 6.1 private room game start

입력:

- `105 GAME_START_REQUEST`

흐름:

1. room type이 `PRIVATE`인지 확인한다.
2. 요청 사용자가 host인지 확인한다.
3. room state가 `LOBBY`인지 확인한다.
4. `currentGame == null`인지 확인한다.
5. participant가 2명 이상인지 확인한다.
6. 현재 `Room.settings` 값이 유효한지 확인한다.
7. `Room.settings`를 복사해 현재 게임용 `Game.settings`를 만든다.
8. `ScoreBoard`를 0점으로 초기화한다.
9. `Game`을 생성한다.
10. `Room.currentGame`에 연결한다.
11. `Room.state = RUNNING`으로 바꾼다.
12. `Room.version`을 증가시킨다.
13. same room participant 전체에게 `302 GAME_STARTED`를 발행한다.
14. 이때 `302 GAME_STARTED`에는 `gameId`, `settings`를 담는다.
15. 2초 뒤 첫 라운드 시작 작업을 예약한다.

### 6.2 first round start

입력:

- 게임 시작 후 2초 뒤 실행되는 내부 작업

흐름:

1. `Round(roundNo=1)`를 생성한다.
2. `Round.state = RUNNING`으로 둔다.
3. 현재 participant 입장 순서를 기준으로 라운드용 participant snapshot을 만든다.
4. `drawerOrderMode`가 `JOIN_ORDER`면 snapshot 순서를 그대로 `drawerOrder`로 저장한다.
5. `drawerOrderMode`가 `RANDOM`이면 snapshot 순서를 섞어 `drawerOrder`로 저장한다.
6. `turnCursor = 0`으로 둔다.
7. `Game.currentRound`에 연결한다.
8. `Room.version`을 증가시킨다.
9. same room participant 전체에게 `303 ROUND_STARTED`를 발행한다.
10. 이때 `303 ROUND_STARTED`에는 `gameId`, `round`를 담는다.
11. 2초 뒤 첫 word choice turn 시작 작업을 예약한다.

---

## 7. word choice 턴 시작 플로우

### 7.1 word choice turn start

입력:

- round start 후 2초 뒤 실행되는 내부 작업

흐름:

1. current round의 `drawerOrder`와 `turnCursor`를 기준으로 drawer를 결정한다.
2. `Turn`을 생성한다.
3. `Turn.state = WORD_CHOICE`로 둔다.
4. `Turn.phase = null`로 두고, word choice 창 오픈 시점에 `WordChoicePhase`를 만든다.
5. `correctUserIds = empty`로 둔다.
6. `pendingScores = empty`로 둔다.
7. `Round.currentTurn`에 연결한다.
8. `Room.version`을 증가시킨다.
9. same room participant 전체에게 `304 TURN_STARTED`를 발행한다.
10. 이때 `304 TURN_STARTED`에는 `gameId`, `round`, `turn`, `turnId`, `drawerUserId`를 담는다.
11. 2초 뒤 `Turn.phase = WordChoicePhase(wordChoices, startedAt, endsAt)`를 만든다.
12. 같은 시점에 drawer 1명에게만 `406 WORD_CHOICES`를 발행한다.
13. 같은 시점에 나머지 participant에게 `311 TURN_STATE(phase=WORD_CHOICES_GIVEN, timeoutSec=...)`를 발행한다.
14. `406 WORD_CHOICES`를 발행한 시점부터 `wordChoiceSec` 뒤 word choice timeout 작업을 예약한다.

### 7.2 explicit word choice

입력:

- `205 WORD_CHOICE`

흐름:

1. current turn이 존재하는지 확인한다.
2. current turn state가 `WORD_CHOICE`인지 확인한다.
3. 요청 사용자가 drawer인지 확인한다.
4. 현재 시각이 `WordChoicePhase.endsAt`를 이미 넘겼는지 먼저 확인한다.
5. deadline이 지났고 turn이 아직 `WORD_CHOICE`면 서버 강제 선택을 우선 적용한다.
6. 아직 deadline 안이면 `choiceIndex`가 후보 범위 안인지 확인한다.
7. `Turn.state = DRAWING`으로 바꾼다.
8. `Turn.phase = DrawingPhase(secretWord, canvas, startedAt, endsAt)`로 바꾼다.
9. `endReason`을 초기화한다.
10. `Room.version`을 증가시킨다.
11. drawer 1명에게 `310 DRAWING_STARTED(durationSec=...)`를 발행한다.
12. 같은 시점에 나머지 participant에게 `311 TURN_STATE(phase=DRAWING_STARTED, durationSec=...)`를 발행한다.
13. `drawSec` 뒤 drawing timeout 작업을 예약한다.

### 7.3 word choice timeout

입력:

- word choice turn 시작 시 예약된 timeout 작업

흐름:

1. current turn이 여전히 expected `turnId`와 같은지 확인한다.
2. current turn state가 아직 `WORD_CHOICE`인지 확인한다.
3. 후보 중 하나를 랜덤 선택한다.
4. `Turn.state = DRAWING`으로 바꾼다.
5. `Turn.phase = DrawingPhase(secretWord, canvas, startedAt, endsAt)`로 바꾼다.
6. `endReason`을 초기화한다.
7. `Room.version`을 증가시킨다.
8. drawer 1명에게 `310 DRAWING_STARTED(durationSec=...)`를 발행한다.
9. 같은 시점에 나머지 participant에게 `311 TURN_STATE(phase=DRAWING_STARTED, durationSec=...)`를 발행한다.
10. `drawSec` 뒤 drawing timeout 작업을 예약한다.

---

## 8. 드로잉 플로우

### 8.1 draw stroke

입력:

- `201 DRAW_STROKE`

흐름:

1. current game이 running인지 확인한다.
2. current turn이 존재하는지 확인한다.
3. current turn state가 `DRAWING`인지 확인한다.
4. 요청 사용자가 drawer인지 확인한다.
5. payload의 `turnId`가 current turn과 같은지 확인한다.
6. stroke 제약을 검증한다.
7. stroke를 `Turn.canvas.strokes`에 추가한다.
8. `Room.version`을 증가시킨다.
9. non-drawer participant에게만 `401 CANVAS_STROKE`를 발행한다.
10. 이때 `401 CANVAS_STROKE`에는 `turnId`, `drawerUserId`, `stroke`를 담는다.

### 8.2 draw clear

입력:

- `202 DRAW_CLEAR`

흐름:

1. current turn이 존재하는지 확인한다.
2. current turn state가 `DRAWING`인지 확인한다.
3. 요청 사용자가 drawer인지 확인한다.
4. payload의 `turnId`가 current turn과 같은지 확인한다.
5. `Turn.canvas.strokes`를 비운다.
6. `Room.version`을 증가시킨다.
7. non-drawer participant에게만 `402 CANVAS_CLEAR`를 발행한다.

---

## 9. 추측 플로우

### 9.1 일반 guess / chat

입력:

- `204 GUESS_SUBMIT`

흐름:

1. current turn이 존재하는지 확인한다.
2. current turn state가 `DRAWING`인지 확인한다.
3. payload의 `turnId`가 있으면 current turn과 같은지 확인한다.
4. 요청 사용자가 drawer인지 여부를 확인한다.
5. drawer면 secret word 비교 없이 drawer 전용 채팅으로 처리한다.
6. drawer 메시지는 현재 턴의 정답자 집합 + drawer에게만 `403 GUESS_MESSAGE`를 발행한다.
7. drawer가 아니면 입력 text를 정규화한다.
8. secret word와 비교한다.
9. 정답이 아니면 일반 message로 처리한다.
10. 아직 정답 처리되지 않은 사용자 메시지면 same room participant 전체에게 `403 GUESS_MESSAGE`를 발행한다.
11. 이미 정답 처리된 사용자 메시지면 현재 턴의 정답자 집합 + drawer에게만 `403 GUESS_MESSAGE`를 발행한다.
12. 이때 `403 GUESS_MESSAGE`에는 `userId`, `nickname`, `text`, `turnId`를 담는다.

### 9.2 correct guess

입력:

- `204 GUESS_SUBMIT`

흐름:

1. current turn이 존재하는지 확인한다.
2. current turn state가 `DRAWING`인지 확인한다.
3. payload의 `turnId`가 current turn과 같은지 확인한다.
4. 요청 사용자가 drawer가 아닌지 확인한다. drawer면 정답 판정으로는 진행하지 않는다.
5. 이미 `correctUserIds`에 들어 있지 않은지 확인한다.
6. `correctUserIds`에 user를 추가한다.
7. 이번 턴에서 이 user가 받을 예정 점수를 turn 내부 보상 상태에 기록한다.
8. drawer가 받을 예정 점수도 turn 내부 보상 상태에 기록한다.
9. `Room.version`을 증가시킨다.
10. same room participant 전체에게 `404 GUESS_CORRECT`를 발행한다.
11. 이때 `404 GUESS_CORRECT`에는 `userId`, `nickname`, `turnId`를 담는다.
12. 모드와 현재 정답자 상태를 보고 turn 종료 여부를 판단한다.
13. turn 종료가 필요하면 즉시 turn end 작업을 이어서 실행한다.

---

## 10. 턴 종료 플로우

### 10.1 turn timeout

입력:

- drawing 시작 시 예약된 timeout 작업

흐름:

1. current turn이 여전히 expected `turnId`와 같은지 확인한다.
2. current turn state가 아직 `DRAWING`인지 확인한다.
3. 종료 사유를 `TIMEOUT`으로 확정한다.
4. 즉시 turn end 작업을 이어서 실행한다.

### 10.2 turn end

입력:

- first correct
- all correct
- timeout
- drawer leave

흐름:

1. `Turn.state = ENDED`로 바꾼다.
2. `endReason`을 기록한다.
3. `endedAt`를 기록한다.
4. 이번 턴에 쌓아둔 예정 점수를 `ScoreBoard`에 한 번에 반영한다.
5. `Room.version`을 증가시킨다.
6. same room participant 전체에게 `305 TURN_ENDED`를 발행한다.
7. 이때 `305 TURN_ENDED`에는 `gameId`, `round`, `turn`, `turnId`, `reason`, 이번 턴 `earnedScores`, 최신 scoreboard를 함께 담는다.
8. `earnedScores`에는 이번 턴에서 누가 몇 점을 얻었는지 담는다.
9. 정답자가 1명 이상이면 drawer도 이번 턴 점수를 얻는다.
10. `305 TURN_ENDED`를 최소 3초간 보여줄 수 있도록 3초 뒤 후속 작업을 예약한다.
11. 다음 턴이 있으면 표시 구간 이후 즉시 다음 word choice turn 시작 작업을 예약한다.
12. 그러면 다음 turn 시작 안내(`304`)가 나가고, 그로부터 2초 뒤 단어 선택 창이 열린다.
13. 라운드가 끝났으면 표시 구간 이후 round end 작업을 이어서 실행한다.

---

## 11. 라운드 종료 플로우

### 11.1 round end

입력:

- 마지막 turn 종료 후 즉시 실행되는 내부 작업

흐름:

1. `Round.state = ENDED`로 바꾼다.
2. `endedAt`를 기록한다.
3. `Room.version`을 증가시킨다.
4. same room participant 전체에게 `306 ROUND_ENDED`를 발행한다.
5. 이때 `306 ROUND_ENDED`에는 `gameId`, `round`를 담는다.
6. 다음 라운드가 있으면 즉시 다음 round start 작업을 예약한다.
7. 마지막 라운드면 즉시 game end 작업을 이어서 실행한다.

---

## 12. 게임 종료 플로우

### 12.1 game end

입력:

- 마지막 라운드 종료 후 즉시 실행되는 내부 작업

흐름:

1. ranking을 계산한다.
2. `Game.status = RESULT_VIEW`로 바꾼다.
3. `endedAt`, `resultViewUntil`을 기록한다.
4. `Room.version`을 증가시킨다.
5. same room participant 전체에게 `307 GAME_ENDED`를 발행한다.
6. 이때 `307 GAME_ENDED`에는 `gameId`, `ranking`, `resultViewSec`를 담는다.
7. 8초 뒤 result view end 작업을 예약한다.

### 12.2 result view end

입력:

- game end 후 8초 뒤 실행되는 내부 작업

흐름:

1. `PRIVATE` room이면 `Game.status = ENDED`, `Room.currentGame = null`, `Room.state = LOBBY`로 바꾼다.
2. `RANDOM` room이고 participant가 2명 이상이면 즉시 다음 게임을 시작한다.
3. `RANDOM` room인데 participant가 1명 이하이면 `Game.status = ENDED`, `Room.currentGame = null`, `Room.state = LOBBY`로 바꾼다.

---

## 13. leave 플로우

### 13.1 일반 leave

입력:

- WS lifecycle `LEAVE`

흐름:

1. participant를 제거한다.
2. `PRIVATE` room이고 나간 사용자가 host면, 남아 있는 participant 입장 순서 기준 다음 index의 participant를 새 host로 지정한다.
3. `Room.version`을 증가시킨다.
4. 남아 있는 participant들에게 `309 ROOM_LEFT`를 발행한다.
5. 이때 `309 ROOM_LEFT`에는 `userId`를 담는다.
6. room empty 여부를 판단한다.

### 13.2 drawer leave

입력:

- current drawer에 대한 `LEAVE`

흐름:

1. participant를 제거한다.
2. `PRIVATE` room이고 나간 사용자가 host면, 남아 있는 participant 입장 순서 기준 다음 index의 participant를 새 host로 지정한다.
3. `Room.version`을 증가시킨다.
4. 남아 있는 participant들에게 `309 ROOM_LEFT`를 발행한다.
5. 이때 `309 ROOM_LEFT`에는 `userId`를 담는다.
6. 종료 사유를 `DRAWER_LEFT`로 확정한다.
7. 즉시 turn end 작업을 이어서 실행한다.

### 13.3 last participant leave

입력:

- 마지막 participant에 대한 `LEAVE`

흐름:

1. participant를 제거한다.
2. `Room.version`을 증가시킨다.
3. room empty 상태를 기록한다.
4. empty room이면 room type에 맞는 삭제 대기 작업을 예약한다.
5. `RANDOM` room은 즉시 close한다.
6. `PRIVATE` room은 30초 뒤 close한다.
7. 예약된 작업 시점에도 여전히 empty면 room을 삭제한다.
8. 삭제 대기 중 다시 participant가 들어오면 예약된 삭제는 stale check로 무시한다.

---

## 14. snapshot 복구 플로우

### 14.1 game snapshot request

입력:

- `106 GAME_SNAPSHOT_REQUEST`

흐름:

1. 요청 사용자가 현재 participant가 아니면 요청을 무시한다.
2. snapshot 요청도 room mailbox로 넣는다.
3. mailbox 안에서 현재 room authoritative state를 읽는다.
4. `Room`, `Game`, `Round`, `Turn`, `Canvas`, `ScoreBoard`의 현재 값을 `408 GAME_SNAPSHOT` payload로 매핑한다.
5. 요청자 1명에게 `408 GAME_SNAPSHOT`을 발행한다.

---

## 15. 구현 우선순위

현재 구현은 아래 순서로 진행하는 것이 자연스럽다.

1. `GAME_SETTINGS_UPDATE_REQUEST`
2. `GAME_START_REQUEST`
3. `WORD_CHOICE`
4. `GUESS_SUBMIT`
5. `DRAW_STROKE`
6. `DRAW_CLEAR`
7. `GAME_SNAPSHOT_REQUEST`
8. `ROOM_LEAVE`의 drawer leave 분기
9. 다음 turn / 다음 round / game end follow-up
