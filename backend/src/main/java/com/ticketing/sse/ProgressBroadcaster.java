package com.ticketing.sse;

import com.ticketing.queue.QueueKeys;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 진행 상황 방송. 개인별 순번을 계산하지 않는다 — 전원에게 같은 숫자(dequeued 등)를
 * 보내고, 각 클라이언트가 "내 순번 − dequeued"로 근사 위치를 스스로 계산한다.
 * 대기자 수와 무관하게 방송 비용은 초당 1건 × 연결 수.
 */
@Component
public class ProgressBroadcaster {

    private final SseEmitterRegistry registry;
    private final StringRedisTemplate redisTemplate;

    public ProgressBroadcaster(SseEmitterRegistry registry, StringRedisTemplate redisTemplate) {
        this.registry = registry;
        this.redisTemplate = redisTemplate;
    }

    @Scheduled(fixedDelayString = "${queue.progress-interval-ms}")
    public void broadcastProgress() {
        if (registry.connectionCount() == 0) {
            return; // 아무도 안 듣는데 Redis 조회할 이유 없음
        }
        registry.broadcast("progress", Map.of(
                "dequeued", counter(QueueKeys.DEQUEUED),
                "waiting", zcard(QueueKeys.WAITING),
                "active", zcard(QueueKeys.ACTIVE)));
    }

    @Scheduled(fixedDelayString = "${queue.sse-heartbeat-seconds}", timeUnit = TimeUnit.SECONDS)
    public void heartbeat() {
        registry.heartbeat();
    }

    private long counter(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }

    private long zcard(String key) {
        Long count = redisTemplate.opsForZSet().zCard(key);
        return count == null ? 0L : count;
    }
}
