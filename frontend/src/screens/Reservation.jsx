import { useState } from 'react'

export default function Reservation({ leaseRemaining, onReserve }) {
  const [submitting, setSubmitting] = useState(false)
  const urgent = leaseRemaining != null && leaseRemaining <= 10

  const handle = async () => {
    setSubmitting(true)
    await onReserve()
  }

  return (
    <div className="card">
      <h2>🎉 입장 완료</h2>
      <p className="muted">제한 시간 안에 예매를 완료하세요</p>
      <div className={`countdown ${urgent ? 'urgent' : ''}`}>{leaseRemaining ?? '—'}초</div>
      <button className="primary" onClick={handle} disabled={submitting}>
        {submitting ? '처리 중…' : '예매하기'}
      </button>
    </div>
  )
}
