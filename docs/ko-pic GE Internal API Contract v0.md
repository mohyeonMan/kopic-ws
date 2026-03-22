# ko-pic GE Internal API Contract v0

## 1. 목적

이 문서는 MVP 기준 `WS <-> GE` 내부 API 계약을 고정한다.

- 범위: WS->GE command RPC, WS->GE session lifecycle RPC, GE->WS outbound event
- 기준 소스:
  - `../kopic-ws/src/main/java/io/jhpark/kopic/ws/engine/app/*`
  - `../kopic-ws/src/main/java/io/jhpark/kopic/ws/dispatch/app/DefaultCommandDispatchService.java`
  - `docs/ko-pic Protocol Spec v0.md`

비범위:

- Lobby HTTP API
- GE 내부 aggregate 상세 구현
- MQ 인프라 튜닝

---

## 2. 계약 원칙

1. WS는 envelope transport를 담당하고, GE는 도메인 검증/상태 전이를 담당한다.
2. WS->GE 동기 RPC 응답은 `ack/error` 의미만 가진다.
3. 클라이언트-facing 상태 이벤트(`301~408`, `3`)는 GE->WS 비동기 outbound 경로로 전달한다.
4. `rid`는 WS/GE 내부 DTO에서도 동일 키(`rid`)로 취급하며 가능하면 그대로 보존한다.

---

## 3. WS -> GE RPC 계약

## 3.1 Runtime Envelope 요청

WS 코드 기준 DTO:

```java
public record EngineEnvelopeRequest(
    String roomId,
    String userId,
    Instant occurredAt,
    ClientEnvelope envelope
) {}
```

`ClientEnvelope`:

```java
public record ClientEnvelope(
    int e,
    JsonNode p,
    String rid
) {}
```

의미:

- `roomId`: handshake 컨텍스트에서 확정된 room
- `userId`: handshake 컨텍스트에서 확정된 user
- `occurredAt`: WS에서 수신/터치된 시각
- `e/p/rid`: 클라이언트 envelope를 내부에서도 동일 키로 유지

### 이벤트 입력 범위

- `1`(PING)은 WS에서 `2`(PONG)으로 처리하고 GE로 전달하지 않는다.
- 나머지 runtime 이벤트(`103`, `105`, `106`, `201`, `202`, `204`, `205`)는 GE로 전달한다.

## 3.2 Session Lifecycle 요청

WS 코드 기준 DTO:

```java
public record SessionLifecycleEvent(
    String roomId,
    String userId,
    Instant occurredAt,
    SessionLifecycleType type
) {}
```

```java
public enum SessionLifecycleType {
    JOIN,
    LEAVE
}
```

의미:

- `JOIN`: WS `afterConnectionEstablished` 직후 내부 room join 트리거
- `LEAVE`: WS 연결 종료 또는 명시적 세션 종료 직후 내부 room leave 트리거

MVP 정책상 재접속 유예는 없고, disconnect는 즉시 leave로 처리한다.

## 3.3 RPC 응답 Ack

WS 코드 기준 DTO:

```java
public record EngineAck(
    boolean accepted,
    EngineAckReason reason
) {}
```

```java
public enum EngineAckReason {
    ACCEPTED,
    NOT_OWNER,
    MIGRATING,
    REJECTED,
    INTERNAL_ERROR
}
```

규칙:

- `accepted=true`면 WS는 요청을 정상 수락으로 간주
- `accepted=false`면 WS는 `reason`에 따라 재조회/재시도 또는 에러 응답

---

## 4. Ack Reason 처리 계약 (WS 동작)

WS 구현 기준(`DefaultCommandDispatchService`) 처리 규칙:

1. `ACCEPTED`
- 즉시 성공 처리

2. `NOT_OWNER`
- owner cache invalidate 후 1회 재전송
- 백오프: 150ms
- 재전송 후에도 실패면 reject

3. `MIGRATING`
- 재전송하지 않고 즉시 reject

4. `REJECTED` / `INTERNAL_ERROR`
- 즉시 reject

WS 에러 매핑(`WebSocketConnectionHandler`) 현재값:

- `NOT_OWNER` -> `ERROR(3)` with `code=902`, `message=ROOM_NOT_FOUND`
- `MIGRATING` -> `ERROR(3)` with `code=909`, `message=MIGRATING`
- 기타 -> `ERROR(3)` with `code=909`, `message=INTERNAL_ERROR`

참고: 위 에러 매핑은 현재 구현값이며, 추후 프로토콜 코드 정합화 시 별도 개정한다.

---

## 5. GE -> WS Outbound 계약

WS 수신 DTO:

```java
public record InboundEngineEvent(
    String userId,
    ServerEnvelope envelope
) {}
```

```java
public record ServerEnvelope(
    int e,
    JsonNode p,
    String rid
) {}
```

의미:

- `userId`: delivery 대상 사용자
- `e/p/rid`: 클라이언트 outbound 이벤트를 내부에서도 동일 키로 유지

MQ 라우팅 규칙:

- exchange: `kopic.engine.outbound`
- routing key prefix: `ws.node.`
- queue prefix: `kopic.ws.node.`
- 최종 routing key: `ws.node.{nodeId}`

GE는 room audience 계산 결과를 user 단위 targeted event로 publish한다.

---

## 6. 필드 정합성 규칙

1. `rid`
- client request에서 온 `rid`를 가능한 그대로 반환
- server-originated event는 `rid=null` 허용

2. `e`
- `Protocol Spec`에 정의된 코드만 사용
- undefined code는 `3 ERROR`로 처리

3. `p`
- WS는 payload 의미를 해석하지 않고 GE 결과를 전달
- semantic validation은 GE 책임

4. `occurredAt`
- 도메인 판정 기준 시간은 GE clock을 우선
- `occurredAt`은 관측/추적 보조값

---

## 7. 버전/호환성

v0에서는 DTO 필드 추가를 최소화한다.

- `EngineEnvelopeRequest`/`SessionLifecycleEvent`는 현재 WS 시그니처를 기준으로 고정
- 필드 변경이 필요하면 `v1` 문서에서 명시적으로 승격

권장 v1 후보:

- `nodeId` 명시 전달
- `sessionId` 명시 전달
- `requestEvent`(원요청 코드)를 GE error payload에 구조화

---

## 8. 최소 테스트 시나리오

1. JOIN ack accepted
2. JOIN ack not_owner -> invalidate + 1회 재시도 성공
3. runtime ack migrating -> WS 에러 응답
4. LEAVE ack rejected -> WS 로그/예외 처리
5. outbound event rid pass-through 검증
