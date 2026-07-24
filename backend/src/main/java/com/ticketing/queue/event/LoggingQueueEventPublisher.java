package com.ticketing.queue.event;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 임시 구현체 — 로그만 남긴다. Task 9에서 SSE 구현체로 교체 예정.
 */
@Component
public class LoggingQueueEventPublisher implements QueueEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingQueueEventPublisher.class);

    @Override
    public void admitted(List<String> tokens) {
        log.info("승격 통지 대상: {}", tokens);
    }

    @Override
    public void expired(List<String> tokens) {
        log.info("만료 통지 대상: {}", tokens);
    }
}
