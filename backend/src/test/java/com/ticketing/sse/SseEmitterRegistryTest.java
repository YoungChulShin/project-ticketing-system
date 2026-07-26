package com.ticketing.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * [Task 9 과제 명세] Spring 컨텍스트 불필요 — 순수 단위 테스트.
 * 실행: ./gradlew test --tests 'com.ticketing.sse.SseEmitterRegistryTest'
 */
class SseEmitterRegistryTest {

    private final SseEmitterRegistry registry = new SseEmitterRegistry();

    @Test
    void register하면_연결_수가_증가한다() {
        registry.register("user-a");
        registry.register("user-b");

        assertThat(registry.connectionCount()).isEqualTo(2);
    }

    @Test
    void 같은_토큰_재연결은_연결_수를_늘리지_않는다() {
        registry.register("user-a");
        registry.register("user-a");

        assertThat(registry.connectionCount()).isEqualTo(1);
    }

    @Test
    void 모르는_토큰_sendTo는_예외_없이_무시된다() {
        assertThatCode(() -> registry.sendTo("ghost", "admission", Map.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void 죽은_연결에_sendTo하면_레지스트리에서_정리된다() {
        SseEmitter emitter = registry.register("user-a");
        emitter.complete(); // 연결 종료 상황 재현 — 이후 send는 예외

        registry.sendTo("user-a", "progress", Map.of());

        assertThat(registry.connectionCount()).isZero();
    }

    @Test
    void broadcast_중_죽은_연결만_정리되고_산_연결은_남는다() {
        registry.register("alive");
        SseEmitter dead = registry.register("dead");
        dead.complete();

        registry.broadcast("progress", Map.of("dequeued", 1));

        assertThat(registry.connectionCount()).isEqualTo(1);
    }
}
