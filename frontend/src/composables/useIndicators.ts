/**
 * 技术指标管理 composable
 * 负责技术指标的计算、渲染到 lightweight-charts 图表
 * 支持主图叠加（MA、BOLL）和副图（MACD、KDJ、RSI）指标
 */
import { reactive, type Ref } from 'vue'
import type { IChartApi, ISeriesApi } from 'lightweight-charts'
import { LineSeries, HistogramSeries } from 'lightweight-charts'
import type { ChartTheme } from '@/types/chart'
import type { CandleItem } from '@/types/indicators'
import {
  calcMA, calcMACD, calcKDJ, calcRSI, calcBOLL
} from '@/utils/indicators'

/** 指标配置：控制各指标的启用/禁用状态 */
export interface IndicatorConfig {
  ma5: boolean
  ma10: boolean
  ma20: boolean
  ma60: boolean
  macd: boolean
  kdj: boolean
  rsi: boolean
  boll: boolean
  volume: boolean
}

export function useIndicators(
  chart: Ref<IChartApi | null>,
  theme: Ref<ChartTheme>
) {
  // 当前启用的指标配置（默认启用 MA5/MA10/MACD/成交量）
  const activeIndicators = reactive<IndicatorConfig>({
    ma5: true, ma10: true, ma20: false, ma60: false,
    macd: true, kdj: false, rsi: false, boll: false, volume: true
  })

  // 管理所有指标系列的引用
  const indicatorSeries = reactive<Map<string, ISeriesApi<any>>>(new Map())

  /**
   * 移除指定 id 的指标系列
   */
  const removeIndicatorSeries = (id: string) => {
    const s = indicatorSeries.get(id)
    if (s && chart.value) {
      chart.value.removeSeries(s)
      indicatorSeries.delete(id)
    }
  }

  /**
   * 清空所有指标系列
   */
  const clearAllIndicators = () => {
    indicatorSeries.forEach((_, id) => removeIndicatorSeries(id))
  }

  /**
   * 根据配置更新所有指标系列
   * @param data K 线数据
   * @param config 指标配置
   */
  const updateIndicators = (data: CandleItem[], config: IndicatorConfig) => {
    if (!chart.value || data.length === 0) return
    const t = theme.value.indicators

    // --- 主图叠加指标 ---

    // MA 均线
    const maList = [
      { key: 'ma5', period: 5, enabled: config.ma5 },
      { key: 'ma10', period: 10, enabled: config.ma10 },
      { key: 'ma20', period: 20, enabled: config.ma20 },
      { key: 'ma60', period: 60, enabled: config.ma60 }
    ]
    for (const { key, period, enabled } of maList) {
      removeIndicatorSeries(key)
      if (enabled) {
        // 缓存计算结果，避免重复调用
        const maValues = calcMA(data, period)
        const maData = maValues
          .map((v, i) => ({ time: data[i].time, value: isNaN(v) ? 0 : v }))
          .filter((_, i) => !isNaN(maValues[i]))
        const s = chart.value!.addSeries(LineSeries, {
          color: t[key], lineWidth: 1, priceLineVisible: false,
          lastValueVisible: false, title: `MA${period}`
        })
        s.setData(maData)
        indicatorSeries.set(key, s)
      }
    }

    // BOLL 布林带
    removeIndicatorSeries('bollUpper')
    removeIndicatorSeries('bollMiddle')
    removeIndicatorSeries('bollLower')
    if (config.boll) {
      const { upper, middle, lower } = calcBOLL(data)
      const makeSeries = (id: string, color: string, values: number[]) => {
        const s = chart.value!.addSeries(LineSeries, {
          color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false, title: id
        })
        s.setData(values
          .map((v, i) => ({ time: data[i].time, value: isNaN(v) ? 0 : v }))
          .filter((_, i) => !isNaN(values[i])))
        indicatorSeries.set(id, s)
      }
      makeSeries('bollUpper', t.bollUpper, upper)
      makeSeries('bollMiddle', t.bollMiddle, middle)
      makeSeries('bollLower', t.bollLower, lower)
    }

    // --- 副图指标（独立 pane，通过 priceScaleId 分离刻度）---

    // MACD
    removeIndicatorSeries('dif')
    removeIndicatorSeries('dea')
    removeIndicatorSeries('macdHist')
    if (config.macd) {
      const { dif, dea, histogram } = calcMACD(data)
      // DIF 线
      const sDif = chart.value!.addSeries(LineSeries, {
        color: t.dif, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
        priceScaleId: 'macd', title: 'DIF'
      })
      sDif.setData(dif
        .map((v, i) => ({ time: data[i].time, value: isNaN(v) ? 0 : v }))
        .filter((_, i) => !isNaN(dif[i])))
      indicatorSeries.set('dif', sDif)
      // DEA 线
      const sDea = chart.value!.addSeries(LineSeries, {
        color: t.dea, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
        priceScaleId: 'macd', title: 'DEA'
      })
      sDea.setData(dea
        .map((v, i) => ({ time: data[i].time, value: isNaN(v) ? 0 : v }))
        .filter((_, i) => !isNaN(dea[i])))
      indicatorSeries.set('dea', sDea)
      // MACD 柱状图（红涨绿跌）
      const sHist = chart.value!.addSeries(HistogramSeries, {
        color: t.macdUp, priceScaleId: 'macd', title: 'MACD'
      })
      sHist.setData(histogram
        .map((v, i) => ({
          time: data[i].time, value: isNaN(v) ? 0 : v,
          color: v >= 0 ? t.macdUp : t.macdDown
        }))
        .filter((_, i) => !isNaN(histogram[i])))
      indicatorSeries.set('macdHist', sHist)
      // 设置 MACD 刻度边距
      chart.value!.priceScale('macd').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
    }

    // KDJ
    removeIndicatorSeries('kLine')
    removeIndicatorSeries('dLine')
    removeIndicatorSeries('jLine')
    if (config.kdj) {
      const { k, d, j } = calcKDJ(data)
      const makeLine = (id: string, color: string, values: number[]) => {
        const s = chart.value!.addSeries(LineSeries, {
          color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
          priceScaleId: 'kdj', title: id
        })
        // KDJ 无前导 NaN，直接映射全部数据
        s.setData(values.map((v, i) => ({ time: data[i].time, value: v })))
        indicatorSeries.set(id, s)
      }
      makeLine('kLine', t.k, k)
      makeLine('dLine', t.d, d)
      makeLine('jLine', t.j, j)
      chart.value!.priceScale('kdj').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
    }

    // RSI
    removeIndicatorSeries('rsi')
    if (config.rsi) {
      const rsi = calcRSI(data)
      const s = chart.value!.addSeries(LineSeries, {
        color: t.rsi, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
        priceScaleId: 'rsi', title: 'RSI'
      })
      s.setData(rsi
        .map((v, i) => ({ time: data[i].time, value: isNaN(v) ? 0 : v }))
        .filter((_, i) => !isNaN(rsi[i])))
      indicatorSeries.set('rsi', s)
      chart.value!.priceScale('rsi').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
    }
  }

  return {
    activeIndicators,
    updateIndicators,
    clearAllIndicators,
    indicatorSeries
  }
}
