import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { shallowRef } from 'vue'
import type { IChartApi, ISeriesApi } from 'lightweight-charts'
import { loadDrawing } from '@/api/kline'
import { useDrawingTools } from '../useDrawingTools'

vi.mock('@/api/kline', () => ({ saveDrawing: vi.fn(), loadDrawing: vi.fn() }))
const frames = new Map<number, FrameRequestCallback>()
let frameId = 0
const drawing = {
  id: 'saved', type: 'trend', points: [{ time: '2024-01-02', price: 10 }, { time: '2024-01-03', price: 12 }],
  color: '#58a6ff', lineWidth: 2, lineStyle: 'solid', visible: true, createdAt: 1
}
const setupTools = () => useDrawingTools(
  shallowRef<IChartApi | null>(null), shallowRef<HTMLCanvasElement | null>(null),
  shallowRef<ISeriesApi<'Candlestick'> | null>(null)
)

beforeEach(() => {
  vi.clearAllMocks()
  frames.clear()
  vi.stubGlobal('localStorage', { getItem: (key: string) => key === 'userId' ? '1' : JSON.stringify({ '600519.SH|1D': [drawing] }) })
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => {
    frames.set(++frameId, callback)
    return frameId
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => frames.delete(id))
  vi.spyOn(console, 'error').mockImplementation(() => {})
})
afterEach(() => { vi.unstubAllGlobals(); vi.restoreAllMocks() })

describe('drawing loading lifecycle', () => {
  it('does not restore local drawings or schedule rendering after an unmounted request rejects', async () => {
    let rejectRequest!: (reason: Error) => void
    vi.mocked(loadDrawing).mockReturnValueOnce(new Promise((_resolve, reject) => { rejectRequest = reject }))
    const tools = setupTools()
    const loading = tools.loadDrawings('600519.SH', '1D')
    tools.disposeDrawingTools()
    rejectRequest(new Error('offline'))
    await loading
    expect(tools.drawings.value).toEqual([])
    expect(frames.size).toBe(0)
  })

  it('does not let a failed older load replace the current stock drawings', async () => {
    let rejectRequest!: (reason: Error) => void
    vi.mocked(loadDrawing).mockReturnValueOnce(new Promise((_resolve, reject) => { rejectRequest = reject }))
    vi.mocked(loadDrawing).mockResolvedValueOnce({ code: 200, data: { data: '[]' } })
    const tools = setupTools()
    const oldLoading = tools.loadDrawings('600519.SH', '1D')
    await tools.loadDrawings('000001.SZ', '1D')
    rejectRequest(new Error('late failure'))
    await oldLoading
    expect(tools.drawings.value).toEqual([])
    tools.disposeDrawingTools()
  })
})
