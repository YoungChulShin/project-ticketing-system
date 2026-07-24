package com.ticketing.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.queue.dto.JoinResult;
import com.ticketing.queue.event.QueueEventPublisher;
import com.ticketing.support.RedisTestSupport;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * 스케줄러 배선 검증 — 주기 실행 자체는 스프링 몫이므로 tick()을 직접 호출해
 * promote 결과가 publisher로 흘러가는지 확인한다.
 */
@Import(PromoteSchedulerTest.RecordingPublisherConfig.class)
class PromoteSchedulerTest extends RedisTestSupport {

    @Autowired
    PromoteScheduler scheduler;

    @Autowired
    QueueService queueService;

    @Autowired
    RecordingPublisher recording;

    @Test
    void tick은_승격자와_만료자를_publisher에_전달한다() {
        recording.reset();
        redisTemplate.opsForZSet().add(QueueKeys.ACTIVE, "old-user", clock.millis() + 60_000);
        JoinResult waiter = queueService.join();

        clock.advance(Duration.ofSeconds(61));
        scheduler.tick();

        assertThat(recording.admitted).containsExactly(waiter.token());
        assertThat(recording.expired).containsExactly("old-user");
    }

    @Test
    void 변화가_없으면_publisher를_호출하지_않는다() {
        recording.reset();

        scheduler.tick();

        assertThat(recording.admitted).isEmpty();
        assertThat(recording.expired).isEmpty();
    }

    static class RecordingPublisher implements QueueEventPublisher {
        final List<String> admitted = new ArrayList<>();
        final List<String> expired = new ArrayList<>();

        void reset() {
            admitted.clear();
            expired.clear();
        }

        @Override
        public void admitted(List<String> tokens) {
            admitted.addAll(tokens);
        }

        @Override
        public void expired(List<String> tokens) {
            expired.addAll(tokens);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingPublisherConfig {

        @Bean
        @Primary
        RecordingPublisher recordingPublisher() {
            return new RecordingPublisher();
        }
    }
}
