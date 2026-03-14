# WS Package

스프링부트 기반 `kopic-ws`는 게임 서버가 아니라 "WS gateway application"으로 두는 것이 맞다.  
핵심 원칙은 기술 축이 아니라 책임 축으로 패키지를 자르는 것이다.

즉 `controller/service/repository` 식 범용 분류보다, WS가 실제로 맡는 도메인 경계대로 나누는 편이 유지보수에 유리하다.

## 추천 패키지 구조

```text
io.jhpark.kopic.ws
  KopicWsApplication

  common
    error
    logging
    json
    time

  config
    WebSocketConfig
    JacksonConfig
    RedisConfig
    RabbitConfig
    SecurityConfig

  session
    domain
      WsSession
    app
      SessionRegistry

  routing
    domain
      EngineRoute
    infra
      RedisRoomDirectory
      LocalRouteCache

  ingress
    app
      InboundMessageHandler
      EnvelopeValidator
      RateLimitService
    dto
      ClientEnvelope
    infra
      WebSocketFrameHandler
      WebSocketConnectionHandler

  dispatch
    app
      CommandDispatchService

  egress
    app
      SessionDeliveryPort
    dto
      ServerEnvelope
    infra
      WebSocketMessageSender

  engine
    app
      GameEngineClient
    infra
      rpc
        GrpcGameEngineClient

  subscription
    app
      EngineEventSubscriber
      EngineEventDispatchService
    infra
      rabbitmq
        RabbitEngineEventSubscriber

  observability
    metric
    tracing
    audit
```

## 핵심 책임

### 1. `session`

- WS 연결과 사용자 컨텍스트만 담당한다.
- 소켓 연결/종료, 인증 주체, 세션 상태를 관리한다.
- `userId`, `roomId`, `connectedAt`, `lastSeenAt` 같은 최소 상태를 관리한다.
- WS 연결 시 클라이언트가 `roomId`를 함께 전달한다.
- `afterConnectionEstablished` 단계에서 WS가 내부 join을 GE에 전달하고, 참여가 완료된다.
- `roomId`는 optional 컨텍스트가 아니라, 모든 runtime 요청의 공통 라우팅 키다.

### 2. `routing`

- `roomId -> owner GE` 조회/캐시 같은 라우팅 상태를 담당한다.
- Redis directory를 붙여도 이 패키지 밖으로 구현 상세가 새지 않게 한다.
- 권장 구조는 2단 directory cache다.
  - `roomId -> ownerEngineId`는 `15분` TTL
  - `engineId -> EngineRoute`는 `24시간` TTL
  - 둘 다 `cache.get(key, loader)`로 조회 coalescing

### 3. `ingress / dispatch`

- `ingress`는 웹소켓에서 들어온 메시지를 해석한다.
- `dispatch`는 그 메시지를 내부 command로 바꿔 `GE` port로 넘긴다.
- 별도 이벤트 분류기보다는, 세션에 결합된 `roomId`를 공통 라우팅 키로 사용해 모든 runtime 요청을 동일하게 전달하는 쪽이 기본이다.
- transport parsing과 use case orchestration을 분리하는 역할이다.

### 4. `egress / subscription`

- `egress`는 클라이언트에게 어떻게 보낼지 담당한다.
- `subscription`은 GE나 MQ에서 온 서버 이벤트를 어떻게 받을지 담당한다.
- WS는 room fan-out 주체가 아니라, 자신에게 연결된 `userId` 기준 세션으로만 targeted delivery를 수행한다.
- 현재는 인터페이스만 두고, 실제 RabbitMQ subscriber는 이후 단계에서 붙인다.

## 패키지 설계 원칙

스프링부트에서는 프로젝트 전체를 `app / domain / infra` 레이어로 가르는 것보다, 각 bounded package 내부에서만 수직 분할하는 편이 좋다.

예:

- `routing.app.RoutingService`
- `routing.domain.EngineRoute`
- `routing.infra.RedisRoomDirectory`

같은 책임 안에서 수직 분할하는 방식이 더 유지보수에 강하다.

## 인터페이스 경계

WS와 외부 시스템의 경계는 포트 인터페이스로 고정하는 것이 좋다.

```java
public interface GameEngineClient {
    EngineAck send(EngineEnvelopeRequest request);
    EngineAck send(SessionLifecycleEvent event);
}

public interface RoomDirectory {
    Optional<EngineRoute> findOwner(String roomId);
}

public interface EngineEventSubscriber {
    void subscribe(Consumer<InboundEngineEvent> consumer);
}

public interface SessionDeliveryPort {
    void deliver(String userId, ServerEnvelope event);
}
```

이렇게 두면 구현체는 나중에 갈아끼우면 된다.

- 현재 WS의 기본 downstream은 `GE` 하나다.
- 기본 경로는 `engine.infra.rpc.GrpcGameEngineClient` 같은 RPC 구현이 자연스럽다.
- RPC는 request acceptance/에러 확인 용도로만 사용하고, 실제 outbound event는 별도 subscriber 경로로 받는다.

중요한 점은 `dispatch`나 `ingress`가 `GrpcStub`, `RabbitTemplate` 같은 인프라 타입을 직접 알지 않게 하는 것이다.

## 권장 요청 흐름

1. 클라이언트가 WS 연결 시 `roomId`를 함께 전달
2. `infrastructure.websocket`에서 세션 생성 및 `roomId` 확정
3. `afterConnectionEstablished`에서 내부 join command를 GE로 전달
4. GE는 RPC ack만 반환
5. 실제 outbound event는 `subscription` 경로로 수신
6. `egress`가 `userId` 기준으로 로컬 세션에 전송
7. 이후 `ingress`에서 runtime envelope parse와 size/rate limit 검사
8. `dispatch`가 `EngineEnvelopeRequest` 또는 `SessionLifecycleEvent`를 GE로 전달

## 피해야 할 구조

- `WebSocketHandler` 안에서 바로 게임엔진 호출
- `controller/service/repository` 식으로 전부 뭉개는 구조
- `RabbitTemplate`, `RedisTemplate`, `SimpMessagingTemplate` 같은 인프라 타입이 애플리케이션 전역으로 새는 구조
- `ws` 애플리케이션 안에 게임 규칙 enum이나 점수 로직을 넣는 구조
- WS가 room membership authority를 가지는 구조
- WS가 이벤트별 payload DTO를 모두 소유하고 semantic validation까지 수행하는 구조
- 모든 요청이 이미 room context 안에 있다는 전제를 무시하고, runtime 이벤트를 별도 entry flow처럼 취급하는 구조
- 실제 GE/MQ가 없는데도 product code 안에 fake event bus를 심어두는 구조

## 현실적인 시작점

현재 레포에서는 아래 정도부터 시작하는 것이 현실적이다.

```text
io.jhpark.kopic.ws
  config
  common
  session
  routing
  ingress
  dispatch
  egress
  engine
  subscription
```

`observability`는 실제 구현이 진행되면서 추가하는 편이 덜 과하다.
