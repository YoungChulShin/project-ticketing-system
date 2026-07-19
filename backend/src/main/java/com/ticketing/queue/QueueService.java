package com.ticketing.queue;

import com.ticketing.config.QueueProperties;
import com.ticketing.queue.dto.ClaimResult;
import com.ticketing.queue.dto.CompleteResult;
import com.ticketing.queue.dto.JoinResult;
import com.ticketing.queue.dto.MeResult;
import com.ticketing.queue.dto.PromoteResult;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
public class QueueService {

    private static final Logger log = LoggerFactory.getLogger(QueueService.class);
    private static final DateTimeFormatter RESERVATION_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final QueueProperties properties;
    private final RedisScript<String> claimScript;
    private final RedisScript<String> completeScript;
    @SuppressWarnings("rawtypes")
    private final RedisScript<List> promoteScript;

    @SuppressWarnings("rawtypes")
    public QueueService(
            StringRedisTemplate redisTemplate,
            Clock clock,
            QueueProperties properties,
            RedisScript<String> claimScript,
            RedisScript<String> completeScript,
            RedisScript<List> promoteScript) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
        this.properties = properties;
        this.claimScript = claimScript;
        this.completeScript = completeScript;
        this.promoteScript = promoteScript;
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

    /**
     * 예매 완료. complete.lua가 active 확인과 제거(자리 반납)를 원자적으로 처리한다.
     *
     * <p>결제 등 실제 예매 도메인은 범위 밖 — 예매 번호 발급과 로그로 대체한다.
     */
    public CompleteResult complete(String token) {
        String result = redisTemplate.execute(
                completeScript,
                List.of(QueueKeys.ACTIVE),
                token, String.valueOf(clock.millis()));

        return switch (result) {
            case "COMPLETED" -> {
                String reservationId = nextReservationId();
                log.info("예매 완료 — token={}, reservationId={}", token, reservationId);
                yield CompleteResult.completed(reservationId);
            }
            case "NOT_ACTIVE" -> CompleteResult.notActive();
            case "EXPIRED" -> CompleteResult.expired();
            default -> throw new IllegalStateException("complete.lua 예상 밖 반환값: " + result);
        };
    }

    /**
     * 승격 실행. promote.lua가 만료 정리 → 빈자리 계산 → 승격을 원자 처리한다.
     *
     * <p>여러 인스턴스가 동시에 호출해도 안전 — 먼저 실행된 쪽이 승격하고 나면
     * 나중 쪽은 빈자리 0을 보고 아무것도 하지 않는다.
     */
    @SuppressWarnings("unchecked")
    public PromoteResult promote() {
        long now = clock.millis();
        long claimDeadline = now + properties.eligibleClaimSeconds() * 1000;

        List<?> result = redisTemplate.execute(
                promoteScript,
                List.of(QueueKeys.WAITING, QueueKeys.ELIGIBLE, QueueKeys.ACTIVE, QueueKeys.DEQUEUED),
                String.valueOf(now), String.valueOf(properties.capacity()), String.valueOf(claimDeadline));

        return new PromoteResult((List<String>) result.get(0), (List<String>) result.get(1));
    }

    private String nextReservationId() {
        Long seq = redisTemplate.opsForValue().increment(QueueKeys.RESERVATION_SEQ);
        String date = LocalDate.ofInstant(clock.instant(), clock.getZone()).format(RESERVATION_DATE);
        return "r-%s-%d".formatted(date, seq);
    }
}
