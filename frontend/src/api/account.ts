import request from '@/utils/request'

export const resetCash = (userId: number, newCash: number) =>
  request.put<any, { code: number; data: boolean }>('/auth/internal/resetCash', null, { params: { userId, newCash } })

export const clearAllTrades = (userId: number) =>
  request.delete<any, { code: number; data: boolean }>('/trade/internal/clearAll', { params: { userId } })
