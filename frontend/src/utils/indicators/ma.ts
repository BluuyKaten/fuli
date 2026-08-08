// ma.ts
import type { CandleItem } from '@/types/indicators'

export function calcMA(data: CandleItem[], period: number): number[] {
  if (period <= 0 || data.length === 0) return []
  const result: number[] = []
  for (let i = 0; i < data.length; i++) {
    if (i < period - 1) {
      result.push(NaN)
      continue
    }
    let sum = 0
    for (let j = 0; j < period; j++) {
      sum += data[i - j].close
    }
    result.push(+(sum / period).toFixed(4))
  }
  return result
}
