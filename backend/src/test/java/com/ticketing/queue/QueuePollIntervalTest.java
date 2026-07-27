package com.ticketing.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.support.RedisTestSupport;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * [adaptive polling 과제 명세] me()가 상태·순번에 따라 nextPollAfterMs를 지시하는지 검증.
 * 실행: ./gradlew test --tests 'com.ticketing.queue.QueuePollIntervalTest'
 */
class QueuePollIntervalTest extends RedisTestSupport {

    @Autowired
    QueueService queueService;

    private List<String> joinMany(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> queueService.join().token())
                .toList();
    }

    @Test
    void 맨_앞_대기자는_2초_간격() {
        String token = queueService.join().token(); // ahead 0

        assertThat(queueService.me(token).nextPollAfterMs()).isEqualTo(2000);
    }

    @Test
    void ahead가_5이하면_2초_간격() {
        List<String> tokens = joinMany(6); // 6번째 = ahead 5

        assertThat(queueService.me(tokens.get(5)).nextPollAfterMs()).isEqualTo(2000);
    }

    @Test
    void ahead가_6에서_20이면_5초_간격() {
        List<String> tokens = joinMany(7); // 7번째 = ahead 6

        assertThat(queueService.me(tokens.get(6)).nextPollAfterMs()).isEqualTo(5000);
    }

    @Test
    void ahead가_20_초과면_10초_간격() {
        List<String> tokens = joinMany(22); // 22번째 = ahead 21

        assertThat(queueService.me(tokens.get(21)).nextPollAfterMs()).isEqualTo(10000);
    }

    @Test
    void ELIGIBLE은_1초_간격() {
        redisTemplate.opsForZSet().add(QueueKeys.ELIGIBLE, "e", clock.millis() + 30_000);

        assertThat(queueService.me("e").nextPollAfterMs()).isEqualTo(1000);
    }

    @Test
    void ACTIVE는_1초_간격() {
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "a", clock.millis() + 60_000);

        assertThat(queueService.me("a").nextPollAfterMs()).isEqualTo(1000);
    }

    @Test
    void EXPIRED는_폴링_중단_0() {
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "old", clock.millis() - 1_000);

        assertThat(queueService.me("old").nextPollAfterMs()).isZero();
    }

    @Test
    void NOT_FOUND는_폴링_중단_0() {
        assertThat(queueService.me("ghost").nextPollAfterMs()).isZero();
    }
}
