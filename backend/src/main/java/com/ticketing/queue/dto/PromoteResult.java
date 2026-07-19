package com.ticketing.queue.dto;

import java.util.List;

/**
 * 승격 실행 결과. 스케줄러가 이 명단으로 SSE 통지를 보낸다.
 *
 * @param expired  lease 만료로 퇴장 처리된 토큰들
 * @param promoted 이번에 eligible로 승격된 토큰들 (FIFO 순서)
 */
public record PromoteResult(List<String> expired, List<String> promoted) {

    public boolean hasChanges() {
        return !expired.isEmpty() || !promoted.isEmpty();
    }
}
