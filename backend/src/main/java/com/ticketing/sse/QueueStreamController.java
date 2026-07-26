package com.ticketing.sse;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class QueueStreamController {

    private final SseEmitterRegistry registry;

    public QueueStreamController(SseEmitterRegistry registry) {
        this.registry = registry;
    }

    /**
     * SSE 연결 수립. SseEmitter를 반환하면 응답이 닫히지 않고 열린 채 유지된다 —
     * 이후 스케줄러/publisher가 이 연결로 이벤트를 밀어넣는다.
     */
    @GetMapping(value = "/api/queue/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String token) {
        return registry.register(token);
    }
}
