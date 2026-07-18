package com.ticketing.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.queue.dto.CompleteResult;
import com.ticketing.support.RedisTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * [Task 5 과제 명세] complete.lua가 통과시켜야 하는 테스트.
 * 실행: ./gradlew test --tests 'com.ticketing.queue.QueueCompleteTest'
 */
class QueueCompleteTest extends RedisTestSupport {

    @Autowired
    QueueService queueService;

    @Test
    void complete_active_사용자는_COMPLETED되고_자리가_반납된다() {
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "user-a", clock.millis() + 60_000);

        CompleteResult result = queueService.complete("user-a");

        assertThat(result.status()).isEqualTo(CompleteResult.Status.COMPLETED);
        // BASE_TIME(2026-07-18) 기준 첫 예매 번호
        assertThat(result.reservationId()).isEqualTo("r-20260718-1");
        // 자리 반납 — active에서 제거됨
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ACTIVE, "user-a")).isNull();
    }

    @Test
    void complete_active에_없는_토큰은_NOT_ACTIVE다() {
        CompleteResult result = queueService.complete("stranger");

        assertThat(result.status()).isEqualTo(CompleteResult.Status.NOT_ACTIVE);
        assertThat(result.reservationId()).isNull();
    }

    @Test
    void complete_lease가_지난_토큰은_EXPIRED되고_제거된다() {
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "user-late", clock.millis() + 60_000);

        clock.advance(Duration.ofSeconds(61));
        CompleteResult result = queueService.complete("user-late");

        assertThat(result.status()).isEqualTo(CompleteResult.Status.EXPIRED);
        assertThat(result.reservationId()).isNull();
        // lazy 정리: 만료 발견 즉시 제거
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ACTIVE, "user-late")).isNull();
    }
}
