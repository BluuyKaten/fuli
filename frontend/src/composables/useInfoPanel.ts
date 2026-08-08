// useInfoPanel.ts
import { ref, computed, type Ref } from 'vue'
import type { IChartApi } from 'lightweight-charts'
import type { CandleItem } from '@/types/indicators'
import type { IndicatorConfig } from './useIndicators'
import { calcMA, calcMACD, calcKDJ, calcRSI, calcBOLL } from '@/utils/indicators'

export interface InfoPanelData {
  date: string
  open: number
  high: number
  low: number
  close: number
  change: number
  volume: number
  ma5: number | null
  ma10: number | null
  ma20: number | null
  ma60: number | null
  macdDif: number | null
  macdDea: number | null
  macdHist: number | null
  kdjK: number | null
  kdjD: number | null
  kdjJ: number | null
  rsi: number | null
  bollUpper: number | null
  bollMiddle: number | null
  bollLower: number | null
}

export function useInfoPanel(
  chart: Ref<IChartApi | null>,
  data: Ref<CandleItem[]>,
  activeIndicators: IndicatorConfig
) {
  const currentIndex = ref(-1)

  const panelData = computed<InfoPanelData | null>(() => {
    const idx = currentIndex.value
    if (idx < 0 || idx >= data.value.length) return null
    const d = data.value[idx]
    const prevClose = idx > 0 ? data.value[idx - 1].close : d.open
    const change = ((d.close - prevClose) / prevClose) * 100

    const result: InfoPanelData = {
      date: String(d.time),
      open: d.open, high: d.high, low: d.low, close: d.close,
      change: +change.toFixed(2),
      volume: d.volume ?? 0,
      ma5: null, ma10: null, ma20: null, ma60: null,
      macdDif: null, macdDea: null, macdHist: null,
      kdjK: null, kdjD: null, kdjJ: null,
      rsi: null,
      bollUpper: null, bollMiddle: null, bollLower: null
    }

    if (activeIndicators.ma5) result.ma5 = calcMA(data.value, 5)[idx]
    if (activeIndicators.ma10) result.ma10 = calcMA(data.value, 10)[idx]
    if (activeIndicators.ma20) result.ma20 = calcMA(data.value, 20)[idx]
    if (activeIndicators.ma60) result.ma60 = calcMA(data.value, 60)[idx]
    if (activeIndicators.macd) {
      const { dif, dea, histogram } = calcMACD(data.value)
      result.macdDif = dif[idx]
      result.macdDea = dea[idx]
      result.macdHist = histogram[idx]
    }
    if (activeIndicators.kdj) {
      const { k, d, j } = calcKDJ(data.value)
      result.kdjK = k[idx]; result.kdjD = d[idx]; result.kdjJ = j[idx]
    }
    if (activeIndicators.rsi) result.rsi = calcRSI(data.value)[idx]
    if (activeIndicators.boll) {
      const { upper, middle, lower } = calcBOLL(data.value)
      result.bollUpper = upper[idx]; result.bollMiddle = middle[idx]; result.bollLower = lower[idx]
    }

    return result
  })

  let unsubscribe: (() => void) | null = null

  const subscribeCrosshair = () => {
    if (!chart.value) return
    const handler = (param: any) => {
      if (param.seriesData) {
        const idx = data.value.findIndex(d => String(d.time) === String(param.time))
        if (idx >= 0) currentIndex.value = idx
      }
    }
    chart.value.subscribeCrosshairMove(handler)
    unsubscribe = () => chart.value?.unsubscribeCrosshairMove(handler)
  }

  const unsubscribeCrosshair = () => {
    unsubscribe?.()
    unsubscribe = null
  }

  return { currentIndex, panelData, subscribeCrosshair, unsubscribeCrosshair }
}
