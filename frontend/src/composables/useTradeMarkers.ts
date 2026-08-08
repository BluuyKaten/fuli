// useTradeMarkers.ts
import { ref, type Ref } from 'vue'
import type { IChartApi, ISeriesApi } from 'lightweight-charts'
import type { SeriesMarker } from 'lightweight-charts'
import { getTradeList } from '@/api/trade'

export interface TradeMarker {
  id: string
  source: 'manual' | 'auto'
  direction: 'buy' | 'sell'
  time: string
  price: number
  quantity?: number
  editable: boolean
}

export function useTradeMarkers(
  chart: Ref<IChartApi | null>,
  candleSeries: Ref<ISeriesApi<any> | null>
) {
  const markers = ref<TradeMarker[]>([])

  const BUY_COLOR = '#26a69a'
  const SELL_COLOR = '#ef5350'

  const toSeriesMarkers = (): SeriesMarker[] => {
    return markers.value.map(m => ({
      time: m.time as any,
      position: m.direction === 'buy' ? 'belowBar' : 'aboveBar',
      color: m.direction === 'buy' ? BUY_COLOR : SELL_COLOR,
      shape: m.direction === 'buy' ? 'arrowUp' : 'arrowDown',
      text: m.direction === 'buy' ? '买' : '卖',
      id: m.id
    }))
  }

  const applyMarkers = () => {
    if (!candleSeries.value) return
    candleSeries.value.setMarkers(toSeriesMarkers())
  }

  const addManualMarker = (direction: 'buy' | 'sell', time: string, price: number) => {
    const marker: TradeMarker = {
      id: `manual-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      source: 'manual',
      direction,
      time,
      price,
      editable: true
    }
    markers.value.push(marker)
    applyMarkers()
    return marker
  }

  const loadAutoMarkers = async (stockCode: string) => {
    try {
      const userId = Number(localStorage.getItem('userId') || '0')
      if (userId <= 0 || !stockCode) return
      const res = await getTradeList({ userId, stockCode, pageNum: 1, pageSize: 200 })
      if (res.code !== 200 || !res.data) return

      const autoMarkers: TradeMarker[] = res.data.map(t => ({
        id: `auto-${t.id}`,
        source: 'auto',
        direction: t.tradeType === 1 ? 'buy' : 'sell',
        time: t.tradeDate,
        price: t.tradePrice,
        quantity: t.tradeQuantity,
        editable: false
      }))

      // 移除旧的 auto 标记，保留 manual 标记
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

  return {
    markers,
    addManualMarker,
    loadAutoMarkers,
    removeMarker,
    clearMarkers,
    applyMarkers
  }
}
