package com.ticketing.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Redis 통합 테스트 공통 지원.
 *
 * <p>Testcontainers가 테스트 실행 시 redis:7-alpine 컨테이너를 무작위 포트로 띄우고,
 * {@code @ServiceConnection}이 그 주소를 Spring Data Redis 설정에 자동 주입한다.
 * 각 테스트 메서드 전에 FLUSHALL로 데이터를 초기화해 테스트 간 독립성을 보장한다.
 */
@SpringBootTest
@Testcontainers
public abstract class RedisTestSupport {

    @Container
    @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @BeforeEach
    void flushRedis() {
        redisTemplate.execute((connection) -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }
}
