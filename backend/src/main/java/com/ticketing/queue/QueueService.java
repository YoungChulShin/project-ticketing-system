package com.ticketing.queue;

import com.ticketing.config.QueueProperties;
import com.ticketing.queue.dto.ClaimResult;
import com.ticketing.queue.dto.JoinResult;
import com.ticketing.queue.dto.MeResult;
import java.time.Clock;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
public class QueueService {

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final QueueProperties properties;
    private final RedisScript<String> claimScript;

    public QueueService(
            StringRedisTemplate redisTemplate,
            Clock clock,
            QueueProperties properties,
            RedisScript<String> claimScript) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.properties = properties;
        this.claimScript = claimScript;
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

    /**
     * 입장 확정. claim.lua가 eligible 확인과 active 이동을 원자적으로 처리한다.
     *
     * <p>lease 만료 시각은 여기(Java)서 계산해 ARGV로 넘긴다 — 시계의 단일
     * 출처를 서버 Clock으로 유지하기 위함 (Lua 안에서 TIME을 읽지 않는다).
     */
    public ClaimResult claim(String token) {
        long now = clock.millis();
        long leaseDeadline = now + properties.leaseSeconds() * 1000;

        String result = redisTemplate.execute(
                claimScript,
                List.of(QueueKeys.ELIGIBLE, QueueKeys.ACTIVE),
                token, String.valueOf(now), String.valueOf(leaseDeadline));

        return switch (result) {
            case "CLAIMED" -> ClaimResult.claimed(leaseDeadline);
            case "NOT_ELIGIBLE" -> ClaimResult.notEligible();
            case "EXPIRED" -> ClaimResult.expired();
            default -> throw new IllegalStateException("claim.lua 예상 밖 반환값: " + result);
        };
    }
}
