import { useCallback, useEffect, useRef, useState } from 'react'
import * as api from './api'

const TOKEN_KEY = 'queue-token'

// 종료 상태 — 여기 도달하면 폴링을 멈춘다.
const TERMINAL = new Set(['DONE', 'EXPIRED', 'NOT_FOUND'])

/**
 * 대기열 클라이언트 두뇌 — adaptive polling 단독.
 *
 * 대규모 대기열 상용 표준(Queue-it, NetFunnel)이 쓰는 방식. 서버가 응답의
 * nextPollAfterMs로 다음 폴링 간격을 지시하고(먼 순번은 길게, 임박은 짧게),
 * 클라이언트는 그 값에 ±20% jitter를 얹어 재요청한다. 무상태라 서버 확장이 자유롭고
 * 연결 유지 비용이 없다.
 *
 * (서버에는 SSE 엔드포인트도 있으나 여기서는 쓰지 않는다 — 폴링 vs SSE 비교 실험 여지로 남겨둠.)
 *
 * token은 state와 ref를 함께 둔다: state는 폴링 엔진 effect를 (재)시작시키고,
 * ref는 콜백들이 최신 token을 클로저 없이 읽게 해 stale closure를 차단한다.
 */
export function useQueue() {
  const [phase, setPhase] = useState('IDLE')
  const [ahead, setAhead] = useState(null)
  const [leaseDeadline, setLeaseDeadline] = useState(null)
  const [leaseRemaining, setLeaseRemaining] = useState(null)
  const [reservationId, setReservationId] = useState(null)
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY))

  const tokenRef = useRef(token)
  const pollTimerRef = useRef(null)
  const claimingRef = useRef(false)

  useEffect(() => { tokenRef.current = token }, [token])

  // 입장 확정. 통지 없이 폴링이 ELIGIBLE을 발견하면 호출. guard로 1회만.
  const doClaim = useCallback(async () => {
    const t = tokenRef.current
    if (!t || claimingRef.current) return
    claimingRef.current = true
    try {
      const res = await api.claim(t)
      if (res.status === 'CLAIMED') {
        setPhase('ACTIVE')
        setLeaseDeadline(res.leaseDeadline)
      } else {
        claimingRef.current = false // NOT_ELIGIBLE/EXPIRED — 다음 폴링이 정정
      }
    } catch {
      claimingRef.current = false
    }
  }, [])

  // /me 응답을 상태에 반영. ELIGIBLE이면 자동 claim.
  const apply = useCallback((data) => {
    switch (data.status) {
      case 'WAITING':
        setPhase('WAITING')
        setAhead(data.ahead) // 폴링마다 exact ahead로 갱신
        break
      case 'ELIGIBLE':
        setPhase('ELIGIBLE')
        void doClaim()
        break
      case 'ACTIVE':
        setPhase('ACTIVE')
        setLeaseDeadline(data.leaseDeadline)
        break
      case 'EXPIRED':
        setPhase('EXPIRED')
        break
      case 'NOT_FOUND':
        localStorage.removeItem(TOKEN_KEY)
        setPhase('NOT_FOUND')
        break
      default:
        break
    }
  }, [doClaim])

  // 대기열 진입.
  const join = useCallback(async () => {
    const res = await api.join()
    localStorage.setItem(TOKEN_KEY, res.token)
    tokenRef.current = res.token
    setAhead(res.ahead)
    setPhase('WAITING')
    setToken(res.token) // 폴링 엔진 시작
  }, [])

  // 예매 완료.
  const reserve = useCallback(async () => {
    const res = await api.reserve(tokenRef.current)
    if (res.status === 'COMPLETED') {
      setPhase('DONE')
      setReservationId(res.reservationId)
    } else {
      setPhase('EXPIRED') // NOT_ACTIVE/EXPIRED
    }
  }, [])

  // 처음부터 다시.
  const reset = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY)
    claimingRef.current = false
    setAhead(null)
    setLeaseDeadline(null)
    setLeaseRemaining(null)
    setReservationId(null)
    setPhase('IDLE')
    setToken(null)
  }, [])

  // ── 폴링 엔진: 토큰이 있으면 adaptive 간격으로 /me 반복 조회 ──
  useEffect(() => {
    if (!token) return
    let stopped = false

    const poll = async () => {
      if (stopped) return
      let data
      try {
        data = await api.me(token)
      } catch {
        pollTimerRef.current = setTimeout(poll, 3000) // 일시 오류 → 짧게 재시도
        return
      }
      apply(data)
      // 서버가 지시한 간격(nextPollAfterMs)에 ±20% jitter. 0이거나 종료면 중단.
      if (!stopped && !TERMINAL.has(data.status) && data.nextPollAfterMs > 0) {
        const jitter = data.nextPollAfterMs * (0.8 + Math.random() * 0.4)
        pollTimerRef.current = setTimeout(poll, jitter)
      }
    }

    void poll() // 즉시 1회

    return () => {
      stopped = true
      clearTimeout(pollTimerRef.current)
    }
  }, [token, apply])

  // ACTIVE 카운트다운: leaseDeadline까지 남은 초
  useEffect(() => {
    if (phase !== 'ACTIVE' || !leaseDeadline) return
    const tick = () => setLeaseRemaining(Math.max(0, Math.ceil((leaseDeadline - Date.now()) / 1000)))
    tick()
    const id = setInterval(tick, 250)
    return () => clearInterval(id)
  }, [phase, leaseDeadline])

  return { phase, ahead, leaseRemaining, reservationId, join, reserve, reset }
}
