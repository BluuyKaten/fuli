/**
 * 实时行情 WebSocket
 */
import { ref, onUnmounted } from 'vue'

export interface QuoteData {
  type: string
  code: string
  price: number
  preClose: number
  bid1: number
  ask1: number
  timestamp: number
}

let socket: WebSocket | null = null
const subscribers = new Set<(quote: QuoteData) => void>()
const connected = ref(false)
const reconnecting = ref(false)

// 重连控制
let reconnectCount = 0
const MAX_RECONNECT = 5
let reconnectTimer: ReturnType<typeof setTimeout> | null = null

// WebSocket 端口：优先从环境变量读取，默认 8082（直连 trade-service）
// 生产环境走网关时改为 8080：VITE_WS_PORT=8080
const WS_PORT = import.meta.env.VITE_WS_PORT || '8082'

const connect = () => {
  if (socket && socket.readyState <= WebSocket.OPEN) return

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const wsUrl = `${protocol}//${window.location.hostname}:${WS_PORT}/ws/quotes`

  socket = new WebSocket(wsUrl)

  socket.onopen = () => {
    connected.value = true
    reconnecting.value = false
    reconnectCount = 0 // 重置重连计数
    console.log('[WebSocket] 实时行情已连接')
  }

  socket.onmessage = (event) => {
    try {
      const data: QuoteData = JSON.parse(event.data)
      subscribers.forEach(cb => cb(data))
    } catch (e) {
      console.warn('[WebSocket] 解析失败', e)
    }
  }

  socket.onclose = () => {
    connected.value = false
    console.log('[WebSocket] 实时行情已断开')
    // 限流重连：最多 5 次，每次间隔递增
    if (reconnectCount < MAX_RECONNECT) {
      reconnectCount++
      reconnecting.value = true
      const delay = Math.min(3000 * reconnectCount, 15000) // 最长 15 秒
      console.log(`[WebSocket] ${delay / 1000}秒后重连 (${reconnectCount}/${MAX_RECONNECT})`)
      reconnectTimer = setTimeout(() => connect(), delay)
    } else {
      reconnecting.value = false
      console.warn('[WebSocket] 已达到最大重连次数，停止重连')
    }
  }

  socket.onerror = (e) => {
    console.error('[WebSocket] 错误', e)
  }
}

// 手动重连入口：重置重连计数并立即尝试连接
export const reconnect = () => {
  if (reconnectTimer) clearTimeout(reconnectTimer)
  reconnectCount = 0
  if (socket) {
    socket.onclose = null // 避免触发自动重连逻辑
    socket.close()
    socket = null
  }
  connect()
}

export function useRealtimeQuote() {
  const onQuote = (callback: (quote: QuoteData) => void) => {
    subscribers.add(callback)
    connect()
    return () => {
      subscribers.delete(callback)
    }
  }

  onUnmounted(() => {
    // 最后一个订阅者断开时关闭连接
    if (subscribers.size === 0 && socket) {
      if (reconnectTimer) clearTimeout(reconnectTimer)
      socket.close()
      socket = null
    }
  })

  return {
    onQuote,
    connected,
    reconnecting,
    reconnect
  }
}
