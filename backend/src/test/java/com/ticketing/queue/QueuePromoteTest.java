package com.ticketing.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.queue.dto.JoinResult;
import com.ticketing.queue.dto.PromoteResult;
import com.ticketing.support.RedisTestSupport;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * [Task 6 과제 명세] promote.lua가 통과시켜야 하는 테스트.
 * 실행: ./gradlew test --tests 'com.ticketing.queue.QueuePromoteTest'
 */
class QueuePromoteTest extends RedisTestSupport {

    @Autowired
    QueueService queueService;

    @Test
    void 빈자리만큼_waiting_앞에서_FIFO_순서로_승격된다() {
        List<String> joined = IntStream.range(0, 7)
                .mapToObj(i -> queueService.join().token())
                .toList();

        PromoteResult result = queueService.promote();

        // K=5, 전부 빈자리 → 먼저 join한 5명이 join 순서 그대로
        assertThat(result.promoted()).containsExactlyElementsOf(joined.subList(0, 5));
        assertThat(result.expired()).isEmpty();
        assertThat(redisTemplate.opsForZSet().zCard(QueueKeys.ELIGIBLE)).isEqualTo(5);
        assertThat(redisTemplate.opsForZSet().zCard(QueueKeys.WAITING)).isEqualTo(2);
        // 승격자의 claim 마감 = now + 30초
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ELIGIBLE, joined.get(0)))
                .isEqualTo((double) (clock.millis() + 30_000));
        assertThat(redisTemplate.opsForValue().get(QueueKeys.DEQUEUED)).isEqualTo("5");
    }

    @Test
    void 정원은_active와_eligible을_합쳐_계산한다() {
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "a1", clock.millis() + 60_000);
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "a2", clock.millis() + 60_000);
        redisTemplate.opsForZSet().add(QueueKeys.ELIGIBLE, "e1", clock.millis() + 30_000);
        redisTemplate.opsForZSet().add(QueueKeys.ELIGIBLE, "e2", clock.millis() + 30_000);
        queueService.join();
        queueService.join();

        PromoteResult result = queueService.promote();

        // 5 - active 2 - eligible 2 = 빈자리 1
        assertThat(result.promoted()).hasSize(1);
        assertThat(redisTemplate.opsForZSet().zCard(QueueKeys.WAITING)).isEqualTo(1);
    }

    @Test
    void lease_만료자는_퇴장되고_명단으로_반환되며_빈자리가_채워진다() {
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "expired-1", clock.millis() - 1_000);
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "expired-2", clock.millis() - 2_000);
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "alive", clock.millis() + 60_000);
        JoinResult waiter = queueService.join();

        PromoteResult result = queueService.promote();

        assertThat(result.expired()).containsExactlyInAnyOrder("expired-1", "expired-2");
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ACTIVE, "expired-1")).isNull();
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ACTIVE, "alive")).isNotNull();
        // 만료 2명 퇴장 → active 1명 → 빈자리 4, 대기자 1명뿐이니 1명 승격
        assertThat(result.promoted()).containsExactly(waiter.token());
    }

    @Test
    void 노쇼는_제거되지만_만료_명단에는_없다() {
        redisTemplate.opsForZSet().add(QueueKeys.ELIGIBLE, "noshow", clock.millis() - 1_000);
        JoinResult waiter = queueService.join();

        PromoteResult result = queueService.promote();

        assertThat(result.expired()).isEmpty();
        assertThat(redisTemplate.opsForZSet().score(QueueKeys.ELIGIBLE, "noshow")).isNull();
        assertThat(result.promoted()).contains(waiter.token());
    }

    @Test
    void 빈자리가_없으면_아무도_승격되지_않는다() {
        IntStream.range(0, 5).forEach(i ->
                redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "a" + i, clock.millis() + 60_000));
        queueService.join();

        PromoteResult result = queueService.promote();

        assertThat(result.promoted()).isEmpty();
        assertThat(result.expired()).isEmpty();
        assertThat(redisTemplate.opsForZSet().zCard(QueueKeys.WAITING)).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(QueueKeys.DEQUEUED)).isNull();
    }
}
