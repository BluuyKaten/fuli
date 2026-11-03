<template>
  <section class="practice-chart" aria-label="练习日线图">
    <div class="chart-toolbar">
      <div class="tools" role="group" aria-label="图表标注工具">
        <button :class="{ selected: mode === null }" @click="setMode(null)">浏览</button>
        <button v-for="tool in tools" :key="tool.type" :disabled="readonly || disabled || annotations.length >= 200"
          :class="{ selected: mode === tool.type }" :aria-pressed="mode === tool.type" @click="setMode(tool.type)">{{ tool.label }}</button>
        <span class="tool-divider"></span>
        <button :class="{ selected: showMA }" :aria-pressed="showMA" @click="showMA = !showMA">MA 5 / 20</button>
        <button @click="chart?.timeScale().fitContent()">适应窗口</button>
      </div>
      <span class="chart-legend"><i></i> 真实模拟成交 <span class="manual-legend"><em class="ring"></em> 手工标注</span></span>
    </div>
    <div class="ohlc" aria-live="off">
      <strong>{{ hovered?.date ?? '—' }}</strong>
      <span>开 <b>{{ price(hovered?.open) }}</b></span><span>高 <b>{{ price(hovered?.high) }}</b></span>
      <span>低 <b>{{ price(hovered?.low) }}</b></span><span>收 <b>{{ price(hovered?.close) }}</b></span>
      <span>量 <b>{{ hovered ? quantityText(hovered.volume) : '—' }}</b> 手</span>
    </div>
    <div ref="host" class="chart-host" :class="{ drawing: mode }" @pointermove="scheduleOverlay" @pointerup="scheduleOverlay" @wheel.passive="scheduleOverlay">
      <svg class="annotation-layer" :width="size.width" :height="size.height" aria-hidden="true">
        <g v-if="cutoffX !== null">
          <line :x1="cutoffX" y1="0" :x2="cutoffX" :y2="size.height - 28" stroke="#d29922" stroke-dasharray="4 5" opacity="0.8" />
          <rect :x="Math.max(0, cutoffX - 74)" y="3" width="148" height="22" rx="3" fill="#30291a" />
          <text :x="Math.max(74, cutoffX)" y="18" fill="#e3b341" text-anchor="middle" font-size="11">{{ readonly ? '实际结束日 · 指标冻结' : '当前决策日' }}</text>
        </g>
        <g v-for="drawing in projected" :key="drawing.id">
          <template v-if="drawing.type === 'LINE' && drawing.points[0] && drawing.points[1]">
            <line :x1="drawing.points[0].x" :y1="drawing.points[0].y" :x2="drawing.points[1].x" :y2="drawing.points[1].y" stroke="#a5a2f3" stroke-width="2" />
            <circle v-for="(point, index) in drawing.points" :key="index" :cx="point.x" :cy="point.y" r="3" fill="#a5a2f3" />
          </template>
          <g v-else-if="drawing.points[0]" :transform="`translate(${drawing.points[0].x}, ${drawing.points[0].y})`">
            <circle r="10" :stroke="drawing.type === 'BUY' ? '#ef5350' : '#26a69a'" fill="#161b22" stroke-width="2" />
            <text text-anchor="middle" dominant-baseline="central" :fill="drawing.type === 'BUY' ? '#ef5350' : '#26a69a'" font-size="10">{{ drawing.type === 'BUY' ? '买' : '卖' }}</text>
          </g>
        </g>
        <circle v-if="draftProjection" :cx="draftProjection.x" :cy="draftProjection.y" r="5" fill="#a5a2f3" stroke="#fff" />
      </svg>
    </div>
    <div class="chart-footnote" role="status">
      <span v-if="mode === 'LINE'">{{ firstPoint ? '已选起点，再点击一个已揭晓位置完成画线。' : '在已揭晓日线上点击两次画线。' }} Esc 退出。</span>
      <span v-else-if="mode">点击已揭晓日线添加{{ mode === 'BUY' ? '买' : '卖' }}点；仅作笔记，不会提交委托。Esc 退出。</span>
      <span v-else-if="readonly">完整走势已揭晓；虚线之后仅供复盘，收益与权益曲线截至实际结束日。</span>
      <span v-else>日线 · 未复权 · 只显示已揭晓行情；拖动浏览历史不会改变决策日。</span>
      <span>{{ annotations.length }} / 200 条标注{{ readonly ? ' · 只读' : '' }}</span>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { CandlestickSeries, ColorType, HistogramSeries, LineSeries, createChart, createSeriesMarkers,
  type IChartApi, type ISeriesApi, type ISeriesMarkersPluginApi, type MouseEventParams, type Time } from 'lightweight-charts'
import type { PracticeAnnotation, PracticeAnnotationPoint, PracticeBar, PracticeFill } from '@/api/practice'
import { annotationAtVisiblePoint, movingAverage, quantityText } from '../practiceData'

const props = defineProps<{ bars: PracticeBar[]; fills: PracticeFill[]; annotations: PracticeAnnotation[]; currentDate: string; readonly: boolean; disabled: boolean }>()
const emit = defineEmits<{ addAnnotation: [annotation: PracticeAnnotation] }>()
const host = ref<HTMLDivElement | null>(null)
const mode = ref<PracticeAnnotation['type'] | null>(null)
const firstPoint = ref<PracticeAnnotationPoint | null>(null)
const showMA = ref(true)
const hovered = ref<PracticeBar | null>(null)
const size = ref({ width: 0, height: 400 })
const overlayTick = ref(0)
const tools = [{ type: 'LINE' as const, label: '画线' }, { type: 'BUY' as const, label: '手工买点' }, { type: 'SELL' as const, label: '手工卖点' }]
let chart: IChartApi | undefined
let candles: ISeriesApi<'Candlestick'> | undefined
let volume: ISeriesApi<'Histogram'> | undefined
let ma5: ISeriesApi<'Line'> | undefined
let ma20: ISeriesApi<'Line'> | undefined
let markers: ISeriesMarkersPluginApi<Time> | undefined
let observer: ResizeObserver | undefined
let frame = 0
let lastDate: string | undefined
const price = (value?: number) => value?.toFixed(2) ?? '—'
const timeDate = (time: Time): string | null => typeof time === 'string' ? time : typeof time === 'object'
  ? `${time.year}-${String(time.month).padStart(2, '0')}-${String(time.day).padStart(2, '0')}` : null
const project = (point: PracticeAnnotationPoint) => {
  const x = chart?.timeScale().timeToCoordinate(point.date)
  const y = candles?.priceToCoordinate(point.price)
  return x != null && y != null ? { x, y } : null
}
const projected = computed(() => {
  void overlayTick.value
  return props.annotations.flatMap(annotation => {
    const points = annotation.points.map(project)
    return points.every(point => point !== null) ? [{ ...annotation, points: points as { x: number; y: number }[] }] : []
  })
})
const cutoffX = computed(() => { void overlayTick.value; return chart?.timeScale().timeToCoordinate(props.currentDate) ?? null })
const draftProjection = computed(() => { void overlayTick.value; return firstPoint.value ? project(firstPoint.value) : null })
function scheduleOverlay() {
  if (!frame) frame = requestAnimationFrame(() => { frame = 0; overlayTick.value++ })
}
function setMode(next: PracticeAnnotation['type'] | null) {
  if (next && (props.readonly || props.disabled)) return
  mode.value = next === mode.value ? null : next
  firstPoint.value = null
  chart?.applyOptions({ handleScroll: !mode.value, handleScale: !mode.value })
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape') { setMode(null); scheduleOverlay() }
}
function click(event: MouseEventParams) {
  if (!mode.value || props.readonly || props.disabled || !event.time || !event.point || (event.paneIndex ?? 0) !== 0) return
  const date = timeDate(event.time)
  const value = candles?.coordinateToPrice(event.point.y)
  if (!date || value == null || value <= 0 || date > props.currentDate || !props.bars.some(bar => bar.date === date)) return
  const point = { date, price: Math.round(value * 10000) / 10000 }
  if (mode.value === 'LINE' && !firstPoint.value) { firstPoint.value = point; scheduleOverlay(); return }
  const annotation: PracticeAnnotation = {
    id: globalThis.crypto?.randomUUID?.() ?? `note-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    type: mode.value, points: firstPoint.value && mode.value === 'LINE' ? [firstPoint.value, point] : [point]
  }
  if (annotationAtVisiblePoint(annotation, props.bars, props.currentDate)) emit('addAnnotation', annotation)
  firstPoint.value = null
  scheduleOverlay()
}
function crosshair(event: MouseEventParams) {
  const date = event.time ? timeDate(event.time) : null
  hovered.value = props.bars.find(bar => bar.date === date) ?? props.bars.at(-1) ?? null
  scheduleOverlay()
}
function syncMA() {
  ma5?.setData(showMA.value ? movingAverage(props.bars, 5) : [])
  ma20?.setData(showMA.value ? movingAverage(props.bars, 20) : [])
}
function syncBars() {
  if (!chart || !candles || !volume) return
  const previous = lastDate
  const range = chart.timeScale().getVisibleLogicalRange()
  const atRight = !range || range.to >= props.bars.length - 3
  candles.setData(props.bars.map(bar => ({ time: bar.date, open: bar.open, high: bar.high, low: bar.low, close: bar.close })))
  volume.setData(props.bars.map(bar => ({ time: bar.date, value: bar.volume, color: bar.close >= bar.open ? '#ef535055' : '#26a69a55' })))
  syncMA()
  lastDate = props.bars.at(-1)?.date
  hovered.value = props.bars.at(-1) ?? null
  if (!previous) chart.timeScale().fitContent()
  else if (previous !== lastDate && atRight) chart.timeScale().scrollToRealTime()
  scheduleOverlay()
}
function syncFills() {
  const dates = new Set(props.bars.map(bar => bar.date))
  markers?.setMarkers(props.fills.filter(fill => dates.has(fill.date)).map(fill => ({
    time: fill.date, position: fill.side === 'BUY' ? 'belowBar' as const : 'aboveBar' as const,
    color: fill.side === 'BUY' ? '#ef5350' : '#26a69a', shape: fill.side === 'BUY' ? 'arrowUp' as const : 'arrowDown' as const,
    text: `${fill.side === 'BUY' ? '成交买' : '成交卖'} ${fill.quantity} @${fill.price.toFixed(2)}`
  })).sort((a, b) => a.time.localeCompare(b.time)))
}
watch(() => props.bars, () => { syncBars(); syncFills() })
watch(() => props.fills, syncFills)
watch(() => props.annotations, scheduleOverlay)
watch(showMA, syncMA)
watch(() => [props.readonly, props.disabled], () => { if (props.readonly || props.disabled) setMode(null) })
onMounted(() => {
  if (!host.value) return
  chart = createChart(host.value, {
    width: host.value.clientWidth, height: host.value.clientHeight,
    layout: { background: { type: ColorType.Solid, color: '#0d1117' }, textColor: '#8b949e', fontFamily: 'Consolas, monospace', attributionLogo: true },
    grid: { vertLines: { color: '#1c2128' }, horzLines: { color: '#1c2128' } },
    rightPriceScale: { borderColor: '#30363d' }, timeScale: { borderColor: '#30363d', rightOffset: 5, timeVisible: false },
    localization: { locale: 'zh-CN' }, crosshair: { mode: 0 }
  })
  candles = chart.addSeries(CandlestickSeries, { upColor: '#ef5350', downColor: '#26a69a', borderVisible: false, wickUpColor: '#ef5350', wickDownColor: '#26a69a' })
  ma5 = chart.addSeries(LineSeries, { color: '#d29922', lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false })
  ma20 = chart.addSeries(LineSeries, { color: '#a5a2f3', lineWidth: 1, priceLineVisible: false, lastValueVisible: false, crosshairMarkerVisible: false })
  volume = chart.addSeries(HistogramSeries, { priceFormat: { type: 'volume' }, priceLineVisible: false, lastValueVisible: false }, 1)
  chart.panes()[1]?.setHeight(74)
  markers = createSeriesMarkers(candles, [])
  chart.subscribeClick(click)
  chart.subscribeCrosshairMove(crosshair)
  chart.timeScale().subscribeVisibleLogicalRangeChange(scheduleOverlay)
  observer = new ResizeObserver(entries => {
    const rect = entries[0]?.contentRect
    if (!rect || !chart) return
    size.value = { width: rect.width, height: rect.height }
    chart.resize(rect.width, rect.height)
    scheduleOverlay()
  })
  observer.observe(host.value)
  size.value = { width: host.value.clientWidth, height: host.value.clientHeight }
  window.addEventListener('keydown', keydown)
  syncBars()
  syncFills()
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', keydown)
  observer?.disconnect()
  if (frame) cancelAnimationFrame(frame)
  chart?.unsubscribeClick(click)
  chart?.unsubscribeCrosshairMove(crosshair)
  chart?.timeScale().unsubscribeVisibleLogicalRangeChange(scheduleOverlay)
  markers?.detach()
  chart?.remove()
  chart = undefined
})
</script>

<style scoped>
.practice-chart { min-width: 0; }
.chart-toolbar { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px; padding: 10px 12px; border-bottom: 1px solid var(--term-border); }
.tools { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
.tools button { border: 1px solid transparent; border-radius: 3px; color: var(--term-fg-muted); background: transparent; font: inherit; font-size: 12px; padding: 5px 8px; cursor: pointer; }
.tools button:hover { background: var(--term-hover); color: var(--term-fg); }
.tools button.selected { border-color: #58a6ff66; background: #1f6feb22; color: var(--term-accent); }
.tools button:focus-visible { outline: 2px solid var(--term-accent); outline-offset: 2px; }
.tools button:disabled { opacity: .35; cursor: not-allowed; }
.tool-divider { height: 16px; border-left: 1px solid var(--term-border); margin: 0 5px; }
.chart-legend { color: var(--term-fg-muted); font-size: 10px; }
.chart-legend i { display: inline-block; width: 0; height: 0; border-left: 4px solid transparent; border-right: 4px solid transparent; border-bottom: 7px solid var(--term-up); }
.manual-legend { margin-left: 10px; display: inline-flex; align-items: center; gap: 4px; }
.manual-legend .ring { display: inline-block; width: 7px; height: 7px; border-radius: 50%; border: 1px solid var(--term-warning); }
.ohlc { display: flex; flex-wrap: wrap; gap: 14px; padding: 8px 14px; min-height: 34px; font-size: 11px; color: var(--term-fg-muted); font-variant-numeric: tabular-nums; }
.ohlc b, .ohlc strong { font-weight: 500; color: var(--term-fg); }
.chart-host { position: relative; height: 430px; }
.chart-host.drawing { cursor: crosshair; }
.annotation-layer { position: absolute; top: 0; left: 0; z-index: 3; pointer-events: none; overflow: hidden; }
.chart-footnote { border-top: 1px solid var(--term-border); display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 9px 14px; font-size: 11px; color: var(--term-fg-muted); line-height: 1.7; }
@media (max-width: 900px) { .chart-host { height: 360px; } .chart-legend { display: none; } .ohlc { gap: 9px; } }
</style>
