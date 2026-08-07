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

const connect = () => {
  if (socket && socket.readyState <= WebSocket.OPEN) return

  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const wsUrl = `${protocol}//${window.location.hostname}:8082/ws/quotes`

  socket = new WebSocket(wsUrl)

  socket.onopen = () => {
    connected.value = true
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
    // 3 秒后重连
    setTimeout(() => connect(), 3000)
  }

  socket.onerror = (e) => {
    console.error('[WebSocket] 错误', e)
  }
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
      socket.close()
      socket = null
    }
  })

  return {
    onQuote,
    connected
  }
}
