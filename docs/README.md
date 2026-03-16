# ko-pic Docs Index

이 디렉터리는 `kopic` 서버군의 MVP 설계 문서를 모아둔 인덱스다.

## 권장 읽기 순서

1. [ko-pic Architecture Plan.md](./ko-pic%20Architecture%20Plan.md)
2. [ko-pic Game Rules.md](./ko-pic%20Game%20Rules.md)
3. [ko-pic Protocol Spec v0.md](./ko-pic%20Protocol%20Spec%20v0.md)
4. [ko-pic Lobby API Spec v0.md](./ko-pic%20Lobby%20API%20Spec%20v0.md)
5. [ko-pic WS Package.md](./ko-pic%20WS%20Package.md)
6. [ko-pic GE Package.md](./ko-pic%20GE%20Package.md)
7. [ko-pic GE Internal API Contract v0.md](./ko-pic%20GE%20Internal%20API%20Contract%20v0.md)
8. [ko-pic Lobby-GE Internal API Contract v0.md](./ko-pic%20Lobby-GE%20Internal%20API%20Contract%20v0.md)
9. [ko-pic GE Domain Model v0.md](./ko-pic%20GE%20Domain%20Model%20v0.md)
10. [ko-pic Lobby Package.md](./ko-pic%20Lobby%20Package.md)

## 이번에 추가된 GE 문서

- `ko-pic GE Package.md`
  - GE 책임 경계
  - 패키지 구조 제안
  - 내부 API/포트 경계
  - 구현 우선순위

- `ko-pic GE Domain Model v0.md`
  - 최소 aggregate/value object
  - command/event 모델
  - 상태 전이/에러 코드 매핑
  - Redis 키 책임 원칙

- `ko-pic GE Internal API Contract v0.md`
  - WS->GE RPC DTO 계약
  - Ack/재시도/에러 매핑 규칙
  - GE->WS outbound 이벤트 계약

- `ko-pic Lobby-GE Internal API Contract v0.md`
  - Lobby->GE create/join 호출 계약
  - GE->Lobby reassign 호출 계약
  - presence 데이터 계약 및 현재 코드 갭

## 주의

- 본 문서는 MVP 기준 초안이다.
- 구현 과정에서 값 범위, 상태 전이, migration 세부 규칙은 버전업 문서로 고정한다.
