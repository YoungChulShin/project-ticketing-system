package com.ticketing.queue.dto;

/**
 * 예매 완료 결과.
 *
 * @param status        처리 결과
 * @param reservationId COMPLETED일 때 발급된 예매 번호. 그 외 null
 */
public record CompleteResult(Status status, String reservationId) {

    public enum Status {
        /** 예매 완료 — 자리 반납됨 */
        COMPLETED,
        /** 예매 진행 중이 아님 — active에 없는 토큰 */
        NOT_ACTIVE,
        /** lease 만료 — 시간 초과로 퇴장 처리됨 */
        EXPIRED
    }

    public static CompleteResult completed(String reservationId) {
        return new CompleteResult(Status.COMPLETED, reservationId);
    }

    public static CompleteResult notActive() {
        return new CompleteResult(Status.NOT_ACTIVE, null);
    }

    public static CompleteResult expired() {
        return new CompleteResult(Status.EXPIRED, null);
    }
}
