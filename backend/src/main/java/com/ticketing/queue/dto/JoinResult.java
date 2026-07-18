package com.ticketing.queue.dto;

/**
 * 대기열 진입 결과.
 *
 * @param token    발급된 사용자 토큰 (UUID). 이후 모든 요청의 식별자
 * @param sequence 진입 순번 (전역 단조증가)
 * @param ahead    진입 시점 기준 내 앞 대기자 수
 */
public record JoinResult(String token, long sequence, long ahead) {
}
