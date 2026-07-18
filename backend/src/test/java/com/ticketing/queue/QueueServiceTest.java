package com.ticketing.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.queue.dto.JoinResult;
import com.ticketing.queue.dto.MeResult;
import com.ticketing.support.RedisTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * [Task 3 과제 명세] 이 테스트를 전부 통과시키는 것이 과제.
 * 실행: ./gradlew test --tests 'com.ticketing.queue.QueueServiceTest'
 */
class QueueServiceTest extends RedisTestSupport {

    @Autowired
    QueueService queueService;

    // ── join ──────────────────────────────────────────────

    @Test
    void join_첫_사용자는_순번_1_앞에_0명() {
        JoinResult result = queueService.join();

        assertThat(result.token()).isNotBlank();
        assertThat(result.sequence()).isEqualTo(1);
        assertThat(result.ahead()).isZero();
    }

    @Test
    void join_순번은_단조증가하고_ahead는_앞사람_수() {
        queueService.join();
        queueService.join();

        JoinResult third = queueService.join();

        assertThat(third.sequence()).isEqualTo(3);
        assertThat(third.ahead()).isEqualTo(2);
    }

    @Test
    void join_토큰이_waiting에_순번을_score로_등록된다() {
        JoinResult result = queueService.join();

        Double score = redisTemplate.opsForZSet().score(QueueKeys.WAITING, result.token());
        assertThat(score).isEqualTo((double) result.sequence());
    }

    // ── me ────────────────────────────────────────────────

    @Test
    void me_대기_중이면_WAITING과_ahead_dequeued를_반환한다() {
        queueService.join();
        JoinResult second = queueService.join();
        redisTemplate.opsForValue().set(QueueKeys.DEQUEUED, "7");

        MeResult me = queueService.me(second.token());

        assertThat(me.status()).isEqualTo(QueueStatus.WAITING);
        assertThat(me.ahead()).isEqualTo(1);
        assertThat(me.dequeued()).isEqualTo(7);
    }

    @Test
    void me_dequeued_카운터가_없으면_0으로_반환한다() {
        JoinResult only = queueService.join();

        MeResult me = queueService.me(only.token());

        assertThat(me.dequeued()).isZero();
    }

    @Test
    void me_모르는_토큰이면_NOT_FOUND() {
        MeResult me = queueService.me("ghost-token");

        assertThat(me.status()).isEqualTo(QueueStatus.NOT_FOUND);
    }

    @Test
    void me_eligible이면_ELIGIBLE과_claimDeadline을_반환한다() {
        long deadline = clock.millis() + 30_000;
        redisTemplate.opsForZSet().add(QueueKeys.ELIGIBLE, "user-e", deadline);

        MeResult me = queueService.me("user-e");

        assertThat(me.status()).isEqualTo(QueueStatus.ELIGIBLE);
        assertThat(me.claimDeadline()).isEqualTo(deadline);
    }

    @Test
    void me_active면_ACTIVE와_leaseDeadline을_반환한다() {
        long deadline = clock.millis() + 60_000;
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "user-a", deadline);

        MeResult me = queueService.me("user-a");

        assertThat(me.status()).isEqualTo(QueueStatus.ACTIVE);
        assertThat(me.leaseDeadline()).isEqualTo(deadline);
    }

    @Test
    void me_lease가_지난_active_사용자는_EXPIRED다() {
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "user-a", clock.millis() + 60_000);

        clock.advance(Duration.ofSeconds(61));

        assertThat(queueService.me("user-a").status()).isEqualTo(QueueStatus.EXPIRED);
    }

    @Test
    void me_claim_유예가_지난_eligible_사용자는_EXPIRED다() {
        redisTemplate.opsForZSet().add(QueueKeys.ELIGIBLE, "user-e", clock.millis() + 30_000);

        clock.advance(Duration.ofSeconds(31));

        assertThat(queueService.me("user-e").status()).isEqualTo(QueueStatus.EXPIRED);
    }
}
