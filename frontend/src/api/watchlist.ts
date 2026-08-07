/**
 * 用户自选股接口
 */
import request from '@/utils/request'
import type { StockInfo } from '@/types'

/** 获取当前用户自选股 */
export const getWatchlist = () => {
  const userId = Number(localStorage.getItem('userId') || '0')
  return request.get<any, { code: number; data: StockInfo[] }>('/auth/watchlist', {
    params: { userId }
  })
}

/** 添加自选 */
export const addToWatchlist = (stock: { stockCode: string; stockName: string }) => {
  const userId = Number(localStorage.getItem('userId') || '0')
  return request.post<any, any>('/auth/watchlist', { ...stock, userId })
}

/** 删除自选 */
export const removeFromWatchlist = (stockCode: string) =>
  request.delete<any, any>(`/auth/watchlist/${stockCode}`)
