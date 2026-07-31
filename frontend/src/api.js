// 백엔드 REST 호출 래퍼. 모든 경로는 /api/* → Vite 프록시가 :8080으로 전달.
// 응답은 항상 200 + status enum 설계라, 여기서는 JSON만 파싱해 넘긴다.

async function postJson(url, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: body ? { 'Content-Type': 'application/json' } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`${url} → HTTP ${res.status}`)
  return res.json()
}

export function join() {
  return postJson('/api/queue/join')
}

export async function me(token) {
  const res = await fetch(`/api/queue/me?token=${encodeURIComponent(token)}`)
  if (!res.ok) throw new Error(`/api/queue/me → HTTP ${res.status}`)
  return res.json()
}

export function claim(token) {
  return postJson('/api/queue/claim', { token })
}

export function reserve(token) {
  return postJson('/api/reservations', { token })
}
