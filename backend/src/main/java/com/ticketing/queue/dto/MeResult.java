package com.ticketing.queue.dto;

import com.ticketing.queue.QueueStatus;

/**
 * 내 상태 조회 결과. 상태에 따라 무관한 필드는 null.
 *
 * @param status         현재 상태
 * @param ahead          WAITING일 때 내 앞 대기자 수 (exact, ZRANK)
 * @param dequeued       WAITING일 때 누적 승격 수 (클라이언트 근사 순번 계산의 기준점)
 * @param claimDeadline  ELIGIBLE일 때 claim 마감 시각 (epoch ms)
 * @param leaseDeadline  ACTIVE일 때 lease 만료 시각 (epoch ms)
 * @param nextPollAfterMs 클라이언트가 다음 /me 폴링까지 기다릴 시간(ms). 0이면 폴링 중단.
 *                        서버가 순번에 따라 간격을 지시 = adaptive polling
 */
public record MeResult(
        QueueStatus status,
        Long ahead,
        Long dequeued,
        Long claimDeadline,
        Long leaseDeadline,
        long nextPollAfterMs
) {

  public static MeResult expired(long nextPollAfterMs) {
    return new MeResult(
        QueueStatus.EXPIRED,
        null,
        null,
        null,
        null,
        nextPollAfterMs);
  }

  public static MeResult active(Long leaseDeadline, long nextPollAfterMs) {
    return new MeResult(
        QueueStatus.ACTIVE,
        null,
        null,
        null,
        leaseDeadline,
        nextPollAfterMs);
  }

  public static MeResult eligible(Long claimDeadline, long nextPollAfterMs) {
    return new MeResult(
        QueueStatus.ELIGIBLE,
        null,
        null,
        claimDeadline,
        null,
        nextPollAfterMs);
  }

  public static MeResult waiting(Long ahead, Long dequeued, long nextPollAfterMs) {
    return new MeResult(
        QueueStatus.WAITING,
        ahead,
        dequeued,
        null,
        null,
        nextPollAfterMs);
  }

  public static MeResult notFound(long nextPollAfterMs) {
    return new MeResult(
        QueueStatus.NOT_FOUND,
        null,
        null,
        null,
        null,
        nextPollAfterMs);
  }


}
