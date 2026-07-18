-- 입장 확정 (eligible → active 이동, lease 시작)
--
-- KEYS[1] = queue:eligible
-- KEYS[2] = queue:active
-- ARGV[1] = 사용자 토큰
-- ARGV[2] = 현재 시각 (epoch ms)
-- ARGV[3] = lease 만료 시각 (epoch ms)
--
-- 반환: 'CLAIMED' | 'NOT_ELIGIBLE' | 'EXPIRED'
-- 스크립트 전체가 하나의 원자 단위 — 확인과 이동 사이에 끼어들기 불가

local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
if not score then
  return 'NOT_ELIGIBLE'
end

-- 자격 시각 초과: 스케줄러보다 먼저 발견했으면 즉시 정리 (lazy cleanup)
if tonumber(score) <= tonumber(ARGV[2]) then
  redis.call('ZREM', KEYS[1], ARGV[1])
  return 'EXPIRED'
end

redis.call('ZREM', KEYS[1], ARGV[1])
redis.call('ZADD', KEYS[2], ARGV[3], ARGV[1])
return 'CLAIMED'
