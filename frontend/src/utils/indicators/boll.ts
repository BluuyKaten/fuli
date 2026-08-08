// boll.ts - BOLL 指标计算
import type { CandleItem } from '@/types/indicators'
import { calcMA } from './ma'

export interface BOLLResult { upper: number[]; middle: number[]; lower: number[] }

/**
 * 计算 BOLL 指标（布林带）
 * @param data K 线数据
 * @param period 计算周期（默认 20）
 * @param k 标准差倍数（默认 2）
 */
export function calcBOLL(data: CandleItem[], period = 20, k = 2): BOLLResult {
  const middle = calcMA(data, period)
  const upper: number[] = []
  const lower: number[] = []
  for (let i = 0; i < data.length; i++) {
    if (isNaN(middle[i])) {
      upper.push(NaN); lower.push(NaN); continue
    }
    let variance = 0
    for (let j = 0; j < period; j++) {
      variance += (data[i - j].close - middle[i]) ** 2
    }
    const std = Math.sqrt(variance / period)
    upper.push(+(middle[i] + k * std).toFixed(4))
    lower.push(+(middle[i] - k * std).toFixed(4))
  }
  return { upper, middle, lower }
}
