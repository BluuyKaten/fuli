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
      position: m.direction === 'buy' ? 'belowBar' : 'aboveBar',
      color: m.direction === 'buy' ? '#26a69a' : '#ef5350',
      shape: m.direction === 'buy' ? 'arrowUp' : 'arrowDown',
      text: m.direction === 'buy' ? '买' : '卖',
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

  const loadAutoMarkers = async (stockCode: string) => {
    const version = ++loadVersion
    markers.value = markers.value.filter(m => m.source === 'manual')
    applyMarkers()
    try {
      const userId = Number(localStorage.getItem('userId') || '0')
      if (userId <= 0 || !stockCode) return
      const res = await getTradeList({ userId, stockCode, pageNum: 1, pageSize: 200 })
      if (disposed || version !== loadVersion || res.code !== 200 || !res.data) return
      const autoMarkers: TradeMarker[] = res.data.map(t => ({
        id: `auto-${t.id}`,
        source: 'auto',
        direction: t.tradeType === 1 ? 'buy' : 'sell',
        time: t.tradeDate.slice(0, 10),
        price: t.tradePrice,
        quantity: t.tradeQuantity,
        editable: false
      }))
      markers.value = markers.value.filter(m => m.source === 'manual').concat(autoMarkers)
      applyMarkers()
    } catch (e) {
      console.error('[useTradeMarkers] 加载交易标记失败:', e)
    }
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

  return { markers, addManualMarker, loadAutoMarkers, removeMarker, clearMarkers, applyMarkers, disposeMarkers }
}
