/**
 * 用户自选股接口
 */
import request from '@/utils/request'
import type { StockInfo } from '@/types'
import { useUserStore } from '@/stores/user'

const getUserId = () => {
  const store = useUserStore()
  return store.userId || Number(localStorage.getItem('userId') || '0')
}

/** 获取当前用户自选股 */
export const getWatchlist = () => {
  const userId = getUserId()
  return request.get<any, { code: number; data: StockInfo[] }>('/auth/watchlist', {
    params: { userId }
  })
}

/** 添加自选 */
export const addToWatchlist = (stock: { stockCode: string; stockName: string }) => {
  const userId = getUserId()
  return request.post<any, any>('/auth/watchlist', { ...stock, userId })
}

/** 删除自选 */
export const removeFromWatchlist = (stockCode: string) => {
  const userId = getUserId()
  return request.delete<any, any>(`/auth/watchlist/${stockCode}?userId=${userId}`)
}
