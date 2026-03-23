# ko-pic Game Rules (MVP v0.2)

## 1. 목적

이 문서는 MVP 구현 기준의 게임 규칙을 정의한다.

- 목표: 방 참여, 그림/추측 실시간 전달, 턴/라운드 진행
- 비목표: 인증 서버 연동, 전달 보장(at-least-once), 고급 관전자 시스템

---

## 2. 모드와 방

지원 모드:

- `random`
- `private`

공통 규칙:

- spectator 역할은 MVP에서 사용하지 않는다.
- 입장한 사용자는 항상 `player`다.
- 닉네임 중복 허용.
- participant 목록은 입장 순서를 유지한다.

random 규칙:

- host 개념 없음.
- random quick-join 요청 시, join 가능한 existing random room이 있으면 그 방에 즉시 편입한다.
- join 가능한 random room이 없으면 owner GE가 새 random room을 생성하고 요청한 사용자를 첫 participant로 넣는다.
- 게임 중 유입 허용.

private 규칙:

- host 존재(방 생성자).
- 입장 경로: 방코드, 초대링크(내부적으로 roomCode를 포함한 join 요청).
- 게임 중 유입 허용.
- 게임 시작 전 대기실에서 게임 설정 변경 가능.

방코드 규칙:

- 길이 6자.
- 중복 생성 금지(생성 시 서버에서 유니크 보장).
- 방 종료 후 즉시 재사용 가능.

---

## 3. 입장/중간입장

입장 시점 규칙:

- 게임 시작 전/후 관계없이 즉시 player로 편입.
- 현재 턴에서도 즉시 guess 가능.
- 현재 턴의 drawer는 될 수 없음.
- 현재 라운드의 drawer 순서에는 중간부터 편입되지 않는다.
- 중간입장자는 다음 라운드부터 drawer 후보가 될 수 있다.

입장 직후 동기화:

- 서버는 현재 상태 스냅샷을 즉시 전달.
- 포함 정보:
  - 방 정보
  - 게임 설정
  - 참여자 목록(`userId`, `nickname`)
  - 현재 라운드/턴
  - 현재 턴 캔버스 전체 stroke 목록

캔버스 저장:

- 서버는 `현재 턴`의 stroke를 메모리에 유지.
- 턴 시작 시 캔버스/stroke 저장소를 초기화.

---

## 4. 게임 설정

설정은 `방`이 아니라 `게임 단위`다.  
설정 변경 가능 시점은 `게임 시작 버튼 누르기 전`까지만 허용한다.

설정 값:

- `roundCount`: 3 ~ 10
- `drawSec`: 20 ~ 60
- `wordChoiceSec`: 5 ~ 15
- `wordChoiceCount`: 3 ~ 5
- `drawerOrderMode`:
  - `JOIN_ORDER`
  - `RANDOM`
- `endMode`:
  - `FIRST_CORRECT`
  - `TIME_OR_ALL_CORRECT`

대기실 설정 변경:

- `private` room에서만 허용한다.
- host만 변경할 수 있다.
- 변경 결과는 같은 room의 다른 participant에게도 즉시 반영되어야 한다.
- 이 변경은 `Room.settings`를 갱신하는 흐름이고, 이미 시작된 `Game.settings`를 바꾸면 안 된다.

---

## 5. 게임 시작/진행

시작:

- private: host 시작 요청 시 시작
- random: room이 만들어진 뒤 GE 정책에 따라 자동 시작할 수 있다.

턴 순서:

1. `TURN_STARTED`
2. drawer에게 `WORD_CHOICES` 전달
3. 다른 participant에게 `TURN_STATE(WORD_CHOICES_GIVEN)` 전달
4. drawer `WORD_CHOICE`
5. drawer에게 `DRAWING_STARTED`
6. 다른 participant에게 `TURN_STATE(DRAWING_STARTED)`

라운드별 drawer 순서:

- 각 라운드 시작 시 그 라운드의 `drawerOrder`를 한 번 확정한다.
- `JOIN_ORDER`면 라운드 시작 시점의 participant 입장 순서를 그대로 사용한다.
- `RANDOM`이면 라운드 시작 시점의 participant 목록을 무작위로 섞어 사용한다.
- 라운드 시작 후 새로 들어온 participant는 현재 라운드 `drawerOrder`에 포함되지 않는다.
- 새로 들어온 participant는 현재 턴부터 guess는 가능하지만, drawer는 다음 라운드부터 가능하다.

drawer가 `wordChoiceSec` 내 미선택이면 서버가 랜덤 선택 후 턴 시작.

전환 지연(서버 주도, 클라 ACK 대기 없음):

- 턴 종료 후 다음 턴 시작까지: 3초
- 라운드 종료 후 다음 라운드 시작까지: 즉시
- 게임 종료 후 결과 화면 유지: 8초

drawer 이탈:

- 현재 턴 즉시 종료
- 다음 drawer 턴으로 진행

---

## 6. 추측/채팅 규칙

채팅 타입:

- `GUESS_SUBMIT` 하나로 추측/일반 채팅 입력을 받는다.

정답 비교 정규화:

1. 소문자 변환
2. 모든 공백 제거
3. 문장부호 제거(`.`, `,`, `!`, `?`)
4. 문자열 완전 일치 비교

판정:

- 동일 턴에서 이미 정답 처리된 플레이어는 추가 정답 판정 대상에서 제외.

메시지 가시성:

- 미정답자 메시지: 전체 공개
- 정답 처리된 사용자 이후 메시지: 현재 턴의 정답자 집합 + drawer에게만 공개
- drawer 메시지: 정답자 + drawer에게만 공개
- 정답 텍스트는 미정답자에게 노출하지 않음

레이트리밋:

- 채팅/guess: 사용자당 초당 5회

---

## 7. 턴 종료/점수

턴 종료 조건:

- `FIRST_CORRECT`: 첫 정답자 발생 즉시 종료
- `TIME_OR_ALL_CORRECT`: 제한시간 종료 또는 전원 정답 시 종료

MVP 점수(더미):

- guesser: 턴 내 첫 정답 시 `+1`
- drawer: 정답자 1명당 `+1`

점수 반영 시점:

- 정답 즉시 누적 scoreboard를 바꾸지 않는다.
- 이번 턴에서 누가 몇 점을 얻을지는 턴 내부 예정 점수로 관리한다.
- 실제 누적 scoreboard 반영은 `TURN_ENDED` 시점에 한 번에 수행한다.

결과 예외:

- `FIRST_CORRECT` 모드: 첫 정답자와 drawer만 점수 획득
- `TIME_OR_ALL_CORRECT` + TIMEOUT: 시간 내 정답자는 점수 획득, 미정답자는 0

중간입장자:

- 입장 직후 현재 턴에서 정답 시 점수 획득 가능

턴 종료 표시:

- `TURN_ENDED`에는 종료 사유뿐 아니라 이번 턴 획득 점수와 최신 누적 scoreboard를 함께 포함한다.
- `TURN_ENDED` 이후 최소 3초간 이번 턴 결과를 보여준 뒤에만 다음 턴/다음 라운드/게임 종료 후속 작업으로 넘어간다.

---

## 8. 드로잉 규칙

MVP tool:

- `PEN`
- `ERASER`

좌표:

- 정규화 좌표(0.0 ~ 1.0)
- 소수점 최대 5자리

색상:

- `colorIndex` 사용
- 범위: 0 ~ 19 (총 20색)

선 굵기:

- `size` 범위: 1 ~ 20

스트로크 제한:

- 1개 `DRAW_STROKE`의 `points` 최대 64개
- 사용자당 `DRAW_STROKE` 초당 최대 30회
- 메시지 최대 크기 8KB

`DRAW_CLEAR`:

- drawer만 허용
- 서버는 drawer 자신에게 `DRAW_STROKE`, `DRAW_CLEAR` echo를 다시 보내지 않는다.

`DRAW_FINISH`:

- MVP에서 사용하지 않음

---

## 9. 연결/재접속

MVP 정책:

- 연결 끊김 시 즉시 leave 처리(유예시간 없음)
- 재접속 복구 정책(동일 사용자 식별/유예 재진입)은 후속 버전에서 정의
- 마지막 participant가 떠난 뒤 room 정리는 owner GE가 담당한다.
- `private` room은 empty 상태가 되면 30초 뒤 정리한다.
- `random` room은 empty 상태가 되면 즉시 정리한다.
- `private` room에서 host가 나가면, 남아 있는 participant 입장 순서 기준 다음 index의 participant가 새 host가 된다.

---

## 10. 전달 보장

MVP는 `at-most-once` 전달 모델을 사용한다.

- WS 레벨 재전송 없음
- 순서/중복 제어는 GE에서 room 단위 라우팅과 TTL 기반 중복 처리로 보완
- 상태 불일치 시 클라이언트는 `GAME_SNAPSHOT_REQUEST`로 복구
