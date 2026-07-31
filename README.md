# 공연 티켓 예매 대기열 시스템

동시 입장 인원을 제한하고 나머지는 대기열에서 순번을 기다리는 예매 시스템.
대기열 메커니즘 학습이 목적인 프로젝트다. 결제 등 실제 예매 도메인은 범위 밖(로그로 대체).

- **요구사항**: 동시에 K명만 예매 페이지 입장, 나머지는 대기(앞에 몇 명 표시), 입장 후 60초 내 예매·초과 시 퇴장 후 다음 대기자 입장
- **스택**: Spring Boot 4.1 + Redis 7 (백엔드), React + Vite (프론트)
- 설계 상세: [docs/system-design.md](docs/system-design.md)

## 실행

3개 프로세스가 필요하다.

```bash
# 1. Redis (docker)
docker-compose up -d          # 호스트 6380 포트로 노출

# 2. 백엔드 (:8080)
cd backend && ./gradlew bootRun

# 3. 프론트 (:5173)
cd frontend && npm install && npm run dev
```

브라우저에서 http://localhost:5173 접속.

- **여러 사용자 시뮬레이션**: 일반 탭 + **시크릿 창 여러 개**로 접속.
  localStorage 토큰이 브라우저 프로필마다 분리되므로 시크릿 창 하나 = 사용자 하나.
- **테스트**: `cd backend && ./gradlew test` (Testcontainers가 Redis 컨테이너 자동 기동)

## 동작 원리

### 상태 머신

```
        join                promote(스케줄러)      claim(자동)
  ────▶ WAITING ─────────▶ ELIGIBLE ─────────▶ ACTIVE ──┬──▶ DONE (예매 완료)
                              │ 30초 내 미claim           │ 60초 lease 만료
                              ▼                          ▼
                           제거 (재입장 필요)           EXPIRED (튕김)
```

- **상태는 전부 Redis에** (ZSET 3개 + 카운터). 서버는 무상태 → 재시작·확장에 대기열 영향 없음
- **"튕김" = ZSET에서 삭제.** lease 만료 시각을 score로 저장해, 스케줄러가 `ZRANGEBYSCORE 0 now`로 만료자를 일괄 조회 (서버 타이머 불필요)
- **원자성은 Lua로.** promote/claim/complete를 각각 Lua 스크립트로 실행 — Redis 싱글 스레드가 스크립트 전체를 원자 단위로 처리해 정원 초과(race condition)를 락 없이 차단
- **2-phase 입장**: 승격(ELIGIBLE) 시점이 아니라 사용자가 claim한 시점부터 60초 lease 시작 → 통지·폴링 지연이 예매 시간을 깎지 않음

### Redis 데이터 구조

상태 머신의 각 상태가 곧 "어느 키에 들어있느냐"다. 사용자 토큰(UUID)이 아래 ZSET들 사이를 이동한다.

| 키 | 타입 | member | score | 의미 |
|---|---|---|---|---|
| `queue:seq` | String | — | — | 진입 순번 발급기 (`INCR` 반환값 = 내 순번) |
| `queue:waiting` | ZSET | 토큰 | **진입 순번** | 대기줄. score 작을수록 앞 |
| `queue:eligible` | ZSET | 토큰 | **claim 마감 시각(ms)** | 승격됨, claim 대기 중 |
| `queue:active` | ZSET | 토큰 | **lease 만료 시각(ms)** | 예매 진행 중 (최대 K명) |
| `queue:dequeued` | String | — | — | 누적 승격 수 (근사 순번 계산용) |

**핵심 아이디어 — 같은 ZSET이지만 score의 의미가 다르다:**

- `waiting`은 score가 *순번* → **FIFO 큐**로 동작. `ZRANK`로 "내 앞 몇 명", `ZPOPMIN`으로 "맨 앞부터 꺼내기"
- `eligible`/`active`는 score가 *만료 시각* → **타이머 목록**으로 동작. `ZRANGEBYSCORE 0 now` 한 번이면 만료자 전원 조회

이 두 번째가 "1분 지나면 튕김"을 구현하는 방식이다. 사용자마다 서버에 타이머(`ScheduledFuture`)를 걸면 서버 재시작 시 소멸하고 다중 서버에서 동작하지 않는다. 만료 시각을 **데이터로 저장**하면 상태가 전부 Redis에 있어, 서버는 주기적으로 `ZRANGEBYSCORE`만 물어보면 된다 — 타이머 10만 개 대신 쿼리 하나.

**연산 복잡도** (hot path에는 O(log N) 이하만 사용):

| 연산 | 복잡도 | 쓰이는 곳 |
|---|---|---|
| `INCR`, `ZCARD`, `ZSCORE` | O(1) | 순번 발급, 정원 확인, 상태 조회 |
| `ZADD`, `ZRANK` | O(log N) | 줄서기, 내 순번 조회 |
| `ZRANGEBYSCORE`, `ZPOPMIN` | O(log N + M) | 만료자 조회, 승격 (M = 대상 수) |

N=10만이어도 log₂(100000)≈17. 전체 상태 조회(`ZRANGE 0 -1`, O(N))는 관찰용 admin API에만 두고 hot path에서는 쓰지 않는다.

### 상태 전이와 Redis 연산

각 전이가 실제로 어떤 Redis 명령인지:

| 전이 | 트리거 | Redis 연산 (Lua) |
|---|---|---|
| → WAITING | `join` | `INCR seq` → `ZADD NX waiting` |
| WAITING → ELIGIBLE | promote 스케줄러 | `ZPOPMIN waiting` → `ZADD eligible` (+`INCRBY dequeued`) |
| ELIGIBLE → ACTIVE | `claim` (자동) | `ZREM eligible` → `ZADD active` (lease 시작) |
| ACTIVE → DONE | `complete` (예매) | `ZREM active` (자리 반납) |
| ACTIVE → EXPIRED | lease 만료 | promote가 `ZREMRANGEBYSCORE active 0 now` |
| ELIGIBLE → 제거 | 노쇼(claim 안 함) | promote가 `ZREMRANGEBYSCORE eligible 0 now` |

promote 스케줄러(500ms)가 만료 정리 + 빈자리 계산(`K − ZCARD(active) − ZCARD(eligible)`) + 승격을 **하나의 Lua 스크립트**로 원자 실행한다. `eligible`을 정원에 함께 세는 이유는 "곧 active가 될 예약석"이기 때문 — 안 세면 claim이 몰릴 때 정원을 초과한다.

### 순번 전달 — adaptive polling

클라이언트가 `GET /api/queue/me`를 주기적으로 폴링한다. **서버가 응답의 `nextPollAfterMs`로 다음 폴링 간격을 지시**하고, 클라이언트는 ±20% jitter를 얹어 재요청한다.

| 상태 | 폴링 간격 |
|---|---|
| WAITING, 앞 ≤ 5명 | 2초 |
| WAITING, 앞 ≤ 20명 | 5초 |
| WAITING, 앞 > 20명 | 10초 (멀수록 드물게 → 서버 부하↓) |
| ELIGIBLE / ACTIVE | 1초 |
| EXPIRED / NOT_FOUND | 0 (폴링 중단) |

DevTools Network에서 `me` 요청 간격이 순번에 따라 달라지는 걸 관찰할 수 있다.

## 설정값 실험

`backend/src/main/resources/application.yml`의 `queue.*`를 바꾸고 백엔드를 재시작하면 동작이 달라진다.

```yaml
queue:
  capacity: 3                  # 동시 예매 인원 K
  lease-seconds: 60            # 입장 후 예매 제한시간
  eligible-claim-seconds: 30   # 승격 후 claim 유예 (노쇼 판정)
  promote-interval-ms: 500     # 승격 스케줄러 주기
```

- `capacity: 1` → 병목 극대화, 대기열 관찰 쉬움
- `lease-seconds: 10` → 만료 러시 관찰

## 설계 노트 — 왜 SSE가 아니라 폴링인가

순번 전달 방식으로 폴링/SSE/WebSocket을 검토했고, **폴링(adaptive)**을 택했다.

- **폴링 부하는 조절 가능, 연결 부하는 아니다.** 대기 수백만이어도 서버가 폴링 간격을 늘리면(먼 순번 30~40초) RPS가 급감한다. 반면 SSE/WebSocket의 장기 연결 수는 줄일 방법이 없다 (연결당 메모리·FD·재연결 폭풍).
- **폴링은 무상태 → 수평 확장·장애 복구가 공짜.** 어느 서버가 요청을 받아도 Redis만 보면 되고, 연결이 끊겨도 다음 요청이 알아서 복구한다.
- **개인 순번 계산 비용은 transport와 무관.** push로 바꿔도 사용자별 ZRANK 계산은 그대로다 — push는 전달 방식일 뿐 계산을 없애지 않는다.
- 실제로 Queue-it, NetFunnel 등 대규모 대기열 상용 솔루션이 adaptive 폴링을 쓴다.

**SSE 서버 코드(`sse/`, `queue/event/`)는 미사용 상태로 남겨두었다** — 폴링 vs SSE 비교 실험 여지. 클라이언트에서 `EventSource`를 켜면 다시 활성화된다.

### 다중 서버 확장

대기열 상태·API·승격 스케줄러는 Redis + Lua 설계 덕에 이미 다중 인스턴스 안전하다
(promote를 여러 서버가 동시에 돌려도 원자성으로 정원 초과 없음 — `QueueConcurrencyTest`로 검증).
폴링은 무상태라 서버를 로드밸런서 뒤에 그냥 늘리면 된다.

## 프로젝트 구조

```
backend/                    Spring Boot
  src/main/java/com/ticketing/
    queue/                  QueueService, 컨트롤러, 스케줄러, DTO
    queue/event/            통지 추상화 (미사용 — SSE 실험용)
    sse/                    SSE 레지스트리·스트림·방송 (미사용 — 폴링 채택)
    config/                 설정(QueueProperties), Clock 빈
  src/main/resources/scripts/   promote.lua, claim.lua, complete.lua
frontend/                   React + Vite
  src/useQueue.js           대기열 클라이언트 두뇌 (adaptive 폴링 상태 머신)
  src/screens/              화면 4개 (렌더만, 로직은 훅에)
docs/                       설계·구현 계획 문서
```
