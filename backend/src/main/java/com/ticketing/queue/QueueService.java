package com.ticketing.queue;

import com.ticketing.queue.dto.JoinResult;
import com.ticketing.queue.dto.MeResult;
import java.time.Clock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class QueueService {

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;

    public QueueService(StringRedisTemplate redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    /**
     * [과제] 대기열 진입.
     *
     * <p>요구사항: QueueServiceTest의 join_* 테스트 3개를 통과시킬 것.
     * 사용 연산 힌트: INCR(opsForValue), ZADD NX(opsForZSet), ZRANK(opsForZSet)
     */
    public JoinResult join() {
        // 1. 토큰 발급: UUID 문자열 생성 — 이 사용자의 유일한 식별자
        String token = QueueTokenGenerator.generate();

        // 2. 순번 발급: queue:seq를 INCR — 반환값이 곧 내 순번 (원자적이라 중복 없음)
        Long sequence = redisTemplate.opsForValue().increment(QueueKeys.SEQ);

        // 3. 대기줄 등록: queue:waiting에 ZADD (member=토큰, score=순번)
        //    ※ NX 옵션(addIfAbsent)을 쓰는 이유: 같은 토큰 재등록 시 순번이 바뀌면 안 됨
        redisTemplate.opsForZSet().addIfAbsent(
            QueueKeys.WAITING,
            token,
            sequence);

        // 4. 내 앞 인원 계산: queue:waiting에서 ZRANK — 0-base 순위 = 앞사람 수
        Long rank = redisTemplate.opsForZSet().rank(QueueKeys.WAITING, token);

        // 5. JoinResult(토큰, 순번, 앞사람 수) 반환
        return new JoinResult(token, sequence, rank);
    }

    /**
     * [과제] 내 상태 조회.
     *
     * <p>요구사항: QueueServiceTest의 me_* 테스트 7개를 통과시킬 것.
     * 판정 우선순위: active → eligible → waiting → NOT_FOUND.
     * active/eligible은 score(만료 시각)가 현재 시각보다 과거면 EXPIRED.
     * 사용 연산 힌트: ZSCORE, ZRANK(opsForZSet), GET(opsForValue), clock.millis()
     */
    public MeResult me(String token) {
        // 1. active 확인: queue:active에서 ZSCORE(토큰)
        //    - 값이 있으면: score = lease 만료 시각(ms)
        //      · 만료 시각 <= 현재 시각(clock.millis()) → EXPIRED
        //      · 아직 유효 → ACTIVE (leaseDeadline에 score를 long으로 담아 반환)
        Double leaseMillis = redisTemplate.opsForZSet().score(QueueKeys.ACTIVE, token);
        if (leaseMillis != null) {
            if (leaseMillis <= clock.millis()) {
                return MeResult.expired();
            } else {
                return MeResult.active(leaseMillis.longValue());
            }
        }

        // 2. eligible 확인: queue:eligible에서 ZSCORE(토큰)
        //    - 값이 있으면: score = claim 마감 시각(ms)
        //      · 마감 지남 → EXPIRED
        //      · 유효 → ELIGIBLE (claimDeadline 담아 반환)
        Double eligibleMillis = redisTemplate.opsForZSet().score(QueueKeys.ELIGIBLE, token);
        if (eligibleMillis != null) {
            if (eligibleMillis <= clock.millis()) {
                return MeResult.expired();
            } else {
                return MeResult.eligible(eligibleMillis.longValue());
            }
        }

        // 3. waiting 확인: queue:waiting에서 ZRANK(토큰)
        //    - 값이 있으면(내가 줄에 있음):
        //      · ahead = rank (0-base 순위 = 앞사람 수)
        //      · dequeued = queue:dequeued GET (없으면 0)
        //      · WAIT<ING 반환
        Long waitingRank = redisTemplate.opsForZSet().rank(QueueKeys.WAITING, token);
        if (waitingRank != null) {
            String dequeued = redisTemplate.opsForValue().get(QueueKeys.DEQUEUED);

            return MeResult.waiting(
                waitingRank,
                dequeued == null ? 0L : Long.valueOf(dequeued));
        }

        // 4. 어디에도 없으면 NOT_FOUND
        return MeResult.notFound();
    }
}
