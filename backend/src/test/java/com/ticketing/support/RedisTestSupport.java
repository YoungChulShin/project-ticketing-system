package com.ticketing.support;

import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis 통합 테스트 공통 지원.
 *
 * <p>Testcontainers가 테스트 실행 시 redis:7-alpine 컨테이너를 무작위 포트로 띄우고,
 * {@code @ServiceConnection}이 그 주소를 Spring Data Redis 설정에 자동 주입한다.
 * 각 테스트 메서드 전에 FLUSHALL로 데이터를 초기화해 테스트 간 독립성을 보장한다.
 */
@SpringBootTest
@Import(RedisTestSupport.TestClockConfig.class)
public abstract class RedisTestSupport {

    /**
     * 싱글턴 컨테이너 패턴: @Container는 테스트 클래스마다 컨테이너를 재시작해
     * 캐시된 Spring 컨텍스트가 죽은 포트를 바라보게 된다.
     * static 초기화로 JVM당 1회만 시작하고 종료는 ryuk에 맡긴다.
     */
    @ServiceConnection
    static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    /** 테스트 기준 시각. 매 테스트 전 이 값으로 리셋된다. */
    protected static final Instant BASE_TIME = Instant.parse("2026-07-18T00:00:00Z");

    @Autowired
    protected StringRedisTemplate redisTemplate;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetState() {
        redisTemplate.execute((connection) -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
        clock.set(BASE_TIME);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(BASE_TIME, ZoneOffset.UTC);
        }
    }
}
