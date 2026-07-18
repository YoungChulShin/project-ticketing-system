package com.ticketing.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.queue.dto.ClaimResult;
import com.ticketing.support.RedisTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * [Task 4 과제 명세] claim.lua가 통과시켜야 하는 테스트.
 * 실행: ./gradlew test --tests 'com.ticketing.queue.QueueClaimTest'
 */
class QueueClaimTest extends RedisTestSupport {

    @Autowired
    QueueService queueService;

    @Test
    void claim_eligible_사용자는_CLAIMED되고_active로_이동하며_lease가_시작된다() {
        redisTemplate.opsForZSet().add(QueueKeys.ELIGIBLE, "user-e", clock.millis() + 30_000);
        long expectedLeaseDeadline = clock.millis() + 60_000;

        ClaimResult result = queueService.claim("user-e");

        assertThat(result.status()).isEqualTo(ClaimResult.Status.CLAIMED);
        assertThat(result.leaseDeadline()).isEqualTo(expectedLeaseDeadline);
        // active로 이동했고 score = lease 만료 시각
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ACTIVE, "user-e"))
                .isEqualTo((double) expectedLeaseDeadline);
        // eligible에서는 제거됨
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ELIGIBLE, "user-e")).isNull();
    }

    @Test
    void claim_eligible에_없는_토큰은_NOT_ELIGIBLE이다() {
        ClaimResult result = queueService.claim("stranger");

        assertThat(result.status()).isEqualTo(ClaimResult.Status.NOT_ELIGIBLE);
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ACTIVE, "stranger")).isNull();
    }

    @Test
    void claim_자격_시각이_지난_토큰은_EXPIRED되고_eligible에서_제거된다() {
        redisTemplate.opsForZSet().add(QueueKeys.ELIGIBLE, "user-late", clock.millis() + 30_000);

        clock.advance(Duration.ofSeconds(31));
        ClaimResult result = queueService.claim("user-late");

        assertThat(result.status()).isEqualTo(ClaimResult.Status.EXPIRED);
        // lazy 정리: 만료 발견 즉시 제거
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ELIGIBLE, "user-late")).isNull();
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ACTIVE, "user-late")).isNull();
    }
}
