<template>
  <div class="lw-chart">
    <div class="chart-toolbar">
      <div class="period-group">
        <button
          v-for="p in periods"
          :key="p.value"
          :class="['period-btn', { active: currentPeriod === p.value }]"
          @click="changePeriod(p.value)"
        >{{ p.label }}</button>
      </div>
    </div>
    <div ref="chartContainer" class="chart-container" />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { createChart, CrosshairMode, ColorType, LineStyle, CandlestickSeries, HistogramSeries } from 'lightweight-charts'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/stores/user'
import {
  getStockMinuteData,
  getStockWeeklyData,
  getStockMonthlyData,
  getStockDailyData,
  loadDrawing
} from '@/api/kline'
import { toTushareCode } from '@/utils/stockCode'

const props = defineProps<{
  stockCode: string  // 纯数字 300750
}>()

const emit = defineEmits<{
  (e: 'price-change', price: string): void
}>()

// 获取当前用户 ID
const getCurrentUserId = () => {
  if (typeof window !== 'undefined') {
    const store = useUserStore()
    if (store.userId) return store.userId
  }
  return Number(localStorage.getItem('userId') || '0')
}

// 周期选项
const periods = [
  { label: '1分', value: '1' },
  { label: '5分', value: '5' },
  { label: '15分', value: '15' },
  { label: '60分', value: '60' },
  { label: '日线', value: '1D' },
  { label: '周线', value: '1W' },
  { label: '月线', value: '1M' }
]
const currentPeriod = ref('1D')

// 指标开关
const indicators = reactive({
  volume: true,
  macd: true,
  kdj: false
})


// 图表相关
const chartContainer = ref<HTMLElement | null>(null)
let chart: any = null
let candleSeries: any = null
let volumeSeries: any = null


// 画线数据
interface DrawingPoint {
  x: number
  y: number
  time?: number
  price?: number
}

interface DrawingObject {
  id: string
  type: string
  points: DrawingPoint[]
  color: string
  lineWidth: number
}

const drawings = ref<DrawingObject[]>([])
const currentDrawing = ref<DrawingObject | null>(null)
const drawingCanvas = ref<HTMLCanvasElement | null>(null)
let canvasCtx: CanvasRenderingContext2D | null = null

// 初始化画布
const initCanvas = () => {
  if (!drawingCanvas.value || !chartContainer.value) return
  const rect = chartContainer.value.getBoundingClientRect()
  drawingCanvas.value.width = rect.width
  drawingCanvas.value.height = rect.height
  canvasCtx = drawingCanvas.value.getContext('2d')
  redrawDrawings()
}

// 重绘所有画线
const redrawDrawings = () => {
  if (!canvasCtx || !drawingCanvas.value) return
  canvasCtx.clearRect(0, 0, drawingCanvas.value.width, drawingCanvas.value.height)

  const all = currentDrawing.value ? [...drawings.value, currentDrawing.value] : drawings.value
  for (const d of all) {
    drawObject(d)
  }
}

// 绘制单个对象
const drawObject = (obj: DrawingObject) => {
  if (!canvasCtx || obj.points.length < 1) return
  const ctx = canvasCtx
  ctx.strokeStyle = obj.color
  ctx.lineWidth = obj.lineWidth
  ctx.beginPath()

  if (obj.type === 'trend' && obj.points.length >= 2) {
    ctx.moveTo(obj.points[0].x, obj.points[0].y)
    ctx.lineTo(obj.points[1].x, obj.points[1].y)
  } else if (obj.type === 'horizontal') {
    const y = obj.points[0].y
    ctx.moveTo(0, y)
    ctx.lineTo(drawingCanvas.value!.width, y)
    // 标注价格
    ctx.fillStyle = obj.color
    ctx.font = '11px monospace'
    ctx.fillText(obj.points[0].price?.toFixed(2) || '', 10, y - 4)
  } else if (obj.type === 'rectangle' && obj.points.length >= 2) {
    const w = obj.points[1].x - obj.points[0].x
    const h = obj.points[1].y - obj.points[0].y
    ctx.rect(obj.points[0].x, obj.points[0].y, w, h)
  } else if (obj.type === 'fibonacci' && obj.points.length >= 2) {
    const x1 = obj.points[0].x
    const x2 = obj.points[1].x
    const y1 = obj.points[0].y
    const y2 = obj.points[1].y
    const diff = y2 - y1
    const levels = [0, 0.236, 0.382, 0.5, 0.618, 1]
    for (const level of levels) {
      const y = y1 + diff * level
      ctx.moveTo(Math.min(x1, x2), y)
      ctx.lineTo(Math.max(x1, x2), y)
      ctx.fillStyle = obj.color
      ctx.font = '10px monospace'
      ctx.fillText(`${(level * 100).toFixed(1)}%`, Math.max(x1, x2) + 4, y + 3)
    }
  }
  ctx.stroke()
}


// 加载画线
const loadDrawings = async () => {
  if (!props.stockCode) return
  try {
    const userId = getCurrentUserId()
    const res = await loadDrawing(userId, props.stockCode, currentPeriod.value)
    if (res.code === 200 && res.data?.data) {
      drawings.value = JSON.parse(res.data.data || '[]')
      redrawDrawings()
    }
  } catch (e) {
    // 忽略加载错误
  }
}

// 初始化图表
const initChart = () => {
  if (!chartContainer.value) return

  chart = createChart(chartContainer.value, {
    layout: {
      background: { type: ColorType.Solid, color: '#161b22' },
      textColor: '#8b949e'
    },
    grid: {
      vertLines: { color: '#30363d' },
      horzLines: { color: '#30363d' }
    },
    crosshair: {
      mode: CrosshairMode.Normal,
      vertLine: { color: '#58a6ff', width: 1, style: LineStyle.Dashed },
      horzLine: { color: '#58a6ff', width: 1, style: LineStyle.Dashed }
    },
    rightPriceScale: { borderColor: '#30363d' },
    timeScale: {
      borderColor: '#30363d',
      timeVisible: true,
      secondsVisible: false
    },
    handleScroll: { vertTouchDrag: false }
  })

  candleSeries = chart.addSeries(CandlestickSeries, {
    upColor: '#ef5350',
    downColor: '#26a69a',
    borderUpColor: '#ef5350',
    borderDownColor: '#26a69a',
    wickUpColor: '#ef5350',
    wickDownColor: '#26a69a'
  })

  if (indicators.volume) {
    volumeSeries = chart.addSeries(HistogramSeries, {
      color: '#26a69a',
      priceFormat: { type: 'volume' },
      priceScaleId: '',
      scaleMargins: { top: 0.8, bottom: 0 }
    })
  }

  const resizeObserver = new ResizeObserver(entries => {
    if (entries.length === 0 || !entries[0].contentRect) return
    const { width, height } = entries[0].contentRect
    chart.applyOptions({ width, height })
    // 同步调整画布大小
    if (drawingCanvas.value) {
      drawingCanvas.value.width = width
      drawingCanvas.value.height = height
      redrawDrawings()
    }
  })
  resizeObserver.observe(chartContainer.value)
}

onMounted(() => {
  if (!chartContainer.value) return
  initChart()
  initCanvas()
  loadData()
  loadDrawings()
})

onBeforeUnmount(() => {
  if (chart) {
    chart.remove()
    chart = null
  }
})

// 加载 K 线数据
const loadData = async () => {
  if (!props.stockCode) return
  try {
    let data: any[] = []
    const period = currentPeriod.value
    // 统一转 Tushare 格式（300750 → 300750.SZ）
    const tushareCode = toTushareCode(props.stockCode)

    if (period === '1D') {
      // 日线（现有接口）
      const res = await getStockDailyData(tushareCode)
      data = res.data || []
    } else if (['1', '5', '15', '60'].includes(period)) {
      // 分钟线
      const res = await getStockMinuteData(props.stockCode, parseInt(period))
      data = res.data || []
    } else if (period === '1W') {
      const res = await getStockWeeklyData(props.stockCode)
      data = res.data || []
    } else if (period === '1M') {
      const res = await getStockMonthlyData(props.stockCode)
      data = res.data || []
    }

// 解析时间（返回 Lightweight Charts 格式）
// 日线 → 'YYYY-MM-DD' 字符串；分钟线 → Unix 秒
const parseTime = (t: any, isDaily: boolean): string | number => {
  if (!t) {
    const now = new Date()
    return isDaily ? now.toISOString().slice(0, 10) : Math.floor(now.getTime() / 1000)
  }
  const s = String(t).trim()
  // 8位数字格式：20200529 → 2020-05-29
  if (/^\d{8}$/.test(s)) {
    const formatted = `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`
    return isDaily ? formatted : Math.floor(new Date(formatted + 'T00:00:00+08:00').getTime() / 1000)
  }
  // 已经是 YYYY-MM-DD 格式
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) {
    return isDaily ? s : Math.floor(new Date(s + 'T00:00:00+08:00').getTime() / 1000)
  }
  // 带时间格式 2024-01-02 10:30
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) {
    const ts = new Date(s.replace(' ', 'T') + '+08:00').getTime()
    return isDaily ? s.slice(0, 10) : Math.floor(ts / 1000)
  }
  // 数字时间戳
  const n = Number(s)
  if (!isNaN(n)) {
    return isDaily ? new Date(n > 1e12 ? n : n * 1000).toISOString().slice(0, 10) : (n > 1e12 ? Math.floor(n / 1000) : n)
  }
  return isDaily ? s : Math.floor(Date.now() / 1000)
}

// 转换为 Lightweight Charts 格式
const isDaily = ['1D', '1W', '1M'].includes(currentPeriod.value)
const candles = data.map((d: any) => ({
  time: parseTime(d.time || d.tradeDate, isDaily) as any,
  open: Number(d.open ?? d.openPrice ?? 0),
  high: Number(d.high ?? d.highPrice ?? 0),
  low: Number(d.low ?? d.lowPrice ?? 0),
  close: Number(d.close ?? d.closePrice ?? 0)
}))

const volumes = data.map((d: any) => ({
  time: parseTime(d.time || d.tradeDate, isDaily) as any,
  value: Number(d.volume ?? d.vol ?? 0),
  color: Number(d.close ?? d.closePrice ?? 0) >= Number(d.open ?? d.openPrice ?? 0) ? '#ef535080' : '#26a69a80'
}))

    candleSeries.setData(candles)
    if (volumeSeries) volumeSeries.setData(volumes)

    // 最新价通知
    if (candles.length > 0) {
      emit('price-change', candles[candles.length - 1].close.toString())
    }
  } catch (e: any) {
    message.error(`加载K线数据失败: ${e.message}`)
  }
}

// 切换周期
const changePeriod = (period: string) => {
  currentPeriod.value = period
  loadData()
  loadDrawings()
}

// 监听股票变化
watch(() => props.stockCode, () => {
  if (props.stockCode) {
    currentPeriod.value = '1D'
    loadData()
    loadDrawings()
  }
})
</script>

<style scoped>
.lw-chart {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-height: 100%;
  background: var(--term-panel-bg);
  border-radius: 4px;
  overflow: hidden;
  position: relative;
  box-sizing: border-box;
}

.chart-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: var(--term-panel-header);
  border-bottom: 1px solid var(--term-border);
}

.period-group {
  display: flex;
  gap: 2px;
}

.period-btn {
  padding: 4px 10px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 3px;
  color: var(--term-fg-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.period-btn:hover {
  background: var(--term-hover);
  color: var(--term-fg);
}

.period-btn.active {
  background: var(--term-active);
  color: var(--term-accent);
  border-color: var(--term-accent);
}

.indicator-group {
  display: flex;
  gap: 6px;
  align-items: center;
}

.chart-wrapper {
  flex: 1;
  position: relative;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.chart-container {
  width: 100%;
  height: 100%;
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
}

.drawing-canvas {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  z-index: 5;
}

.drawing-canvas.active {
  pointer-events: auto;
  cursor: crosshair;
}

.drawing-toolbar {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  background: var(--term-panel-header);
  border-top: 1px solid var(--term-border);
}

.draw-btn {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 3px;
  color: var(--term-fg-muted);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s;
}

.draw-btn:hover {
  background: var(--term-hover);
  color: var(--term-fg);
}

.draw-btn.active {
  background: var(--term-active);
  color: var(--term-accent);
  border-color: var(--term-accent);
}

/* 指标选择弹窗 */
.indicator-pop {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 4px 0;
  min-width: 100px;
}

.ind-item {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 12px;
  color: #c9d1d9;
}

.ind-item:hover {
  color: #58a6ff;
}
</style>
