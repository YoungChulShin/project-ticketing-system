-- 승격 엔진 — 만료자 퇴장 + 노쇼 정리 + 빈자리만큼 승격
--
-- KEYS[1] = queue:waiting
-- KEYS[2] = queue:eligible
-- KEYS[3] = queue:active
-- KEYS[4] = queue:dequeued
-- ARGV[1] = 현재 시각 (epoch ms)
-- ARGV[2] = K (동시 예매 정원)
-- ARGV[3] = 승격자의 claim 마감 시각 (epoch ms)
--
-- 반환: { 만료자 토큰 배열, 승격자 토큰 배열 }
-- 스크립트 전체가 원자 단위 — 빈자리 계산과 승격 사이에 끼어들기 불가.
-- 다중 인스턴스가 동시에 호출해도 안전한 이유가 이것.

-- 만료자 명단은 삭제 전에 확보해야 한다 (지우면 누구였는지 소실)
local expiredUsers = redis.call('ZRANGE', KEYS[3], 0, ARGV[1], 'BYSCORE')
redis.call('ZREMRANGEBYSCORE', KEYS[3], 0, ARGV[1])

-- 노쇼 제거. 명단은 반환 안 함 — 만료 통지는 lease 만료자에게만
redis.call('ZREMRANGEBYSCORE', KEYS[2], 0, ARGV[1])

-- eligible은 곧 active가 될 예약석이므로 정원 계산에 포함
local activeCount = redis.call('ZCARD', KEYS[3])
local eligibleCount = redis.call('ZCARD', KEYS[2])
-- free: 승격 가능한 사람 수
local free = tonumber(ARGV[2]) - activeCount - eligibleCount

local promoted = {}
if free > 0 then
  -- ZPOPMIN 반환은 {member1, score1, member2, score2, ...} 평평한 배열
  -- 사람 수 만큼 대기열에서 가져오기
  local popped = redis.call('ZPOPMIN', KEYS[1], free)
  for i = 1, #popped, 2 do
    local token = popped[i]
    redis.call('ZADD', KEYS[2], ARGV[3], token)
    -- promoted 라는 배열에 token을 추가
    table.insert(promoted, token)
  end
  if #promoted > 0 then
    redis.call('INCRBY', KEYS[4], #promoted)
  end
end

return { expiredUsers, promoted }
