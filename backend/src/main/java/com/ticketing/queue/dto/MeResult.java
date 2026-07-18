package com.ticketing.queue.dto;

import com.ticketing.queue.QueueStatus;

/**
 * 내 상태 조회 결과. 상태에 따라 무관한 필드는 null.
 *
 * @param status        현재 상태
 * @param ahead         WAITING일 때 내 앞 대기자 수 (exact, ZRANK)
 * @param dequeued      WAITING일 때 누적 승격 수 (클라이언트 근사 순번 계산의 기준점)
 * @param claimDeadline ELIGIBLE일 때 claim 마감 시각 (epoch ms)
 * @param leaseDeadline ACTIVE일 때 lease 만료 시각 (epoch ms)
 */
public record MeResult(
        QueueStatus status,
        Long ahead,
        Long dequeued,
        Long claimDeadline,
        Long leaseDeadline
) {

  public static MeResult expired() {
    return new MeResult(
        QueueStatus.EXPIRED,
        null,
        null,
        null,
        null);
  }

  public static MeResult active(Long leaseDeadline) {
    return new MeResult(
        QueueStatus.ACTIVE,
        null,
        null,
        null,
        leaseDeadline);
  }

  public static MeResult eligible(Long claimDeadline) {
    return new MeResult(
        QueueStatus.ELIGIBLE,
        null,
        null,
        claimDeadline,
        null);
  }

  public static MeResult waiting(Long ahead, Long dequeued) {
    return new MeResult(
        QueueStatus.WAITING,
        ahead,
        dequeued,
        null,
        null);
  }

  public static MeResult notFound() {
    return new MeResult(
        QueueStatus.NOT_FOUND,
        null,
        null,
        null,
        null);
  }


}
