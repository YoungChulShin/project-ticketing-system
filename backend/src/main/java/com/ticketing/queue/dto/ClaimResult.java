package com.ticketing.queue.dto;

/**
 * 입장 확정(claim) 결과.
 *
 * @param status        처리 결과
 * @param leaseDeadline CLAIMED일 때 lease 만료 시각 (epoch ms). 그 외 null
 */
public record ClaimResult(Status status, Long leaseDeadline) {

    public enum Status {
        /** 입장 확정 — 이 순간부터 lease 시작 */
        CLAIMED,
        /** 자격 없음 — eligible에 없는 토큰 */
        NOT_ELIGIBLE,
        /** 자격 시각 초과 — 노쇼 처리됨 */
        EXPIRED
    }

    public static ClaimResult claimed(long leaseDeadline) {
        return new ClaimResult(Status.CLAIMED, leaseDeadline);
    }

    public static ClaimResult notEligible() {
        return new ClaimResult(Status.NOT_ELIGIBLE, null);
    }

    public static ClaimResult expired() {
        return new ClaimResult(Status.EXPIRED, null);
    }
}
