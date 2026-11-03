import { beforeEach, describe, expect, it, vi } from 'vitest'
import { shallowRef } from 'vue'
import type { ISeriesApi, Time } from 'lightweight-charts'
import { useTradeMarkers } from '../useTradeMarkers'

const plugin = vi.hoisted(() => ({ setMarkers: vi.fn(), detach: vi.fn() }))
const createPlugin = vi.hoisted(() => vi.fn(() => plugin))
vi.mock('lightweight-charts', () => ({ createSeriesMarkers: createPlugin }))
vi.mock('@/api/trade', () => ({ getTradeList: vi.fn(async () => ({ code: 200, data: [] })) }))

beforeEach(() => { vi.clearAllMocks() })

describe('v5 series marker plugin', () => {
  it('creates one plugin, sorts markers chronologically and preserves existing manual markers', () => {
    const series = shallowRef({} as ISeriesApi<'Candlestick'> | null)
    const tools = useTradeMarkers(series)
    tools.addManualMarker('sell', '2024-01-03', 12)
    tools.addManualMarker('buy', '2024-01-02', 10)
    expect(createPlugin).toHaveBeenCalledTimes(1)
    const applied = plugin.setMarkers.mock.lastCall?.[0] as { time: Time }[]
    expect(applied.map(marker => marker.time)).toEqual(['2024-01-02', '2024-01-03'])
    expect(tools.markers.value).toHaveLength(2)
    tools.disposeMarkers()
    expect(plugin.detach).toHaveBeenCalledOnce()
  })

  it('detaches from an old series before reattaching and does not recreate on dispose', () => {
    const series = shallowRef({} as ISeriesApi<'Candlestick'> | null)
    const tools = useTradeMarkers(series)
    tools.addManualMarker('buy', '2024-01-02', 10)
    series.value = {} as ISeriesApi<'Candlestick'>
    tools.applyMarkers()
    expect(plugin.detach).toHaveBeenCalledOnce()
    expect(createPlugin).toHaveBeenCalledTimes(2)
    tools.disposeMarkers()
    tools.disposeMarkers()
    tools.applyMarkers()
    expect(plugin.detach).toHaveBeenCalledTimes(2)
    expect(createPlugin).toHaveBeenCalledTimes(2)
  })
})
