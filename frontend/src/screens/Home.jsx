export default function Home({ onJoin }) {
  return (
    <div className="card">
      <h2>2026 여름 콘서트</h2>
      <p className="muted">동시 입장 3명 · 입장 후 60초 내 예매</p>
      <button className="primary" onClick={onJoin}>예매 시작</button>
    </div>
  )
}
