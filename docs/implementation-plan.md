# 티켓 예매 대기열 시스템 — 구현 계획

> 진행 방식: CLAUDE.md의 학습형 사이클 준수 — 태스크마다 설명 → 코드 → 확인 → 커밋 전 사용자 승인.
> `[사용자 과제]` 태스크는 사용자가 직접 구현한다. 이 문서에는 의도적으로 정답 코드가 없다
> (요구사항·인터페이스·완료 기준만). Claude는 과제 시작 시 실패하는 테스트를 먼저 제시한다 (TDD).

**목표:** docs/system-design.md 설계대로 동작하는 대기열 시스템을 로컬에서 실행 가능하게 구현

**아키텍처:** Redis ZSET 3개 + Lua 스크립트 3개(원자성), Spring Boot 무상태 서버 + 승격 스케줄러, SSE primary + polling fallback, React 프론트

**기술 스택:** Spring Boot 4.1.0 (Java 21), spring-data-redis (Lettuce), Redis 7 (docker), Testcontainers, React + Vite

## 전역 제약

- 백엔드: Spring Boot 4.1.0, Java 17+ (권장 21)
- 프론트: React + Vite, Node 20+
- 대기열 상태는 Redis에만 존재. 서버 메모리에는 SSE 연결 레지스트리만 허용
- 설정값은 application.yml `queue.*`로 외부화 (capacity=5, lease-seconds=60, eligible-claim-seconds=30, promote-interval-ms=500, progress-interval-ms=1000, sse-heartbeat-seconds=15)
- 현재 시각은 항상 주입된 `Clock`에서 얻는다. `System.currentTimeMillis()` 직접 호출 금지
- Lua 스크립트에서 시각은 ARGV로 주입 (`redis.call('TIME')` 사용 금지)
- 만료/자격없음 응답은 HTTP 200 + status enum
- 커밋 = 태스크 1개. 커밋 전 변경 사항 설명 + 사용자 확인

## 디렉토리 구조

```
backend/
├── build.gradle, settings.gradle, gradlew*
├── src/main/java/com/ticketing/
│   ├── TicketingApplication.java
│   ├── config/        # QueueProperties, ClockConfig, SchedulingConfig
│   ├── queue/         # QueueKeys, QueueService, QueueScripts, PromoteScheduler,
│   │   │              # QueueController, AdminController, dto/
│   │   └── event/     # QueueEventPublisher(인터페이스), DirectQueueEventPublisher
│   ├── sse/           # SseEmitterRegistry, QueueStreamController, ProgressBroadcaster
│   └── reservation/   # ReservationController, ReservationService(로그만)
├── src/main/resources/
│   ├── application.yml
│   └── scripts/       # claim.lua, complete.lua, promote.lua
└── src/test/java/com/ticketing/
    ├── support/RedisTestSupport.java   # Testcontainers 공통
    └── queue/ ...                      # 태스크별 테스트
frontend/
├── package.json, vite.config.js       # /api → :8080 프록시
└── src/
    ├── App.jsx, api.js, useQueue.js
    └── screens/Home.jsx, Waiting.jsx, Reservation.jsx, Result.jsx
docker-compose.yml                      # redis:7
```

---

## Task 1: 백엔드 뼈대 [Claude]

**산출물:** 부팅되는 Spring Boot 4.1.0 앱 + docker-compose Redis

- 사전 확인: `java -version`(17+), `docker --version`, `node -v`(20+)
- `docker-compose.yml`: redis:7-alpine, 6379 노출
- Gradle 프로젝트: spring-boot-starter-web, spring-boot-starter-data-redis, spring-boot-starter-validation, (test) spring-boot-starter-test + testcontainers junit-jupiter/redis 모듈
- `application.yml`: 전역 제약의 queue.* 값 전부
- 검증: `docker compose up -d` → `./gradlew bootRun` 부팅 성공, `redis-cli ping` → PONG
- 학습 포인트: Boot 4 구조, Lettuce 자동 구성

## Task 2: 테스트 인프라 + Redis 연동 [Claude]

**산출물:** Testcontainers로 진짜 Redis에 붙는 테스트 기반 + 설정 클래스

**Produces (이후 태스크가 사용):**
- `QueueKeys`: 상수 `SEQ="queue:seq"`, `WAITING="queue:waiting"`, `ELIGIBLE="queue:eligible"`, `ACTIVE="queue:active"`, `DEQUEUED="queue:dequeued"`
- `QueueProperties`: record, queue.* 바인딩 (capacity, leaseSeconds, eligibleClaimSeconds, promoteIntervalMs, progressIntervalMs, sseHeartbeatSeconds)
- `ClockConfig`: `Clock` 빈 (프로덕션 systemUTC). 테스트는 고정/가변 Clock 교체
- `RedisTestSupport`: `@Testcontainers` 추상 클래스, StringRedisTemplate 제공, 각 테스트 전 FLUSHALL

- 검증 테스트: INCR 두 번 → 1, 2 반환
- 실행: `./gradlew test`
- 학습 포인트: Testcontainers 동작 원리, @ConfigurationProperties, Clock 주입 패턴

## Task 3: QueueService — join + me [사용자 과제]

**산출물:** 줄서기와 상태 조회 로직 (Redis 명령 조합, Lua 아님)

**Interfaces (Produces):**
```java
// com.ticketing.queue.QueueService
JoinResult join()                    // 토큰 발급(UUID), INCR seq, ZADD NX waiting
                                     // JoinResult(String token, long sequence, long ahead)
MeResult me(String token)            // 상태 판정 우선순위: active → eligible → waiting → NOT_FOUND
                                     // MeResult(QueueStatus status, Long ahead, Long dequeued,
                                     //          Long claimDeadline, Long leaseDeadline)
enum QueueStatus { WAITING, ELIGIBLE, ACTIVE, EXPIRED, NOT_FOUND }
```

- Claude가 실패하는 테스트 제시 (join 순번 증가/중복 join 무시/ahead 계산, me 상태별 판정)
- 사용자 구현 → 리뷰(질문형 피드백) → 테스트 통과
- 힌트 범위: 사용할 명령(ZADD NX, ZRANK, ZSCORE, GET), 판정 순서. 코드는 안 줌
- 참고: 이 단계의 me()는 waiting/기타만 구분. eligible/active 만료 판정은 Task 4~6에서 정교화

## Task 4: claim.lua [사용자 과제]

**산출물:** 입장 확정 Lua 스크립트 + Spring 연동

**계약:**
```
KEYS[1]=queue:eligible, KEYS[2]=queue:active
ARGV[1]=토큰, ARGV[2]=now(ms), ARGV[3]=lease만료시각(ms)
반환: 'CLAIMED' | 'NOT_ELIGIBLE' | 'EXPIRED'
로직: 설계 문서 6장 claim 순서 1~5 그대로
```

- Claude 제공: `QueueScripts`(RedisScript 로딩 보일러플레이트) + 실패 테스트 3개(정상/미자격/자격만료)
- 사용자 작성: claim.lua 본문 + QueueService.claim() 연결
- 학습 포인트: EVALSHA, Lua에서 tonumber 필요성

## Task 5: complete.lua + 예매 로그 [사용자 과제]

**산출물:** 예매 완료 처리

**계약:**
```
KEYS[1]=queue:active
ARGV[1]=토큰, ARGV[2]=now(ms)
반환: 'COMPLETED' | 'NOT_ACTIVE' | 'EXPIRED'
```

- 사용자 작성: complete.lua + QueueService.complete() (COMPLETED면 reservationId 생성 `r-<날짜>-<seq>` + INFO 로그)
- 테스트: 정상 완료 후 active에서 사라짐 / lease 만료 후 호출 → EXPIRED

## Task 6: promote.lua + 동시성 불변식 테스트 [사용자 과제 — 최고 난도]

**산출물:** 승격 스크립트 (시스템의 심장)

**계약:**
```
KEYS[1]=waiting, KEYS[2]=eligible, KEYS[3]=active, KEYS[4]=dequeued
ARGV[1]=now(ms), ARGV[2]=K, ARGV[3]=eligible만료시각(ms)
반환: {만료자 토큰 배열, 승격자 토큰 배열}
로직: 설계 문서 6장 promote 순서 1~9 그대로
```

- 테스트 (Claude 제시, 설계 11장 불변식):
  1. 동시 join 100 → 순번 유일 (CountDownLatch)
  2. promote 반복 + 동시 claim 폭주 → `ZCARD active ≤ K` 항상
  3. FIFO: join 순서 = 승격 순서
  4. Clock 61초 전진 → promote → 만료자 반환 + 다음 승격
  5. eligible 31초 미claim → 노쇼 제거
- 학습 포인트: Lua 테이블 반환, ZPOPMIN 복수 인자, 원자성의 실제 효과 관찰

## Task 7: REST API [Claude]

**산출물:** 컨트롤러 5개 + DTO (설계 8장 명세 그대로)

- `QueueController`: POST /api/queue/join, GET /api/queue/me, POST /api/queue/claim
- `ReservationController`: POST /api/reservations
- `AdminController`: GET /api/admin/queue (세 ZSET WITHSCORES + 카운터)
- 테스트: MockMvc 대신 통합(RestClient) 스모크 — join→me 흐름
- 학습 포인트: 200+status enum 설계의 컨트롤러 단순함

## Task 8: 승격 스케줄러 + 이벤트 발행 구조 [Claude]

**산출물:** promote 주기 실행 + 통지 추상화 (2차 확장 대비 seam)

**Produces:**
```java
interface QueueEventPublisher {
  void admitted(List<String> tokens);   // 승격자 통지
  void expired(List<String> tokens);    // 만료자 통지
}
// 1차 구현: DirectQueueEventPublisher → SseEmitterRegistry 직접 호출 (Task 9에서 연결)
```

- `PromoteScheduler`: @Scheduled(fixedDelayString="${queue.promote-interval-ms}") → promote.lua → 결과를 publisher로
- 테스트: 스케줄러 없이 직접 호출로 검증 (스케줄링 자체는 부팅 로그 확인)
- 학습 포인트: @Scheduled fixedDelay vs fixedRate, 다중 서버에서 중복 실행이 안전한 이유 복습

## Task 9: SSE [반반 — 뼈대 Claude, 통지 연결 사용자]

**산출물:** 실시간 채널 완성

- Claude: `SseEmitterRegistry`(Map<token,SseEmitter>, 등록/제거/전송 + 끊긴 연결 정리), `QueueStreamController`(GET /api/queue/stream), `ProgressBroadcaster`(@Scheduled 1s: dequeued/waiting/active broadcast + 15s heartbeat)
- 사용자: DirectQueueEventPublisher 구현 — promote 결과(승격/만료 명단)를 registry 통해 개인 이벤트로 전송
- 검증: `curl -N localhost:8080/api/queue/stream?token=...`으로 이벤트 눈으로 확인 → join한 토큰이 승격될 때 admission 수신
- 학습 포인트: SseEmitter 생명주기(timeout/completion), text/event-stream 원문 관찰

## Task 10: React 프론트 [Claude — 상세 해설 모드]

**산출물:** 화면 4개 동작

- 10a: Vite 스캐폴드 + `vite.config.js` 프록시(/api→8080) + `api.js`(fetch 래퍼)
- 10b: `useQueue.js` — phase 상태 머신, EventSource 연결/재연결, 3회 실패→5s 폴링 fallback→60s마다 SSE 재시도, admission 수신 시 자동 claim, 30s /me 보정, lease 카운트다운
- 10c: 화면 4개 + transport 표시(SSE ●/폴링 ○)
- 검증: 탭 6개 열어 5명 입장 + 1명 대기 → 만료/완료 시 자동 승격 관찰
- 학습 포인트: React 상태/이펙트 기초, 훅으로 로직 격리

## Task 11: E2E 시나리오 + README [같이]

- README.md: 실행 방법 3단계, 설정값 실험 가이드 (capacity=1 병목 관찰, lease=10 만료 러시)
- 수동 시나리오 체크리스트: 다중 탭, 서버 재시작(대기열 생존 확인), SSE 강제 차단→폴링 전환 관찰, `redis-cli MONITOR` 병행
- 완료 시 브레인스토밍 때 논의한 검증 항목 전부 커버

## Task 12 (2차): Redis Pub/Sub 다중 인스턴스 [사용자 과제 가능]

- 작업 목록 Task #1로 별도 추적 중. DirectQueueEventPublisher → RedisPubSubQueueEventPublisher 교체, :8080/:8081 교차 통지 실험

---

## 태스크 의존 순서

```
1 → 2 → 3 → 4 → 5 → 6 → 7 → 8 → 9 → 10 → 11 → (12)
                └ Lua 3부작: 쉬운 것부터 (claim → complete → promote)
```
