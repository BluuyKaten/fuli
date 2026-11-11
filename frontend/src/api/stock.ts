import request from '@/utils/request'
import type { StockInfo, StockDailyData, SyncStatusVO, ApiResponse } from '@/types'

export const searchStocks = (keyword: string) =>
  request.get<ApiResponse<StockInfo[]>>('/stock/search', { params: { keyword } })

export const getStockInfo = (stockCode: string) =>
  request.get<any, { code: number; data: StockInfo }>('/stock/info', { params: { stockCode } })

export const getStockDaily = (stockCode: string, startDate?: string, endDate?: string) =>
  request.get<any, { code: number; data: StockDailyData[] }>('/stock/daily', { params: { stockCode, startDate, endDate } })

export const syncStockBasic = () =>
  request.post<any, { code: number; data: number }>('/data/tushare/sync/stock-basic')

export const syncDailyByDate = (tradeDate: string) =>
  request.post<any, { code: number; data: number }>('/data/tushare/sync/daily-by-date', { tradeDate })

export const syncDailyByRange = (tsCode: string, startDate?: string, endDate?: string) =>
  request.post<any, { code: number; data: number }>('/data/tushare/sync/daily', { tsCode, startDate, endDate }, { timeout: 600000 })

export const syncAllIncremental = (startDate?: string, endDate?: string) =>
  request.post<any, { code: number; data: number }>('/data/tushare/sync/all-incremental', { startDate, endDate }, { timeout: 600000 })

export const getSyncStatus = (tsCode: string) =>
  request.get<any, { code: number; data: SyncStatusVO }>('/data/tushare/sync/status', { params: { tsCode } })

/** 美股同步的单标的結果 */
export interface UsSyncResult {
  symbol: string
  success: boolean
  /** 落库日线根数（成功时） */
  count?: number
  /** 失败原因（失败时） */
  message?: string
}

export interface UsSyncBatchData {
  /** 本次使用的数据源：tencent（前复权）/ eastmoney（原始价 + 因子） */
  source: string
  total: number
  succeeded: number
  results: UsSyncResult[]
}

/** 同步单个美股标的 */
export const syncUsSymbol = (symbol: string) =>
  request.post<any, { code: number; data: number }>('/data/us/sync', { symbol }, { timeout: 180000 })

/** 批量同步美股：逐标的返回结果，一只失败不影响其余 */
export const syncUsBatch = (symbols: string[]) =>
  request.post<any, { code: number; data: UsSyncBatchData }>(
    '/data/us/sync-batch',
    { symbols },
    { timeout: 600000 }
  )


/** 持仓数量返回结构 */
export interface HoldingQuantityVO {
  userId: number
  stockCode: string
  holdingQuantity: number
}

/**
 * 查询当前用户对某只股票的持仓数量
 */
export const getHoldingQuantity = (userId: number, stockCode: string) =>
  request.get<any, { code: number; data: HoldingQuantityVO }>('/stock/holding', { params: { userId, stockCode } })

/** 可卖数量返回结构 */
export interface AvailableQuantityVO {
  userId: number
  stockCode: string
  totalQuantity: number
  availableQuantity: number
  frozenQuantity: number
  market: string
  tradeRule: string
  isAStock: boolean
}

/**
 * 查询当前用户对某只股票的可卖数量（考虑A股T+1规则）
 */
export const getAvailableQuantity = (userId: number, stockCode: string) =>
  request.get<any, { code: number; data: AvailableQuantityVO }>('/stock/available-quantity', { params: { userId, stockCode } })

/** 最新价格返回结构 */
export interface LatestPriceVO {
  stockCode: string
  tradeDate: string
  closePrice: string
  preClose: string
}

/**
 * 获取股票最新价格
 */
export const getStockLatestPrice = (stockCode: string) =>
  request.get<any, { code: number; data: LatestPriceVO }>('/stock/latest-price', { params: { stockCode } })
