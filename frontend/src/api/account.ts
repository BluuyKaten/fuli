import request from '@/utils/request'

// 内部接口密钥(需要与后端 application-local.yml 中的 fuli.internal-key 一致)
const INTERNAL_KEY = 'dev-only-internal-key-0123456789'

export const resetCash = (userId: number, newCash: number) =>
  request.put<any, { code: number; data: boolean }>('/auth/internal/resetCash', null, {
    params: { userId, newCash },
    headers: { 'X-Internal-Key': INTERNAL_KEY }
  })

export const clearAllTrades = (userId: number) =>
  request.delete<any, { code: number; data: boolean }>('/trade/internal/clearAll', {
    params: { userId },
    headers: { 'X-Internal-Key': INTERNAL_KEY }
  })
