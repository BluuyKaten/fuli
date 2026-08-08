/**
 * 图表核心 composable
 * 负责 lightweight-charts 图表实例的生命周期管理
 */
import { ref, type Ref } from 'vue'
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  ColorType,
  CrosshairMode,
  LineStyle,
  CandlestickSeries,
  HistogramSeries,
  LineSeries
} from 'lightweight-charts'
import type { ChartTheme } from '@/types/chart'

export function useChartCore(chartContainer: Ref<HTMLElement | null>) {
  // 图表实例
  const chart = ref<IChartApi | null>(null) as Ref<IChartApi | null>
  // 系列映射表：id -> series 实例，统一管理所有已添加的 series
  const seriesMap = ref<Map<string, ISeriesApi<any>>>(new Map())

  /**
   * 初始化图表实例
   * 必须在 chartContainer 已挂载后调用
   */
  const initChart = (theme: ChartTheme) => {
    if (!chartContainer.value) return
    chart.value = createChart(chartContainer.value, {
      layout: {
        background: { type: ColorType.Solid, color: theme.layout.background },
        textColor: theme.layout.textColor
      },
      grid: {
        vertLines: { color: theme.grid.vertLines },
        horzLines: { color: theme.grid.horzLines }
      },
      crosshair: {
        mode: CrosshairMode.Normal,
        vertLine: { color: theme.crosshair, width: 1, style: LineStyle.Dashed },
        horzLine: { color: theme.crosshair, width: 1, style: LineStyle.Dashed }
      },
      rightPriceScale: { borderColor: theme.grid.vertLines },
      timeScale: { borderColor: theme.grid.vertLines, timeVisible: true, secondsVisible: false },
      handleScroll: { vertTouchDrag: false }
    })
  }

  /**
   * 应用主题到已初始化的图表
   * 运行时切换主题时使用
   */
  const applyTheme = (theme: ChartTheme) => {
    if (!chart.value) return
    chart.value.applyOptions({
      layout: {
        background: { type: ColorType.Solid, color: theme.layout.background },
        textColor: theme.layout.textColor
      },
      grid: {
        vertLines: { color: theme.grid.vertLines },
        horzLines: { color: theme.grid.horzLines }
      },
      crosshair: {
        vertLine: { color: theme.crosshair },
        horzLine: { color: theme.crosshair }
      }
    })
  }

  /**
   * 添加 K 线系列（唯一，id 固定为 'candle'）
   */
  const addCandlestickSeries = (options: any) => {
    if (!chart.value) return null
    const s = chart.value.addSeries(CandlestickSeries, options)
    seriesMap.value.set('candle', s)
    return s
  }

  /**
   * 添加成交量柱状图系列
   */
  const addHistogramSeries = (id: string, options: any) => {
    if (!chart.value) return null
    const s = chart.value.addSeries(HistogramSeries, options)
    seriesMap.value.set(id, s)
    return s
  }

  /**
   * 添加折线系列（用于均线、指标线等）
   */
  const addLineSeries = (id: string, options: any) => {
    if (!chart.value) return null
    const s = chart.value.addSeries(LineSeries, options)
    seriesMap.value.set(id, s)
    return s
  }

  /**
   * 移除指定 series
   */
  const removeSeries = (id: string) => {
    const s = seriesMap.value.get(id)
    if (s && chart.value) {
      chart.value.removeSeries(s)
      seriesMap.value.delete(id)
    }
  }

  /**
   * 清空所有 series
   */
  const clearAllSeries = () => {
    const ids = Array.from(seriesMap.value.keys())
    ids.forEach(id => removeSeries(id))
  }

  /**
   * 销毁图表实例，释放资源
   * 组件卸载时调用
   */
  const destroyChart = () => {
    clearAllSeries()
    if (chart.value) {
      chart.value.remove()
      chart.value = null
    }
  }

  return {
    chart,
    initChart,
    applyTheme,
    addCandlestickSeries,
    addHistogramSeries,
    addLineSeries,
    removeSeries,
    clearAllSeries,
    destroyChart,
    seriesMap
  }
}
