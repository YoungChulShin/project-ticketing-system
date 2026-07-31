import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버(:5173)로 들어온 /api 요청을 백엔드(:8080)로 프록시.
// 덕분에 프론트 코드는 그냥 '/api/...'로 호출 → CORS 설정 불필요.
// SSE(text/event-stream)도 이 프록시를 통과해 스트리밍된다.
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
