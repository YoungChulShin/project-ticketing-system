# 공연 티켓 예매 대기열 시스템 — 설계 문서

작성일: 2026-07-17

## 1. 목적

대기열 시스템을 프로덕션 수준 패턴으로 직접 구현하며 학습하는 프로젝트.
결제 등 부가 도메인은 범위 밖 (로그 기록으로 대체). 로컬 실행이 기본 환경.

### 요구사항

- 동시에 K명(기본 5명)만 예매 페이지에 입장 가능. 나머지는 대기열에서 대기
- 대기 중에는 "앞에 몇 명 남았는지"가 화면에 계속 갱신 표시
- 대기 인원이 0이 되면 자동 입장
- 입장한 사용자는 최대 60초 동안 예매 가능. 초과 시 퇴장 처리 후 다음 대기자 입장

## 2. 기술 결정 요약

| 항목 | 결정 | 핵심 근거 |
|---|---|---|
| 대기열 저장소 | Redis Sorted Set | 실무 표준 패턴. 상태를 서버 밖에 두어 무상태 서버 + 수평 확장 |
| 순번 전달 | SSE primary + polling fallback | 대규모 연결 플랫폼 보유 조직(네이버/카카오급) 가정. codex CLI 자문 반영 |
| 입장 방식 | 2-phase (ELIGIBLE 승격 → claim 시 lease 시작) | 통지 지연이 사용자의 예매 시간을 깎지 않도록 |
| 순번 계산 | 글로벌 dequeued 카운터 + 클라이언트 뺄셈 + 주기 보정 | 개인별 ZRANK 매초 실행 회피 (read amplification 방지) |
| 백엔드 | Spring Boot 4.1.0 | — |
| 프론트엔드 | React + Vite | 상태 기반 UI. SSE/폴링 로직을 custom hook으로 격리 |
| 사용자 식별 | 로그인 없음. join 시 UUID 토큰 발급 (localStorage 보관) | 학습 범위 축소 |

### transport 결정 배경 (codex 자문 요약)

- 범용 조직 기준 정답은 adaptive polling (Queue-it, NetFunnel이 실제 사용하는 방식)
- 단, 대규모 장기 연결 플랫폼을 갖춘 조직 가정 시 SSE primary + polling fallback이 우세
- 어느 쪽이든 불변인 핵심: 입장/타임아웃은 transport가 아닌 Redis의 원자적 lease로 관리,
  SSE 전달 성공 여부가 입장 권한의 source of truth가 되면 안 됨

## 3. 아키텍처

```
┌─────────────┐   SSE 스트림 (순번 갱신, 입장 통지)
│   브라우저    │◀──────────────────────────────┐
│ React + Vite│   REST API (join/claim/예매)    │
│   (:5173)   │───────────────────────────────▶│
└─────────────┘                          ┌──────┴──────┐
                                         │ Spring Boot │
                                         │   (:8080)   │
                                         └──────┬──────┘
                                                │ Lettuce
                                         ┌──────▼──────┐
                                         │  Redis 7    │
                                         │  (:6379)    │
                                         │ docker 실행  │
                                         └─────────────┘
```

책임 분리:

- **Redis** — 대기열 상태의 유일한 source of truth. 서버 메모리에 대기열 상태를 두지 않음
- **Spring Boot** — 무상태 로직 계층. API 처리 + 승격 스케줄러
- **React** — 상태 표현 계층. SSE 수신, 폴링 fallback 전환, 화면 렌더

서버 메모리에 남는 유일한 것은 SSE 연결 레지스트리(Map<token, SseEmitter>) —
연결 상태는 원래 소멸성이므로 무상태 원칙 위반이 아님. 서버 재시작 시 클라이언트가
재연결하면 복구되고, 대기 순번은 Redis에 그대로 유지된다.

## 4. Redis 스키마

| 키 | 타입 | score | 용도 |
|---|---|---|---|
| `queue:seq` | String | — | 순번 발급기. `INCR` 반환값 = 진입 순번 |
| `queue:waiting` | ZSET | 진입 순번 | 대기줄 (FIFO). member = 사용자 토큰 |
| `queue:eligible` | ZSET | 자격 만료 시각 (epoch ms) | 승격 후 claim 대기. 유예 30초 |
| `queue:active` | ZSET | lease 만료 시각 (epoch ms) | 예매 진행 중. 최대 K명 |
| `queue:dequeued` | String | — | 누적 승격 수. 근사 순번 계산의 기준 |

설계 포인트: 같은 ZSET이지만 score 의미가 다름 —
`waiting`은 score=순번이라 **FIFO 큐**, `eligible`/`active`는 score=만료시각이라
**타이머 목록** (`ZRANGE ... BYSCORE 0 now`로 만료자 일괄 조회). 사용자별 서버 타이머 불필요.

## 5. 상태 머신

```
        join                승격(스케줄러)         claim(자동)
  ────▶ WAITING ─────────▶ ELIGIBLE ─────────▶ ACTIVE ──┬──▶ DONE (예매 완료)
                              │ 30초 내 claim 없음        │ 60초 lease 만료
                              ▼                          ▼
                           제거 (재입장 필요)           EXPIRED (튕김)
```

- "튕김"의 실체 = ZSET에서 삭제. 만료자의 이후 요청은 조회 실패로 `EXPIRED` 응답
- 노쇼(ELIGIBLE 미claim) 정책: 완전 제거. "줄 뒤로 보내기"로 변경 가능 (정책 선택지)
- 2-phase 이유: 승격 시점이 아닌 **claim 시점부터 lease 시작** —
  통지·폴링 지연이 사용자의 60초를 깎지 않음

## 6. 원자성 — Lua 스크립트 3개

여러 Redis 명령을 조합하는 로직(읽기→판단→쓰기)은 명령 사이에 다른 요청이 끼어들면
정원 초과가 발생한다 (read-modify-write race). Redis는 싱글 스레드이므로 Lua 스크립트
전체가 원자 단위로 실행됨 — 락 없이 구조적으로 race 차단.

공통 패턴: **확인 → (만료면 lazy 정리 후 거절) → 상태 이동 → 결과 반환.**
현재 시각은 스크립트 내부에서 읽지 않고 서버가 ARGV로 주입 (시계 단일화 + 테스트 가능성).

### `promote` — 승격 스케줄러 (500ms 주기, @Scheduled)

1. `ZRANGE active 0 now BYSCORE` — lease 만료자 명단 조회 (통지용)
2. `ZREMRANGEBYSCORE active 0 now` — 만료자 퇴장
3. `ZREMRANGEBYSCORE eligible 0 now` — 노쇼 제거
4. `ZCARD active`, `ZCARD eligible` — 현원 파악
5. 빈자리 = K − active − eligible. 0 이하면 종료 (eligible은 곧 active가 될 예약석이므로 함께 계산)
6. `ZPOPMIN waiting <빈자리>` — 줄 앞에서 꺼냄
7. `ZADD eligible (now+30s) <토큰들>` — 자격 부여
8. `INCRBY dequeued <승격 수>`
9. 반환: [만료자 명단, 승격자 명단] → Spring이 SSE 통지

스케줄러는 다중 서버에서 동시 실행돼도 안전 (Lua 원자성 — 한 대가 승격하면 다른 대는 빈자리 0).

### `claim` — 입장 확정 (승격 통지 수신 시 클라이언트가 자동 호출)

1. `ZSCORE eligible <토큰>` — 없으면 `NOT_ELIGIBLE`
2. score < now → `ZREM` 후 `EXPIRED`
3. `ZREM eligible <토큰>`
4. `ZADD active (now+60s) <토큰>` — lease 시작
5. `CLAIMED` 반환

### `complete` — 예매

1. `ZSCORE active <토큰>` — 없으면 `NOT_ACTIVE`
2. score < now → `ZREM` 후 `EXPIRED`
3. `ZREM active <토큰>` — 자리 반납
4. `COMPLETED` 반환 → Spring이 예매 로그 기록

## 7. SSE 설계

SSE = `Content-Type: text/event-stream`인 끝나지 않는 HTTP 응답.
브라우저는 내장 `EventSource`(자동 재연결 포함), 서버는 `SseEmitter`.

### 이벤트

| 이벤트 | 대상 | 주기 | 내용 |
|---|---|---|---|
| `progress` | 전원 broadcast | 1초 | `{dequeued, waiting 수, active 수}` |
| `admission` | 승격된 개인 | promote 직후 | claim 마감 시각 |
| `expired` | 만료된 개인 | promote 직후 | 만료 통지 |
| heartbeat (주석 줄) | 전원 | 15초 | 프록시/LB 유휴 종료 방지 |

### 근사 순번 계산

서버가 개인별 순번을 계산해 보내지 않음. 클라이언트가:

```
표시 순번 = (마지막 exact ahead) − (현재 dequeued − exact 조회 시점의 dequeued)
```

- exact ahead = `/me`의 `ZRANK` 결과. 30초마다 보정
- 오차 원인: 승격 없이 이탈한 대기자 (표시가 실제보다 많게 나오는 보수적 오차)
- 효과: 대기자 수와 무관하게 서버 broadcast는 초당 1건

### Polling fallback (클라이언트 상태 머신)

```
SSE 연결 ──성공──▶ SSE 모드
   │ 3회 연속 실패
   ▼
폴링 모드: GET /api/queue/me 5초 주기 ──60초마다 SSE 재시도──▶ 복귀
```

`/me`는 SSE와 동일 정보 + 내 상태를 반환 → transport가 바뀌어도 클라이언트 상태 머신은 하나.
ELIGIBLE 통지를 SSE로 놓쳐도 다음 폴링에서 발견해 claim — 통지 유실이 입장 권리를 뺏지 못함.

## 8. API 명세

| # | 엔드포인트 | 역할 | 내부 |
|---|---|---|---|
| 1 | `POST /api/queue/join` | 줄서기 | `INCR seq` → `ZADD NX waiting` |
| 2 | `GET /api/queue/stream?token=` | SSE 연결 | SseEmitter 등록 |
| 3 | `GET /api/queue/me?token=` | 내 상태 (fallback + 보정) | ZSET 조회 + `ZRANK` |
| 4 | `POST /api/queue/claim` | 입장 확정 | Lua `claim` |
| 5 | `POST /api/reservations` | 예매 | Lua `complete` + 로그 |
| 6 | `GET /api/admin/queue` | 관찰용 전체 상태 | `ZRANGE WITHSCORES` |

```
POST /api/queue/join
→ { "token": "uuid", "sequence": 1006, "ahead": 998 }

GET /api/queue/me?token=uuid
→ { "status": "WAITING",  "ahead": 998, "dequeued": 7 }
→ { "status": "ELIGIBLE", "claimDeadline": 1784251290000 }
→ { "status": "ACTIVE",   "leaseDeadline": 1784251350000 }
→ { "status": "EXPIRED" } / { "status": "NOT_FOUND" }

POST /api/queue/claim        { "token": "uuid" }
→ { "status": "CLAIMED", "leaseDeadline": ... } | NOT_ELIGIBLE | EXPIRED

POST /api/reservations       { "token": "uuid" }
→ { "status": "COMPLETED", "reservationId": "r-..." } | NOT_ACTIVE | EXPIRED
```

- 만료/자격없음은 HTTP 에러가 아닌 200 + status enum (정상 상태 전이이므로).
  4xx는 형식 오류(토큰 누락 등)에만
- 별도 admission token 없음: 단일 앱이므로 `active` 멤버십(`ZSCORE`)이 곧 권한 검증.
  대기열/예매 서비스가 분리된 시스템이라면 서명 토큰 필요 (프로덕션 참고사항)

## 9. 프론트엔드 구성

화면 4개 (phase 기반 전환): 홈 → 대기(앞에 N명 + transport 표시) → 예매(카운트다운) → 결과(완료/시간초과).

```
App.jsx
 └─ useQueue()  ← 로직 전부: phase 상태 머신, SSE/폴링 전환,
 │              admission 수신 시 자동 claim, /me 보정, 카운트다운
 │              반환: { phase, ahead, leaseRemaining, transport, join(), reserve() }
 ├─ Home / Waiting / Reservation / Result  (렌더만, 로직 0)
```

대기 화면의 transport 표시(SSE ●/폴링 ○)는 fallback 전환을 눈으로 관찰하기 위한 학습 장치.

## 10. 다중 서버 확장 (2차 로드맵)

대기열 상태·API·스케줄러는 Redis + Lua 설계 덕에 이미 다중 서버 안전.
유일하게 깨지는 것은 SSE 통지 라우팅 (연결이 서버별 메모리에 있으므로).

- **1차**: 서버 1대 완성. 통지는 `QueueEventPublisher` 인터페이스로 분리 (구현체 = 직접 호출)
- **2차**: 구현체를 Redis Pub/Sub으로 교체 (`PUBLISH queue:events`, 전 서버 구독,
  자기 연결만 전송). 로컬에서 :8080/:8081 두 인스턴스 + 탭 분산 접속으로 교차 통지 검증

## 11. 테스트 전략

핵심 불변식 5개:

1. 순번 중복 없음 — 동시 join 100 (CountDownLatch)
2. **active ≤ K 절대 초과 없음** — 동시 claim 폭주 후 ZCARD 검증 (가장 중요)
3. FIFO 순서 보장
4. lease 만료 → 퇴장 + 다음 승격
5. 만료자 예매 시도 → EXPIRED

도구:

- **Testcontainers Redis** — Lua는 mock으로 실행 불가, 진짜 Redis로 검증
- **Clock 주입** — now를 ARGV로 넘기는 설계 덕에 sleep 없이 시간 조작 테스트
- SSE는 통합 테스트 1개 + 브라우저 다중 탭 수동 관찰 (`redis-cli MONITOR` 병행)

## 12. 설정값

```yaml
queue:
  capacity: 5                  # K. 동시 예매 인원
  lease-seconds: 60            # 입장 후 예매 제한시간
  eligible-claim-seconds: 30   # 승격 후 claim 유예
  promote-interval-ms: 500     # 승격 스케줄러 주기
  progress-interval-ms: 1000   # SSE broadcast 주기
  sse-heartbeat-seconds: 15
```

프론트 상수: `/me` 보정 30초, 폴링 fallback 5초, SSE 실패 판정 3회.

## 13. 구현 순서 (개요)

1. 프로젝트 뼈대 (Gradle + Spring Boot 4.1.0, docker-compose Redis, React + Vite)
2. Redis 연동 + 키/명령 래퍼
3. Lua 스크립트 3개 + Testcontainers 테스트 (불변식 1~5)
4. REST API (join/me/claim/reservations/admin)
5. 승격 스케줄러 + SSE (stream, progress broadcast, 개인 통지)
6. React 화면 + useQueue 훅
7. (2차) Redis Pub/Sub 다중 인스턴스
