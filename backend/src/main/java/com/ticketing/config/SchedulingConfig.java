package com.ticketing.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 활성화. 테스트에서는 queue.scheduling.enabled=false로 꺼서
 * 백그라운드 promote가 테스트 시나리오를 오염시키지 않게 한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "queue.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
