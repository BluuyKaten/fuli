// types/indicators.ts
export interface CandleItem {
  time: string | number
  open: number
  high: number
  low: number
  close: number
  volume?: number
}
