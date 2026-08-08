// macd.ts
import type { CandleItem } from '@/types/indicators'
import { calcEMA } from './ema'

export interface MACDResult {
  dif: number[]
  dea: number[]
  histogram: number[]
}

export function calcMACD(data: CandleItem[], fast = 12, slow = 26, signal = 9): MACDResult {
  const emaFast = calcEMA(data, fast)
  const emaSlow = calcEMA(data, slow)
  const dif = emaFast.map((v, i) => (isNaN(v) || isNaN(emaSlow[i])) ? NaN : +(v - emaSlow[i]).toFixed(4))

  // DEA = EMA(DIF, signal)，只对有效值计算
  const validDif: { idx: number; val: number }[] = []
  dif.forEach((v, i) => { if (!isNaN(v)) validDif.push({ idx: i, val: v }) })

  const dea = new Array(data.length).fill(NaN)
  const k = 2 / (signal + 1)
  for (let j = 0; j < validDif.length; j++) {
    const { idx, val } = validDif[j]
    if (j < signal - 1) continue
    if (j === signal - 1) {
      let sum = 0
      for (let m = 0; m < signal; m++) sum += validDif[m].val
      dea[idx] = +(sum / signal).toFixed(4)
    } else {
      const prevIdx = validDif[j - 1].idx
      dea[idx] = +(val * k + dea[prevIdx] * (1 - k)).toFixed(4)
    }
  }

  const histogram = dif.map((v, i) => (isNaN(v) || isNaN(dea[i])) ? NaN : +(v - dea[i]) * 2)
  return { dif, dea, histogram }
}
