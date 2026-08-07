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

/** 获取日线数据（已有接口） */
export const getStockDailyData = (stockCode: string) =>
  request.get<any, { code: number; data: any[] }>('/stock/daily', {
    params: { stockCode }
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
