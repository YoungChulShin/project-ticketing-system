package com.ticketing.queue.event;

import com.ticketing.sse.EventType;
import com.ticketing.sse.SseEmitterRegistry;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * [Task 9 과제] 1차(단일 서버) 통지 구현 — SSE 레지스트리 직접 호출.
 * 2차 확장 때 이 클래스만 Redis Pub/Sub 구현체로 교체된다.
 *
 * [구현 단계]
 * - admitted: tokens 각각에 대해 registry.sendTo(토큰, "admission", 데이터)
 * - expired:  tokens 각각에 대해 registry.sendTo(토큰, "expired", 데이터)
 * - 데이터는 빈 Map이면 충분 — 클라이언트는 이벤트 "이름"만 보고 반응
 *   (admission 받으면 자동 claim, expired 받으면 시간초과 화면)
 * - 연결 없는 사용자는 registry가 알아서 무시 — 그 사람은 폴링(/me)으로 알게 됨
 *
 * 검증: curl 2개 터미널로 (핸드오프 메시지 참고)
 */
@Component
public class DirectQueueEventPublisher implements QueueEventPublisher {

    private final SseEmitterRegistry registry;

    public DirectQueueEventPublisher(SseEmitterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void admitted(List<String> tokens) {
        tokens.forEach(token -> {
            registry.sendTo(
                token,
                EventType.ADMISSION.toString(),
                null);
        });
    }

    @Override
    public void expired(List<String> tokens) {
        tokens.forEach(token -> {
            registry.sendTo(
                token,
                EventType.EXPIRED.toString(),
                null);
        });
    }
}
