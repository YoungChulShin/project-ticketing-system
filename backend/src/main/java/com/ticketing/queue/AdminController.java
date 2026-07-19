package com.ticketing.queue;

import java.util.List;
import java.util.Set;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학습 관찰용 — 대기열 내부 상태를 통째로 보여준다.
 * 프로덕션이라면 인증 + 페이징 필수인 엔드포인트.
 */
@RestController
public class AdminController {

    private final StringRedisTemplate redisTemplate;

    public AdminController(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public record Entry(String token, long score) {
    }

    public record QueueView(
            List<Entry> waiting,
            List<Entry> eligible,
            List<Entry> active,
            long seq,
            long dequeued
    ) {
    }

    @GetMapping("/api/admin/queue")
    public QueueView queue() {
        return new QueueView(
                entries(QueueKeys.WAITING),
                entries(QueueKeys.ELIGIBLE),
                entries(QueueKeys.ACTIVE),
                counter(QueueKeys.SEQ),
                counter(QueueKeys.DEQUEUED));
    }

    private List<Entry> entries(String key) {
        Set<TypedTuple<String>> tuples = redisTemplate.opsForZSet().rangeWithScores(key, 0, -1);
        if (tuples == null) {
            return List.of();
        }
        return tuples.stream()
                .map(tuple -> new Entry(tuple.getValue(), tuple.getScore().longValue()))
                .toList();
    }

    private long counter(String key) {
        String value = redisTemplate.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }
}
