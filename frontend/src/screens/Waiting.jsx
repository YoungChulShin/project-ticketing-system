export default function Waiting({ phase, ahead }) {
  const entering = phase === 'ELIGIBLE'
  return (
    <div className="card">
      {entering ? (
        <>
          <h2>입장 중…</h2>
          <p className="muted">차례가 되었습니다. 예매 화면으로 이동합니다.</p>
        </>
      ) : (
        <>
          <p className="muted">내 앞 대기 인원</p>
          <div className="bignum">{ahead ?? '—'}</div>
          <p className="muted">잠시만 기다려 주세요…</p>
        </>
      )}
    </div>
  )
}
