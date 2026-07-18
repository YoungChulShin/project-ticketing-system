package com.ticketing.queue;

/**
 * 대기열 안에서 사용자의 현재 상태.
 */
public enum QueueStatus {
    /** 대기줄에 있음 */
    WAITING,
    /** 입장 자격 획득, claim 대기 중 */
    ELIGIBLE,
    /** 예매 진행 중 (lease 보유) */
    ACTIVE,
    /** 자격 또는 lease 만료 */
    EXPIRED,
    /** 어떤 상태에도 없음 (미참여 또는 이미 제거됨) */
    NOT_FOUND
}
