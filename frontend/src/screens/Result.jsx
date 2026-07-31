export default function Result({ phase, reservationId, onReset }) {
  const done = phase === 'DONE'
  return (
    <div className="card">
      {done ? (
        <>
          <h2>✅ 예매 완료</h2>
          <p className="muted">예매 번호</p>
          <div className="code">{reservationId}</div>
        </>
      ) : (
        <>
          <h2>⏱️ {phase === 'EXPIRED' ? '시간 초과' : '세션 종료'}</h2>
          <p className="muted">
            {phase === 'EXPIRED'
              ? '제한 시간 안에 예매하지 못했습니다.'
              : '대기열에서 나갔습니다.'}
          </p>
        </>
      )}
      <button className="primary" onClick={onReset}>다시 줄서기</button>
    </div>
  )
}
