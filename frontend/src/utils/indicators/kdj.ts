// kdj.ts - KDJ 指标计算
import type { CandleItem } from '@/types/indicators'

export interface KDJResult { k: number[]; d: number[]; j: number[] }

/**
 * 计算 KDJ 指标（随机指标）
 * @param data K 线数据
 * @param n 计算周期（默认 9）
 */
export function calcKDJ(data: CandleItem[], n = 9): KDJResult {
  const kArr: number[] = [], dArr: number[] = [], jArr: number[] = []
  for (let i = 0; i < data.length; i++) {
    if (i < n - 1) {
      kArr.push(50); dArr.push(50); jArr.push(50); continue
    }
    const highs: number[] = [], lows: number[] = []
    for (let j = i - n + 1; j <= i; j++) {
      highs.push(data[j].high)
      lows.push(data[j].low)
    }
    const high = Math.max(...highs)
    const low = Math.min(...lows)
    const rsv = high === low ? 50 : ((data[i].close - low) / (high - low)) * 100
    const k = (2 / 3) * kArr[i - 1] + (1 / 3) * rsv
    const d = (2 / 3) * dArr[i - 1] + (1 / 3) * k
    const j = 3 * k - 2 * d
    kArr.push(+k.toFixed(2)); dArr.push(+d.toFixed(2)); jArr.push(+j.toFixed(2))
  }
  return { k: kArr, d: dArr, j: jArr }
}
