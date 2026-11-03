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

  // 指标序列缓存：作为 computed 依赖 data + activeIndicators，
  // 仅在数据或指标开关变化时重算，十字光标移动时不再全量重算各指标。
  const indicatorSeries = computed(() => {
    const d = data.value
    return {
      ma5: activeIndicators.ma5 ? calcMA(d, 5) : null,
      ma10: activeIndicators.ma10 ? calcMA(d, 10) : null,
      ma20: activeIndicators.ma20 ? calcMA(d, 20) : null,
      ma60: activeIndicators.ma60 ? calcMA(d, 60) : null,
      macd: activeIndicators.macd ? calcMACD(d) : null,
      kdj: activeIndicators.kdj ? calcKDJ(d) : null,
      rsi: activeIndicators.rsi ? calcRSI(d) : null,
      boll: activeIndicators.boll ? calcBOLL(d) : null
    }
  })

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

    const s = indicatorSeries.value
    if (s.ma5) result.ma5 = s.ma5[idx]
    if (s.ma10) result.ma10 = s.ma10[idx]
    if (s.ma20) result.ma20 = s.ma20[idx]
    if (s.ma60) result.ma60 = s.ma60[idx]
    if (s.macd) {
      result.macdDif = s.macd.dif[idx]
      result.macdDea = s.macd.dea[idx]
      result.macdHist = s.macd.histogram[idx]
    }
    if (s.kdj) {
      result.kdjK = s.kdj.k[idx]; result.kdjD = s.kdj.d[idx]; result.kdjJ = s.kdj.j[idx]
    }
    if (s.rsi) result.rsi = s.rsi[idx]
    if (s.boll) {
      result.bollUpper = s.boll.upper[idx]; result.bollMiddle = s.boll.middle[idx]; result.bollLower = s.boll.lower[idx]
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
