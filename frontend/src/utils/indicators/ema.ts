// ema.ts
import type { CandleItem } from '@/types/indicators'

export function calcEMA(data: CandleItem[], period: number): number[] {
  if (period <= 0 || data.length === 0) return []
  const k = 2 / (period + 1)
  const result: number[] = []
  for (let i = 0; i < data.length; i++) {
    if (i < period - 1) {
      result.push(NaN)
    } else if (i === period - 1) {
      let sum = 0
      for (let j = 0; j < period; j++) sum += data[i - j].close
      result.push(+(sum / period).toFixed(4))
    } else {
      result.push(+(data[i].close * k + result[i - 1] * (1 - k)).toFixed(4))
    }
  }
  return result
}
