import { useQueue } from './useQueue'
import Home from './screens/Home'
import Waiting from './screens/Waiting'
import Reservation from './screens/Reservation'
import Result from './screens/Result'
import './App.css'

// phase 하나로 화면을 갈아끼운다. 로직은 전부 useQueue 안에 있고,
// 화면 컴포넌트는 값을 받아 그리기만 한다 (로직 0).
export default function App() {
  const q = useQueue()

  return (
    <div className="app">
      <h1>🎫 예매 대기열</h1>
      {q.phase === 'IDLE' && <Home onJoin={q.join} />}
      {(q.phase === 'WAITING' || q.phase === 'ELIGIBLE') && (
        <Waiting phase={q.phase} ahead={q.ahead} />
      )}
      {q.phase === 'ACTIVE' && (
        <Reservation leaseRemaining={q.leaseRemaining} onReserve={q.reserve} />
      )}
      {(q.phase === 'DONE' || q.phase === 'EXPIRED' || q.phase === 'NOT_FOUND') && (
        <Result phase={q.phase} reservationId={q.reservationId} onReset={q.reset} />
      )}
    </div>
  )
}
