/**
 * 用户自选股接口
 * userId 由后端从认证上下文取，不再由客户端传递（防越权）。
 */
import request from '@/utils/request'
import type { StockInfo } from '@/types'

/** 获取当前用户自选股 */
export const getWatchlist = () =>
  request.get<any, { code: number; data: StockInfo[] }>('/auth/watchlist')

/** 添加自选 */
export const addToWatchlist = (stock: { stockCode: string; stockName: string }) =>
  request.post<any, any>('/auth/watchlist', stock)

/** 删除自选 */
export const removeFromWatchlist = (stockCode: string) =>
  request.delete<any, any>(`/auth/watchlist/${stockCode}`)
