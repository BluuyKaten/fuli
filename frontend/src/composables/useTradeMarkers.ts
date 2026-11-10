// useTradeMarkers.ts
import { ref, watch, type Ref } from 'vue'
import { createSeriesMarkers, type ISeriesApi, type ISeriesMarkersPluginApi, type SeriesMarker, type Time } from 'lightweight-charts'
import { getTradeList } from '@/api/trade'

export interface TradeMarker {
  id: string
  source: 'manual' | 'auto'
  direction: 'buy' | 'sell'
  time: Time
  price: number
  quantity?: number
  editable: boolean
}

export function useTradeMarkers(candleSeries: Ref<ISeriesApi<'Candlestick'> | null>) {
  const markers = ref<TradeMarker[]>([])
  let plugin: ISeriesMarkersPluginApi<Time> | null = null
  let attachedSeries: ISeriesApi<'Candlestick'> | null = null
  let disposed = false
  let loadVersion = 0

  const timeValue = (time: Time): number => {
    if (typeof time === 'number') return time
    if (typeof time === 'string') return Date.parse(time) / 1000
    return Date.UTC(time.year, time.month - 1, time.day) / 1000
  }

  const toSeriesMarkers = (): SeriesMarker<Time>[] => {
    return [...markers.value].sort((a, b) => timeValue(a.time) - timeValue(b.time)).map(m => ({
      time: m.time,
      // 买点标在下方、卖点标在上方，避免压住蜡烛实体
      position: m.direction === 'buy' ? 'belowBar' : 'aboveBar',
      // A 股习惯：买用红、卖用绿
      color: m.direction === 'buy' ? '#ef5350' : '#26a69a',
      shape: 'circle',
      text: m.direction === 'buy' ? 'B' : 'S',
      // size 用默认值 1。之前调到 2 是为了对抗 MA 标签遮挡，但实测偏大、压住蜡烛实体，
      // 用户反馈"红点太大"。遮挡问题改用下移位置解决，不靠放尺寸。
      size: 1,
      id: m.id
    }))
  }

  const applyMarkers = () => {
    if (disposed) return
    const series = candleSeries.value
    if (series !== attachedSeries) {
      plugin?.detach()
      plugin = null
      attachedSeries = series
    }
    if (!series) return
    if (!plugin) plugin = createSeriesMarkers(series, [])
    plugin.setMarkers(toSeriesMarkers())
  }

  const stopWatching = watch(candleSeries, applyMarkers, { flush: 'sync' })

  const addManualMarker = (direction: 'buy' | 'sell', time: Time, price: number) => {
    const marker: TradeMarker = {
      id: `manual-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      source: 'manual', direction, time, price, editable: true
    }
    markers.value.push(marker)
    applyMarkers()
    return marker
  }

  /**
   * 加载历史成交自动标记。
   *
   * @param stockCode 股票代码
   * @param barRange  当前已加载 K 线的日期范围（含）。窗口化之后必须传：
   *                  marker 的 time 若不在 series 数据范围内，lightweight-charts
   *                  会拒绝渲染甚至抛错，表现为「买卖点整片消失」。
   *                  越界的标记会被暂存到 skippedCount，供上层提示「还有更早的成交」。
   */
  const loadAutoMarkers = async (
    stockCode: string,
    barRange?: { from: string; to: string }
  ): Promise<{ loaded: number; skipped: number }> => {
    const version = ++loadVersion
    markers.value = markers.value.filter(m => m.source === 'manual')
    applyMarkers()
    if (disposed || !stockCode) return { loaded: 0, skipped: 0 }
    try {
      // userId 不需要前端传：TradeRecordController.list 会用 token 里的 userId
      // 覆盖请求体中的值。原先这里用 localStorage.userId 做门槛，
      // 一旦它缺失（换浏览器、清缓存、HMR 重载），标记就永远不加载且没有任何提示。
      const res = await getTradeList({ stockCode, pageNum: 1, pageSize: 200 })
      if (disposed || version !== loadVersion || res.code !== 200 || !res.data) {
        return { loaded: 0, skipped: 0 }
      }
      const all: TradeMarker[] = res.data.map(t => ({
        id: `auto-${t.id}`,
        source: 'auto',
        direction: t.tradeType === 1 ? 'buy' : 'sell',
        time: String(t.tradeDate).slice(0, 10),
        price: t.tradePrice,
        quantity: t.tradeQuantity,
        editable: false
      }))
      const inRange = barRange
        ? all.filter(m => {
            const d = String(m.time)
            return d >= barRange.from && d <= barRange.to
          })
        : all
      markers.value = markers.value.filter(m => m.source === 'manual').concat(inRange)
      applyMarkers()
      if (all.length === 0) {
        console.info(`[买卖点] ${stockCode}: 无成交记录`)
      } else {
        console.info(
          `[买卖点] ${stockCode}: 共 ${all.length} 条，窗口内显示 ${inRange.length} 条` +
            (barRange ? `（窗口 ${barRange.from} ~ ${barRange.to}）` : ''),
          inRange.map(m => `${m.direction === 'buy' ? 'B' : 'S'}@${m.time}`).join(' ')
        )
      }
      return { loaded: inRange.length, skipped: all.length - inRange.length }
    } catch (e) {
      console.error('[useTradeMarkers] 加载交易标记失败:', e)
      return { loaded: 0, skipped: 0 }
    }
  }

  /** 只清除自动标记，保留用户手动画的 */
  const clearAutoMarkers = () => {
    markers.value = markers.value.filter(m => m.source === 'manual')
    applyMarkers()
  }

  const removeMarker = (id: string) => {
    markers.value = markers.value.filter(m => m.id !== id)
    applyMarkers()
  }

  const clearMarkers = () => {
    markers.value = []
    applyMarkers()
  }

  const disposeMarkers = () => {
    disposed = true
    ++loadVersion
    stopWatching()
    plugin?.detach()
    plugin = null
    attachedSeries = null
  }

  return { markers, addManualMarker, loadAutoMarkers, clearAutoMarkers, removeMarker, clearMarkers, applyMarkers, disposeMarkers }
}
