import request from '@/utils/request'

export const searchStocks = (keyword: string) =>
  request.get<any, { code: number; data: any[] }>('/stock/search', { params: { keyword } })

export const getStockInfo = (stockCode: string) =>
  request.get<any, { code: number; data: any }>('/stock/info', { params: { stockCode } })

export const getStockDaily = (stockCode: string, startDate?: string, endDate?: string) =>
  request.get<any, { code: number; data: any[] }>('/stock/daily', { params: { stockCode, startDate, endDate } })

export const syncStockBasic = () =>
  request.post<any, { code: number; data: number }>('/data/tushare/sync/stock-basic')

export const syncDailyByDate = (tradeDate: string) =>
  request.post<any, { code: number; data: number }>('/data/tushare/sync/daily-by-date', { tradeDate })

export const syncDailyByRange = (tsCode: string, startDate?: string, endDate?: string) =>
  request.post<any, { code: number; data: number }>('/data/tushare/sync/daily', { tsCode, startDate, endDate }, { timeout: 600000 })

export const syncAllIncremental = (startDate?: string, endDate?: string) =>
  request.post<any, { code: number; data: number }>('/data/tushare/sync/all-incremental', { startDate, endDate }, { timeout: 600000 })

export const getSyncStatus = (tsCode: string) =>
  request.get<any, { code: number; data: any }>('/data/tushare/sync/status', { params: { tsCode } })
