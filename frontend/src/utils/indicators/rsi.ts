// rsi.ts - RSI 指标计算
import type { CandleItem } from '@/types/indicators'

/**
 * 计算 RSI 指标（相对强弱指数）
 * @param data K 线数据
 * @param period 计算周期（默认 14）
 */
export function calcRSI(data: CandleItem[], period = 14): number[] {
  if (data.length === 0) return []
  const result: number[] = new Array(data.length).fill(NaN)
  if (data.length < period + 1) return result

  const changes = data.map((d, i) => i === 0 ? 0 : d.close - data[i - 1].close)
  let avgGain = 0, avgLoss = 0

  // 初始平均：前 period 个变化
  for (let i = 1; i <= period; i++) {
    if (changes[i] > 0) avgGain += changes[i]
    else avgLoss += Math.abs(changes[i])
  }
  avgGain /= period
  avgLoss /= period

  result[period] = avgLoss === 0 ? 100 : +(100 - 100 / (1 + avgGain / avgLoss)).toFixed(2)

  for (let i = period + 1; i < data.length; i++) {
    const gain = changes[i] > 0 ? changes[i] : 0
    const loss = changes[i] < 0 ? Math.abs(changes[i]) : 0
    avgGain = (avgGain * (period - 1) + gain) / period
    avgLoss = (avgLoss * (period - 1) + loss) / period
    result[i] = avgLoss === 0 ? 100 : +(100 - 100 / (1 + avgGain / avgLoss)).toFixed(2)
  }
  return result
}
