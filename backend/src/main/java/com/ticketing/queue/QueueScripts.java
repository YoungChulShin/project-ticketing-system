package com.ticketing.queue;

import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * classpath의 Lua 스크립트를 RedisScript 빈으로 노출한다.
 *
 * <p>RedisScript는 스크립트 본문의 SHA1을 계산해 두고, 실행 시 EVALSHA(해시)를
 * 먼저 시도한다. Redis가 모르는 해시라고 답하면 그때 한 번만 EVAL(본문 전송)로
 * 로드한다 — 이후 호출은 전부 해시만 오간다 (본문 재전송 없음).
 */
@Configuration
public class QueueScripts {

    @Bean
    public RedisScript<String> claimScript() {
        return RedisScript.of(new ClassPathResource("scripts/claim.lua"), String.class);
    }

    @Bean
    public RedisScript<String> completeScript() {
        return RedisScript.of(new ClassPathResource("scripts/complete.lua"), String.class);
    }

    @Bean
    @SuppressWarnings("rawtypes")
    public RedisScript<List> promoteScript() {
        return RedisScript.of(new ClassPathResource("scripts/promote.lua"), List.class);
    }
}
