// @vitest-environment ./tests/renderer-environment.ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRenderer, nextTick, shallowRef } from 'vue'
import type { IChartApi, ISeriesApi, Time } from 'lightweight-charts'
import LightweightChart from '../LightweightChart.vue'

const mocks = vi.hoisted(() => ({
  addMarker: vi.fn(), destroyChart: vi.fn(), detachMarkers: vi.fn(),
  subscribeRange: vi.fn(), unsubscribeRange: vi.fn(), disconnect: vi.fn(), removeListener: vi.fn(),
  timeAt: vi.fn<() => Time | null>(() => '2024-01-02'),
  priceAt: vi.fn<() => number | null>(() => 10),
  frames: new Map<number, FrameRequestCallback>(), nextFrame: 0,
  context: {
    clearRect: vi.fn(), save: vi.fn(), restore: vi.fn(), beginPath: vi.fn(),
    arc: vi.fn(), fill: vi.fn(), stroke: vi.fn(), setLineDash: vi.fn(),
    moveTo: vi.fn(), lineTo: vi.fn(), fillText: vi.fn()
  }
}))

vi.mock('ant-design-vue', () => ({ message: { error: vi.fn() } }))
vi.mock('@/api/kline', () => ({
  getStockDailyData: vi.fn(async () => ({ data: [] })),
  getStockMinuteData: vi.fn(async () => ({ data: [] })),
  getStockWeeklyData: vi.fn(async () => ({ data: [] })),
  getStockMonthlyData: vi.fn(async () => ({ data: [] })),
  saveDrawing: vi.fn(), loadDrawing: vi.fn()
}))
vi.mock('@/composables/useChartCore', () => ({
  useChartCore: () => {
    const chart = shallowRef({
      timeScale: () => ({
        coordinateToTime: mocks.timeAt, timeToCoordinate: () => 120,
        subscribeVisibleLogicalRangeChange: mocks.subscribeRange, unsubscribeVisibleLogicalRangeChange: mocks.unsubscribeRange
      }),
      priceScale: () => ({ applyOptions: vi.fn() }), applyOptions: vi.fn()
    } as unknown as IChartApi)
    return {
      chart, initChart: vi.fn(), applyTheme: vi.fn(), destroyChart: mocks.destroyChart,
      addCandlestickSeries: () => ({
        coordinateToPrice: mocks.priceAt, priceToCoordinate: () => 60, setData: vi.fn()
      } as unknown as ISeriesApi<'Candlestick'>),
      addHistogramSeries: () => ({ setData: vi.fn() })
    }
  }
}))
vi.mock('@/composables/useIndicators', () => ({ useIndicators: () => ({
  activeIndicators: {}, updateIndicators: vi.fn(), saveIndicators: vi.fn(), clearAllIndicators: vi.fn()
}) }))
vi.mock('@/composables/useTradeMarkers', () => ({ useTradeMarkers: () => ({
  // 组件会解构 markers 做缩略条买卖点投影（sparkMarkers）、解构 clearAutoMarkers 切标的时清自动标记。
  // mock 的返回契约必须和 composable 的真实 return 保持一致，少一个就会在渲染期
  // 抛 "Cannot read properties of undefined (reading 'value')"，9 个用例一起红。
  markers: shallowRef([]),
  loadAutoMarkers: vi.fn(), clearAutoMarkers: vi.fn(),
  addManualMarker: mocks.addMarker, disposeMarkers: mocks.detachMarkers
}) }))
vi.mock('@/composables/useChartExport', () => ({ useChartExport: () => ({
  exporting: false, exportPNG: vi.fn(), exportCSV: vi.fn()
}) }))
vi.mock('@/composables/useInfoPanel', () => ({ useInfoPanel: () => ({
  panelData: null, subscribeCrosshair: vi.fn(), unsubscribeCrosshair: vi.fn()
}) }))

// Vue's renderer drives the real template listeners without a browser or a new DOM dependency.
class TestNode {
  children: TestNode[] = []
  parent: TestNode | null = null
  props: Record<string, unknown> = {}
  style: Record<string, string> = {}
  text = ''
  width = 800
  height = 400
  constructor(readonly tag: string) {}
  getBoundingClientRect() { return { left: 0, top: 0, width: 800, height: 400 } }
  getContext() { return mocks.context }
}
const renderer = createRenderer<TestNode, TestNode>({
  createElement: tag => new TestNode(tag),
  createText: text => Object.assign(new TestNode('#text'), { text }),
  createComment: text => Object.assign(new TestNode('#comment'), { text }),
  setText: (node, text) => { node.text = text },
  setElementText: (node, text) => { node.text = text },
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  patchProp: (node, key, _prev, value) => { node.props[key] = value },
  insert: (node, parent, anchor) => {
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    if (index < 0) parent.children.push(node)
    else parent.children.splice(index, 0, node)
  },
  remove: node => {
    if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1)
  }
})
const all = (node: TestNode): TestNode[] => [node, ...node.children.flatMap(all)]
const stored = new Map<string, string>()
let root: TestNode
let app: ReturnType<typeof renderer.createApp>
let keyHandler: ((event: KeyboardEvent) => void) | undefined

const click = async (text: string) => {
  const button = all(root).find(node => node.tag === 'button' && node.text === text)
  if (!button) throw new Error(`Missing button: ${text}`)
  ;(button.props.onClick as () => void)()
  await nextTick()
}
const wrapper = () => all(root).find(node => typeof node.props.class === 'string' && node.props.class.includes('chart-wrapper'))!
const mouseEvent = (x: number, y: number) => ({
  clientX: x, clientY: y, button: 0, preventDefault: vi.fn(), stopPropagation: vi.fn()
}) as unknown as MouseEvent
const point = async (x = 120, y = 60) => {
  const event = mouseEvent(x, y)
  ;(wrapper().props.onMousedownCapture as (event: MouseEvent) => void)(event)
  await nextTick()
  return event
}
const flushFrames = () => {
  const frames = [...mocks.frames.values()]
  mocks.frames.clear()
  frames.forEach(frame => frame(0))
}

beforeEach(async () => {
  vi.clearAllMocks()
  mocks.timeAt.mockReturnValue('2024-01-02')
  mocks.priceAt.mockReturnValue(10)
  stored.clear()
  mocks.frames.clear()
  vi.stubGlobal('localStorage', {
    getItem: (key: string) => stored.get(key) ?? null,
    setItem: (key: string, value: string) => stored.set(key, value)
  })
  vi.stubGlobal('window', {
    addEventListener: (type: string, handler: (event: KeyboardEvent) => void) => {
      if (type === 'keydown') keyHandler = handler
    }, removeEventListener: mocks.removeListener
  })
  vi.stubGlobal('document', { addEventListener: vi.fn(), removeEventListener: vi.fn() })
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() { mocks.disconnect() } })
  vi.stubGlobal('requestAnimationFrame', (frame: FrameRequestCallback) => {
    const id = ++mocks.nextFrame
    mocks.frames.set(id, frame)
    return id
  })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => mocks.frames.delete(id))
  root = new TestNode('root')
  // 阶段 0 起 stockCode 一律是「代码.市场」的完整符号：
  // 前端不再做后缀推断（toTushareCode 已删），裸码传进来也推不出市场。
  app = renderer.createApp(LightweightChart, { stockCode: '600519.SH' })
  app.mount(root)
  await nextTick()
  flushFrames()
})
afterEach(() => { app.unmount(); vi.unstubAllGlobals() })

describe('chart tool switching through toolbar and canvas events', () => {
  it('enables chart clicks for a marker without a drawing tool', async () => {
    await click('买入标记')
    expect(wrapper().props.class).toContain('active')
    await point()
    expect(mocks.addMarker).toHaveBeenCalledWith('buy', '2024-01-02', 10)
  })

  it('buy marker → trend line draws a line without adding a marker', async () => {
    await click('买入标记')
    await click('趋势线')
    await point()
    await point(200, 100)
    expect(mocks.addMarker).not.toHaveBeenCalled()
    const saved = JSON.parse(stored.get('kline_drawings') ?? '{}')
    expect(saved['600519.SH|1D']).toHaveLength(1)
    expect(saved['600519.SH|1D'][0].type).toBe('trend')
  })

  it('trend line → sell marker clears the partial drawing and tool state', async () => {
    await click('趋势线')
    await point()
    await click('卖出标记')
    expect(all(root).find(node => node.text === '趋势线')?.props.class).not.toContain('active')
    await point()
    expect(mocks.addMarker).toHaveBeenCalledWith('sell', '2024-01-02', 10)
    await click('趋势线')
    await point()
    expect(stored.get('kline_drawings')).toBeUndefined()
  })

  it('Escape clears a partial preview and exits both tool modes', async () => {
    await click('趋势线')
    await point()
    flushFrames()
    mocks.context.clearRect.mockClear()
    keyHandler?.({ key: 'Escape', target: null, preventDefault: vi.fn() } as unknown as KeyboardEvent)
    await nextTick()
    flushFrames()
    expect(wrapper().props.class).not.toContain('active')
    expect(mocks.context.clearRect).toHaveBeenCalled()
    await click('买入标记')
    keyHandler?.({ key: 'Escape', target: null, preventDefault: vi.fn() } as unknown as KeyboardEvent)
    await nextTick()
    expect(wrapper().props.class).not.toContain('active')
  })

  it('renders preview handles using current time/price projection', async () => {
    await click('趋势线')
    await point(10, 20)
    ;(wrapper().props.onMousemoveCapture as (event: MouseEvent) => void)(mouseEvent(200, 100))
    flushFrames()
    expect(mocks.context.arc).toHaveBeenCalledWith(120, 60, 4, 0, Math.PI * 2)
    expect(mocks.context.moveTo).toHaveBeenCalledWith(120, 60)
    expect(mocks.context.lineTo).toHaveBeenCalledWith(200, 100)
  })

  it('passes the first empty-space mousedown through for chart panning', async () => {
    await click('选择')
    const event = await point(500, 300)
    expect(event.preventDefault).not.toHaveBeenCalled()
    expect(event.stopPropagation).not.toHaveBeenCalled()
  })

  it('preserves completed drawings and markers when tools switch', async () => {
    await click('趋势线')
    await point()
    await point(200, 100)
    const before = stored.get('kline_drawings')
    await click('买入标记')
    await point()
    await click('卖出标记')
    await point()
    await click('选择')
    expect(stored.get('kline_drawings')).toBe(before)
    expect(mocks.addMarker.mock.calls.map(call => call[0])).toEqual(['buy', 'sell'])
  })

  it('ignores an unavailable time without completing a malformed drawing', async () => {
    await click('趋势线')
    mocks.timeAt.mockReturnValueOnce(null)
    await point()
    await point()
    expect(stored.get('kline_drawings')).toBeUndefined()
    await point(200, 100)
    const saved = JSON.parse(stored.get('kline_drawings') ?? '{}')
    expect(saved['600519.SH|1D'][0].points).toEqual([
      { time: '2024-01-02', price: 10 }, { time: '2024-01-02', price: 10 }
    ])
  })

  it('releases subscriptions, observer, marker plugin and queued rendering on unmount', async () => {
    await click('趋势线')
    await point()
    expect(mocks.frames.size).toBeGreaterThan(0)
    app.unmount()
    expect(mocks.frames.size).toBe(0)
    expect(mocks.unsubscribeRange).toHaveBeenCalledWith(mocks.subscribeRange.mock.calls[0][0])
    expect(mocks.disconnect).toHaveBeenCalledOnce()
    expect(mocks.detachMarkers).toHaveBeenCalledOnce()
    expect(mocks.removeListener).toHaveBeenCalledWith('keydown', keyHandler)
    expect(mocks.destroyChart).toHaveBeenCalledOnce()
  })
})
