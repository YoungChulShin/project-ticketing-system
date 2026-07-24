package com.ticketing.queue;

import com.ticketing.queue.dto.PromoteResult;
import com.ticketing.queue.event.QueueEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 승격 엔진의 심장 박동. promote-interval-ms(기본 500ms)마다 promote를 실행한다.
 *
 * <p>다중 인스턴스가 각자 이 스케줄러를 돌려도 안전 — promote.lua가 원자적이라
 * 한쪽이 승격하면 다른 쪽은 빈자리 0을 본다 (QueueConcurrencyTest로 검증됨).
 */
@Component
public class PromoteScheduler {

    private final QueueService queueService;
    private final QueueEventPublisher eventPublisher;

    public PromoteScheduler(QueueService queueService, QueueEventPublisher eventPublisher) {
        this.queueService = queueService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${queue.promote-interval-ms}")
    public void tick() {
        PromoteResult result = queueService.promote();
        if (!result.promoted().isEmpty()) {
            eventPublisher.admitted(result.promoted());
        }
        if (!result.expired().isEmpty()) {
            eventPublisher.expired(result.expired());
        }
    }
}
