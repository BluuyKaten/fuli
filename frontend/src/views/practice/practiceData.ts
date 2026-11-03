import type { CreatePracticeRequest, PracticeAnnotation, PracticeBar, PracticeView } from '@/api/practice'
import { calcMA } from '@/utils/indicators/ma'

// Keep the visible-prefix boundary here, before data reaches reactive state or indicators.
// Even a malformed ACTIVE response cannot populate a client-side review cache.
export function mapPracticeView(source: PracticeView): PracticeView {
  if (typeof source.id !== 'string' || !source.id) throw new Error('练习标识无效，请重新打开练习')
  const visible = (date: string) => date <= source.currentDate
  const result: PracticeView = {
    id: source.id, stockCode: source.stockCode, startDate: source.startDate, endDate: source.endDate,
    currentDate: source.currentDate, status: source.status, version: source.version, createdAt: source.createdAt,
    initialCash: source.initialCash, cash: source.cash, quantity: source.quantity, availableQuantity: source.availableQuantity,
    equity: source.equity, returnRate: source.returnRate, benchmarkEquity: source.benchmarkEquity,
    benchmarkReturnRate: source.benchmarkReturnRate, excessReturnRate: source.excessReturnRate,
    maxDrawdown: source.maxDrawdown, fees: source.fees, backgroundCount: source.backgroundCount,
    revealedSteps: source.revealedSteps, totalSteps: source.totalSteps,
    bars: source.bars.filter(bar => visible(bar.date)),
    pendingOrder: source.pendingOrder,
    orders: source.orders.filter(order => visible(order.submittedDate) && (!order.executedDate || visible(order.executedDate))),
    fills: source.fills.filter(fill => visible(fill.date)),
    equityCurve: source.equityCurve.filter(point => visible(point.date)),
    annotations: source.annotations.filter(annotation => annotation.points.every(point => visible(point.date)))
  }
  if (source.status === 'FINISHED' && source.reviewBars) result.reviewBars = source.reviewBars
  return result
}

export const chartBars = (view: PracticeView): PracticeBar[] =>
  view.status === 'FINISHED' ? view.reviewBars ?? view.bars : view.bars

export function movingAverage(bars: PracticeBar[], period: number) {
  const values = calcMA(bars, period)
  return bars.flatMap((bar, index) => Number.isFinite(values[index]) ? [{ time: bar.date, value: values[index] }] : [])
}

export function normalizePracticeCode(input: string): string {
  const code = input.trim().toUpperCase()
  if (/^\d{6}\.(SH|SZ|BJ)$/.test(code)) return code
  if (/^6\d{5}$/.test(code)) return `${code}.SH`
  if (/^[03]\d{5}$/.test(code)) return `${code}.SZ`
  if (/^[489]\d{5}$/.test(code)) return `${code}.BJ`
  return code
}

export function validateCreation(data: CreatePracticeRequest): string | null {
  if (!/^\d{6}\.(SH|SZ|BJ)$/.test(data.stockCode)) return '输入六位 A 股代码，或带 .SH / .SZ / .BJ 的完整代码'
  const validDate = (date: string) => {
    const parsed = new Date(`${date}T00:00:00Z`)
    return /^\d{4}-\d{2}-\d{2}$/.test(date) && Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === date
  }
  if (!data.startDate || !data.endDate || !validDate(data.startDate) || !validDate(data.endDate)) return '请选择有效的起止日期'
  if (data.startDate >= data.endDate) return '结束日期须晚于起始日期，并至少有一根后续行情'
  if (!Number.isFinite(data.initialCash) || data.initialCash < 100 || data.initialCash > 1000000000 || Math.abs(data.initialCash * 100 - Math.round(data.initialCash * 100)) > 0.00001) return '初始资金须为 100 至 1,000,000,000 元，精确到分'
  return null
}
export const validQuantity = (quantity: number | null): quantity is number =>
  quantity !== null && Number.isSafeInteger(quantity) && quantity > 0 && quantity <= 10000000 && quantity % 100 === 0

export function annotationAtVisiblePoint(annotation: PracticeAnnotation, bars: PracticeBar[], currentDate: string): boolean {
  const expected = annotation.type === 'LINE' ? 2 : 1
  return annotation.points.length === expected && annotation.points.every(point =>
    point.date <= currentDate && bars.some(bar => bar.date === point.date) && Number.isFinite(point.price) && point.price > 0)
}
export const money = (value: number) => value.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
export const percent = (value: number) => `${value > 0 ? '+' : ''}${(value * 100).toFixed(2)}%`
export const quantityText = (value: number) => value.toLocaleString('zh-CN')
export const sideText = (side: 'BUY' | 'SELL') => side === 'BUY' ? '买入' : '卖出'
export const orderStatus: Record<string, string> = { PENDING: '待成交', FILLED: '已成交', REJECTED: '已拒绝', CANCELLED: '已撤销' }
export function errorText(error: unknown): string {
  if (typeof error === 'object' && error !== null && 'response' in error) {
    const response = (error as { response?: { data?: { message?: string } } }).response
    if (response?.data?.message) return response.data.message
  }
  return error instanceof Error ? error.message : '请求失败，请重试'
}
export function isStale(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false
  return (error as { response?: { status?: number } }).response?.status === 409 || (error as { code?: number }).code === 409
}
