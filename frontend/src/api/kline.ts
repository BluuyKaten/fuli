/**
 * K 线多周期数据接口
 */
import request from '@/utils/request'

/**
 * 一条周期选项。
 *
 * `kind` 决定调哪个端点：`minute` 走 /stock/minute、`day` 走 /stock/daily、
 * `week` 走 /stock/weekly、`month` 走 /stock/monthly。
 * 早先前端靠 `['1','5','15','60'].includes(period)` 猜，加周期就得改判断，漏改就是「点了没反应」。
 */
export interface KlinePeriod {
  value: string
  label: string
  kind: 'minute' | 'day' | 'week' | 'month'
}

export interface KlinePeriodMeta {
  /** SH / SZ / BJ / US */
  market: string
  defaultPeriod: string
  /** 时间轴显示口径；美股也是 Asia/Shanghai（东财统一北京时间） */
  timezone: string
  periods: KlinePeriod[]
}

/** 该标的可用的周期（服务端下发，前端不再硬编码） */
export const getStockPeriods = (code: string) =>
  request.get<any, { code: number; data: KlinePeriodMeta }>('/stock/periods', {
    params: { code }
  })

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
