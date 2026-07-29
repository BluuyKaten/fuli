import request from '@/utils/request'

export const searchStocks = (keyword: string) =>
  request.get<any, { code: number; data: any[] }>('/stock/search', { params: { keyword } })

export const getStockInfo = (stockCode: string) =>
  request.get<any, { code: number; data: any }>('/stock/info', { params: { stockCode } })

export const getStockDaily = (stockCode: string, startDate?: string, endDate?: string) =>
  request.get<any, { code: number; data: any[] }>('/stock/daily', { params: { stockCode, startDate, endDate } })
