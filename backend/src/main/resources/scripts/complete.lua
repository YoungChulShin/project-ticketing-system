-- [Task 5 과제] 예매 완료 (active에서 제거 = 자리 반납)
--
-- KEYS[1] = queue:active
-- ARGV[1] = 사용자 토큰
-- ARGV[2] = 현재 시각 (epoch ms)
--
-- 반환: 'COMPLETED' | 'NOT_ACTIVE' | 'EXPIRED'
-- 검증: ./gradlew test --tests 'com.ticketing.queue.QueueCompleteTest'
--
-- [구현 단계]
-- 1. active에서 ZSCORE로 lease 만료 시각 조회
--    - 없으면 'NOT_ACTIVE' 반환 (입장한 적 없거나 이미 퇴장 처리됨)
-- 2. 만료 시각 <= 현재 시각이면:
--    - ZREM으로 제거 (lazy cleanup — 스케줄러보다 먼저 발견한 만료)
--    - 'EXPIRED' 반환
-- 3. 유효하면:
--    - ZREM으로 제거 (자리 반납 — 다음 promote 주기에 대기자가 승격됨)
--    - 'COMPLETED' 반환
--
-- ※ tonumber 변환 잊지 말 것 — ARGV와 ZSCORE 반환값은 문자열

local leaseTime = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not leaseTime then
  return 'NOT_ACTIVE'
end

if tonumber(leaseTime) <= tonumber(ARGV[2]) then
  redis.call('ZREM', KEYS[1], ARGV[1])
  return 'EXPIRED'
end

redis.call('ZREM', KEYS[1], ARGV[1])
return 'COMPLETED'
