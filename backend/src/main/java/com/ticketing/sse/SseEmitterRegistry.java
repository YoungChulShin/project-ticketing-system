package com.ticketing.sse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter.SseEventBuilder;

/**
 * [Task 9 과제] 토큰 → SSE 연결 레지스트리.
 *
 * <p>서버 메모리에 남는 유일한 상태. HTTP 스레드(등록), 스케줄러 스레드(방송),
 * 콜백(제거)이 동시에 접근한다 — 그래서 ConcurrentHashMap.
 *
 * 검증: ./gradlew test --tests 'com.ticketing.sse.SseEmitterRegistryTest'
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);
    /** 5분 — 만료돼도 클라이언트 EventSource가 자동 재연결 */
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    /**
     * [구현 단계]
     * 1. new SseEmitter(TIMEOUT_MS) 생성
     * 2. Map에 등록 — 같은 토큰이 이미 있으면 새 연결로 대체 (재연결 시나리오)
     * 3. 연결이 끝나는 경로 3개 전부에 "Map에서 제거" 콜백 등록:
     *      emitter.onCompletion(...) / onTimeout(...) / onError(...)
     *    ★ 제거는 remove(token, emitter) 2-인자 버전으로 —
     *      옛 연결의 콜백이 늦게 발화했을 때, 그 사이 등록된 "새" 연결을
     *      지워버리면 안 된다 (key와 value가 둘 다 일치할 때만 제거됨)
     * 4. emitter 반환
     */
    public SseEmitter register(String token) {
        SseEmitter sseEmitter = new SseEmitter(TIMEOUT_MS);
        emitters.put(token, sseEmitter);

        sseEmitter.onCompletion(() -> {
            emitters.remove(token);
        });
        sseEmitter.onTimeout(() -> {
            emitters.remove(token);
        });
        sseEmitter.onError(error -> {
            log.error("error: {}", error.getMessage(), error);
            emitters.remove(token);
        });

        return sseEmitter;
    }

    /**
     * 특정 사용자에게 개인 이벤트 전송.
     *
     * [구현 단계]
     * 1. Map에서 조회 — 없으면 그냥 return (그 사용자는 폴링 fallback이 커버)
     * 2. emitter.send(SseEmitter.event().name(eventName).data(data))
     * 3. send가 예외를 던지면 = 죽은 연결 → Map에서 제거 (안 치우면 누수)
     */
    public void sendTo(String token, String eventName, Object data) {
        SseEmitter sseEmitter = emitters.get(token);
        if (sseEmitter == null) {
            return;
        }

      try {
        sseEmitter.send(
            SseEmitter.event()
                .name(eventName)
                .data(data));
      } catch (Exception e) {
        emitters.remove(token);
      }
    }

    /**
     * 연결된 전원에게 방송.
     *
     * [구현 단계]
     * emitters.forEach((token, emitter) -> ...) 순회하며 sendTo와 같은
     * send + 실패 시 제거 처리. (ConcurrentHashMap은 순회 중 제거 안전)
     */
    public void broadcast(String eventName, Object data) {
        emitters.forEach((token, emitter) -> {
            sendTo(token, eventName, data);
        });
    }

    /**
     * 주석(comment) 라인 전송 — 프록시/LB의 유휴 연결 종료 방지.
     *
     * [구현 단계]
     * broadcast와 동일 순회, 보내는 것만 SseEmitter.event().comment("heartbeat")
     * (comment는 클라이언트에서 이벤트로 취급되지 않음 — 순수 연결 유지용)
     */
    public void heartbeat() {
        emitters.forEach((token, emitter) -> {
          try {
            emitter.send(
                SseEmitter.event()
                    .comment("heartbeat")
            );
          } catch (Exception e) {
            emitters.remove(token);
          }
        });
    }

    public int connectionCount() {
        return emitters.size();
    }
}
