package com.ticketing.queue.event;

import java.util.List;

/**
 * 승격/만료 통지의 이음새(seam).
 *
 * <p>promote 결과를 "누구에게 어떻게 알릴지"에서 분리한다. 1차(단일 서버)는
 * SSE 레지스트리 직접 호출, 2차(다중 인스턴스)는 Redis Pub/Sub 구현체로 교체 —
 * 이 인터페이스 뒤의 구현만 바뀌고 스케줄러는 무수정.
 */
public interface QueueEventPublisher {

    /** 승격자들에게 "네 차례" 통지 */
    void admitted(List<String> tokens);

    /** lease 만료자들에게 "시간 초과" 통지 */
    void expired(List<String> tokens);
}
