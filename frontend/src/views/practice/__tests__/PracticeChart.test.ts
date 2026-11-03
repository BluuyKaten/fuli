import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createRenderer, nextTick } from 'vue'
import type { MouseEventParams } from 'lightweight-charts'
import PracticeChart from '../components/PracticeChart.vue'
import { bar } from './fixtures'
import type { PracticeAnnotation } from '@/api/practice'

const mocks = vi.hoisted(() => ({
  click: undefined as ((event: MouseEventParams) => void) | undefined,
  setData: vi.fn(), markerData: vi.fn(), detach: vi.fn(), remove: vi.fn(), disconnect: vi.fn(),
  addListener: vi.fn(), removeListener: vi.fn(), frames: new Map<number, FrameRequestCallback>(), frame: 0,
  keydown: undefined as ((event: KeyboardEvent) => void) | undefined
}))
vi.mock('lightweight-charts', () => ({
  ColorType: { Solid: 'solid' }, CandlestickSeries: 'candles', HistogramSeries: 'volume', LineSeries: 'line',
  createChart: () => ({
    addSeries: () => ({ setData: mocks.setData, priceToCoordinate: () => 80, coordinateToPrice: () => 10 }),
    panes: () => [{}, { setHeight: vi.fn() }],
    subscribeClick: (handler: (event: MouseEventParams) => void) => { mocks.click = handler },
    unsubscribeClick: vi.fn(), subscribeCrosshairMove: vi.fn(), unsubscribeCrosshairMove: vi.fn(),
    timeScale: () => ({ timeToCoordinate: () => 100, subscribeVisibleLogicalRangeChange: vi.fn(), unsubscribeVisibleLogicalRangeChange: vi.fn(),
      getVisibleLogicalRange: () => null, fitContent: vi.fn(), scrollToRealTime: vi.fn() }),
    resize: vi.fn(), applyOptions: vi.fn(), remove: mocks.remove
  }), createSeriesMarkers: () => ({ setMarkers: mocks.markerData, detach: mocks.detach })
}))
class Node {
  children: Node[] = []
  parent: Node | null = null
  props: Record<string, unknown> = {}
  text = ''
  clientWidth = 800
  clientHeight = 400
  constructor(readonly tag: string) {}
}
const renderer = createRenderer<Node, Node>({
  createElement: tag => new Node(tag), createText: text => Object.assign(new Node('#text'), { text }),
  createComment: text => Object.assign(new Node('#comment'), { text }),
  setText: (node, text) => { node.text = text }, setElementText: (node, text) => { node.text = text },
  parentNode: node => node.parent, nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] ?? null,
  patchProp: (node, key, _previous, value) => { node.props[key] = value },
  insert: (node, parent) => { node.parent = parent; parent.children.push(node) },
  remove: node => { if (node.parent) node.parent.children.splice(node.parent.children.indexOf(node), 1) }
})
const all = (node: Node): Node[] => [node, ...node.children.flatMap(all)]
let app: ReturnType<typeof renderer.createApp>
let root: Node
let added: PracticeAnnotation[]
const clickTool = async (label: string) => {
  const button = all(root).find(node => node.tag === 'button' && node.text === label)
  if (!button) throw new Error(`Missing tool ${label}`)
  ;(button.props.onClick as () => void)()
  await nextTick()
}
const clickPoint = async (date = '2024-01-02') => {
  mocks.click?.({ time: date, point: { x: 100, y: 80 }, paneIndex: 0 } as MouseEventParams)
  await nextTick()
}
beforeEach(async () => {
  vi.clearAllMocks()
  mocks.frames.clear()
  vi.stubGlobal('window', {
    addEventListener: (type: string, handler: (event: KeyboardEvent) => void) => { if (type === 'keydown') mocks.keydown = handler },
    removeEventListener: mocks.removeListener
  })
  vi.stubGlobal('ResizeObserver', class { observe() {} disconnect() { mocks.disconnect() } })
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) => { const id = ++mocks.frame; mocks.frames.set(id, callback); return id })
  vi.stubGlobal('cancelAnimationFrame', (id: number) => mocks.frames.delete(id))
  root = new Node('root')
  added = []
  app = renderer.createApp(PracticeChart, {
    bars: [bar('2024-01-02')], currentDate: '2024-01-02', readonly: false, disabled: false,
    fills: [], annotations: [{ id: 'saved', type: 'BUY', points: [{ date: '2024-01-02', price: 10 }] }],
    onAddAnnotation: (annotation: PracticeAnnotation) => { added.push(annotation) }
  })
  app.mount(root)
  for (const callback of mocks.frames.values()) callback(0)
  mocks.frames.clear()
  await nextTick()
})
afterEach(() => { app.unmount(); vi.unstubAllGlobals() })

describe('pure practice chart tools', () => {
  it('renders only supplied visible bars and derives MA from them', () => {
    expect(mocks.setData.mock.calls[0][0]).toEqual([{ time: '2024-01-02', open: 10, high: 11, low: 9, close: 10 }])
    expect(mocks.setData.mock.calls[2][0]).toEqual([])
  })
  it('switches one-click BUY → two-click LINE → one-click SELL without mixing modes', async () => {
    await clickTool('手工买点'); await clickPoint()
    await clickTool('画线'); await clickPoint()
    expect(added.map(annotation => annotation.type)).toEqual(['BUY'])
    await clickPoint()
    await clickTool('手工卖点'); await clickPoint()
    expect(added.map(annotation => [annotation.type, annotation.points.length])).toEqual([['BUY', 1], ['LINE', 2], ['SELL', 1]])
    expect(all(root).filter(node => node.tag === 'circle').length).toBeGreaterThan(0)
  })
  it('Esc discards an unfinished line and exits while preserving supplied saved annotations', async () => {
    await clickTool('画线'); await clickPoint()
    mocks.keydown?.({ key: 'Escape' } as KeyboardEvent)
    await clickPoint()
    expect(added).toEqual([])
    expect(all(root).find(node => node.tag === 'button' && node.text === '浏览')?.props.class).toContain('selected')
    expect(all(root).some(node => node.tag === 'circle')).toBe(true)
  })
  it('ignores a future or absent date even when a tool is active', async () => {
    await clickTool('手工买点'); await clickPoint('2024-01-03'); await clickPoint('2024-01-01')
    expect(added).toEqual([])
  })
  it('releases its chart, marker plugin, resize observer and scheduled overlay work', () => {
    app.unmount()
    expect(mocks.detach).toHaveBeenCalledOnce()
    expect(mocks.remove).toHaveBeenCalledOnce()
    expect(mocks.disconnect).toHaveBeenCalledOnce()
    expect(mocks.removeListener).toHaveBeenCalledWith('keydown', mocks.keydown)
    expect(mocks.frames.size).toBe(0)
  })
})
