package com.ticketing.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.support.RedisTestSupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 설계 문서 11장의 동시성 불변식 검증.
 * CountDownLatch로 모든 스레드를 준비시킨 뒤 동시에 발사한다.
 */
class QueueConcurrencyTest extends RedisTestSupport {

    @Autowired
    QueueService queueService;

    private final ExecutorService pool = Executors.newFixedThreadPool(16);

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    @Test
    void 동시_join_100명의_순번은_모두_유일하다() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = IntStream.range(0, 100)
                .mapToObj(i -> pool.submit(() -> {
                    start.await();
                    return queueService.join().sequence();
                }))
                .toList();

        start.countDown();

        Set<Long> sequences = new HashSet<>();
        for (Future<Long> future : futures) {
            sequences.add(future.get());
        }
        assertThat(sequences).hasSize(100);
        assertThat(redisTemplate.opsForZSet().zCard(QueueKeys.WAITING)).isEqualTo(100);
    }

    @Test
    void 동시_promote_claim_폭주에도_정원을_넘지_않는다() throws Exception {
        List<String> tokens = IntStream.range(0, 30)
                .mapToObj(i -> queueService.join().token())
                .toList();

        for (int round = 0; round < 10; round++) {
            CountDownLatch start = new CountDownLatch(1);
            List<Callable<Void>> jobs = new ArrayList<>();
            // promote 2개가 동시에 돎 (다중 인스턴스 스케줄러 상황 재현)
            for (int i = 0; i < 2; i++) {
                jobs.add(() -> {
                    start.await();
                    queueService.promote();
                    return null;
                });
            }
            // 전원이 동시에 claim 시도 (자격 없는 사람은 NOT_ELIGIBLE로 튕김)
            for (String token : tokens) {
                jobs.add(() -> {
                    start.await();
                    queueService.claim(token);
                    return null;
                });
            }
            List<Future<Void>> futures = jobs.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }

            // ★ 핵심 불변식: 어떤 순간에도 active + eligible(예약석) ≤ K
            Long active = redisTemplate.opsForZSet().zCard(QueueKeys.ACTIVE);
            Long eligible = redisTemplate.opsForZSet().zCard(QueueKeys.ELIGIBLE);
            assertThat(active + eligible)
                    .as("round %d: active=%d, eligible=%d", round, active, eligible)
                    .isLessThanOrEqualTo(5);

            // 자리 비우기: active 전원 예매 완료 → 다음 라운드에서 새 승격 발생
            for (String token : tokens) {
                queueService.complete(token);
            }
        }

        // 10라운드 × 최대 5명 = 30명 전원 처리 가능했어야 함
        assertThat(redisTemplate.opsForZSet().zCard(QueueKeys.WAITING)).isZero();
        assertThat(redisTemplate.opsForValue().get(QueueKeys.DEQUEUED)).isEqualTo("30");
    }
}
