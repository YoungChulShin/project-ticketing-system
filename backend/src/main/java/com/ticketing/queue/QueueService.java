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
     * 대기열 진입. 토큰과 전역 단조증가 순번을 발급하고 대기줄에 등록한다.
     */
    public JoinResult join() {
        String token = QueueTokenGenerator.generate();

        Long sequence = redisTemplate.opsForValue().increment(QueueKeys.SEQ);

        // NX: 같은 토큰이 재등록돼도 기존 순번을 유지해야 한다
        redisTemplate.opsForZSet().addIfAbsent(
            QueueKeys.WAITING,
            token,
            sequence);

        Long rank = redisTemplate.opsForZSet().rank(QueueKeys.WAITING, token);

        return new JoinResult(token, sequence, rank);
    }

    /**
     * 내 상태 조회. active → eligible → waiting 순으로 판정한다.
     *
     * <p>active/eligible의 score는 만료 시각(epoch ms)이므로, 스케줄러가 아직
     * 치우지 않은 만료 항목도 여기서 즉시 EXPIRED로 판정된다 (lazy 판정).
     */
    public MeResult me(String token) {
        Double leaseMillis = redisTemplate.opsForZSet().score(QueueKeys.ACTIVE, token);
        if (leaseMillis != null) {
            if (leaseMillis <= clock.millis()) {
                return MeResult.expired();
            }
            return MeResult.active(leaseMillis.longValue());
        }

        Double eligibleMillis = redisTemplate.opsForZSet().score(QueueKeys.ELIGIBLE, token);
        if (eligibleMillis != null) {
            if (eligibleMillis <= clock.millis()) {
                return MeResult.expired();
            }
            return MeResult.eligible(eligibleMillis.longValue());
        }

        Long waitingRank = redisTemplate.opsForZSet().rank(QueueKeys.WAITING, token);
        if (waitingRank != null) {
            String dequeued = redisTemplate.opsForValue().get(QueueKeys.DEQUEUED);

            return MeResult.waiting(
                waitingRank,
                dequeued == null ? 0L : Long.valueOf(dequeued));
        }

        return MeResult.notFound();
    }
}
