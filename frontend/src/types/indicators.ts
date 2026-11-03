// types/indicators.ts
import type { Time } from 'lightweight-charts'

export interface CandleItem {
  time: Time
  open: number
  high: number
  low: number
  close: number
  volume?: number
}

// 指标只依赖自身计算所需的价格，不要求完整的图表数据。
export type ClosePrice = Pick<CandleItem, 'close'>
export type HighLowClosePrice = Pick<CandleItem, 'high' | 'low' | 'close'>
