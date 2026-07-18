package com.ticketing.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketing.config.QueueProperties;
import com.ticketing.support.RedisTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RedisInfraTest extends RedisTestSupport {

    @Autowired
    QueueProperties properties;

    @Test
    void INCR는_1부터_시작해_원자적으로_증가한다() {
        Long first = redisTemplate.opsForValue().increment(QueueKeys.SEQ);
        Long second = redisTemplate.opsForValue().increment(QueueKeys.SEQ);

        assertThat(first).isEqualTo(1L);
        assertThat(second).isEqualTo(2L);
    }

    @Test
    void 대기열_설정이_yml에서_바인딩된다() {
        assertThat(properties.capacity()).isEqualTo(5);
        assertThat(properties.leaseSeconds()).isEqualTo(60);
        assertThat(properties.eligibleClaimSeconds()).isEqualTo(30);
    }
}
