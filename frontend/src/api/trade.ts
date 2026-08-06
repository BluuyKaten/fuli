import request from '@/utils/request'
import type { StatisticsVO } from '@/types'

export interface TradeRecord {
  id: number
  userId: number
  stockCode: string
  stockName: string
  tradeType: number
  tradeTypeName: string
  tradePrice: number
  tradeQuantity: number
  tradeAmount: number
  commission: number
  tax: number
  totalCost: number
  profitLoss: number
  profitLossRatio: number
  tradeDate: string
  tradeTime: string
  remark: string
}

export type { TradeRecord as TradeRecordType }

export interface TradeRecord {
  id: number
  userId: number
  stockCode: string
  stockName: string
  tradeType: number
  tradeTypeName: string
  tradePrice: number
  tradeQuantity: number
  tradeAmount: number
  commission: number
  tax: number
  totalCost: number
  profitLoss: number
  profitLossRatio: number
  tradeDate: string
  tradeTime: string
  remark: string
}

export interface TradeQueryParams {
  userId?: number
  stockCode?: string
  tradeType?: number
  startDate?: string
  endDate?: string
  pageNum: number
  pageSize: number
}

export const getTradeList = (params: TradeQueryParams) =>
  request.post<any, { code: number; data: TradeRecord[] }>('/trade/list', params)

export const getTradePage = (params: TradeQueryParams) =>
  request.post<any, { code: number; data: { records: TradeRecord[]; total: number } }>('/trade/page', params)

export const createTrade = (data: Partial<TradeRecord>) =>
  request.post<any, { code: number; data: number }>('/trade', data)

export const updateTrade = (id: number, data: Partial<TradeRecord>) =>
  request.put<any, { code: number; data: boolean }>(`/trade/${id}`, data)

export const deleteTrade = (id: number) =>
  request.delete<any, { code: number; data: boolean }>(`/trade/${id}`)

export const getStatistics = (params: TradeQueryParams) =>
  request.post<any, { code: number; data: StatisticsVO }>('/trade/statistics', params)
