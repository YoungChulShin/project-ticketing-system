package com.ticketing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 대기열 동작 설정. application.yml의 queue.* 값이 바인딩된다.
 *
 * @param capacity             동시에 예매를 진행할 수 있는 최대 인원 (K). active + eligible 합이 이 값을 넘지 않는다
 * @param leaseSeconds         입장(claim) 후 예매를 완료해야 하는 제한 시간(초). 초과 시 퇴장 처리
 * @param eligibleClaimSeconds 승격 후 입장을 확정(claim)해야 하는 유예 시간(초). 초과 시 노쇼로 간주하고 대기열에서 제거
 * @param promoteIntervalMs    승격 스케줄러 실행 주기(ms). 만료자 정리와 빈자리 승격을 이 주기로 수행
 * @param progressIntervalMs   SSE progress 이벤트 broadcast 주기(ms). 대기자 전원에게 진행 상황 전송
 * @param sseHeartbeatSeconds  SSE heartbeat 전송 주기(초). 프록시/LB가 유휴 연결을 끊지 않도록 유지
 */
@ConfigurationProperties(prefix = "queue")
public record QueueProperties(
        int capacity,
        long leaseSeconds,
        long eligibleClaimSeconds,
        long promoteIntervalMs,
        long progressIntervalMs,
        long sseHeartbeatSeconds
) {
}
