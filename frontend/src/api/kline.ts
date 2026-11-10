/**
 * K 线多周期数据接口
 */
import request from '@/utils/request'

/** 获取分钟 K 线 */
export const getStockMinuteData = (stockCode: string, period: number) =>
  request.get<any, { code: number; data: any[] }>('/stock/minute', {
    params: { code: stockCode, period }
  })

/** 获取周 K 线 */
export const getStockWeeklyData = (stockCode: string) =>
  request.get<any, { code: number; data: any[] }>('/stock/weekly', {
    params: { code: stockCode }
  })

/** 获取月 K 线 */
export const getStockMonthlyData = (stockCode: string) =>
  request.get<any, { code: number; data: any[] }>('/stock/monthly', {
    params: { code: stockCode }
  })

/**
 * 全历史收盘价缩略序列（服务端已做 LTTB 降采样）。
 *
 * 主图只加载最近 620 根，这条序列负责「让用户知道全历史长什么样、现在看的是哪一段」。
 * 约 240 个点 / 4 KB。
 */
export const getStockSparkline = (stockCode: string, points = 240) =>
  request.get<any, { code: number; data: { d: number; c: number }[] }>('/stock/daily/sparkline', {
    params: { stockCode, points }
  })

/**
 * 获取日线数据。
 *
 * - 不传日期：返回最近 `limit` 根（后端缺省 620 = 500 视窗 + 120 指标前置）
 * - 传 startDate/endDate：按区间返回，`limit` 不生效（练习模块依赖完整区间）
 *
 * 窗口化原因见 docs/adr/0002-kline-windowed-loading.md。
 */
export const getStockDailyData = (
  stockCode: string,
  opts: { startDate?: string; endDate?: string; limit?: number } = {}
) =>
  request.get<any, { code: number; data: any[] }>('/stock/daily', {
    params: { stockCode, ...opts }
  })

/** 获取实时行情（5 档） */
export const getStockQuote = (stockCode: string) =>
  request.get<any, { code: number; data: any }>('/stock/quote', {
    params: { code: stockCode }
  })

/** 保存画线 */
export const saveDrawing = (data: { userId: number; stockCode: string; period: string; data: any }) =>
  request.post<any, any>('/stock/drawing', data)

/** 加载画线 */
export const loadDrawing = (userId: number, stockCode: string, period: string) =>
  request.get<any, { code: number; data: { data: string } }>('/stock/drawing', {
    params: { userId, code: stockCode, period }
  })
