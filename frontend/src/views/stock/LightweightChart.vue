<template>
  <div class="lw-chart">
    <!-- 顶部工具栏 -->
    <div class="chart-toolbar">
      <!-- 周期按钮组 -->
      <div class="period-group">
        <button
          v-for="p in periods"
          :key="p.value"
          :class="['period-btn', { active: currentPeriod === p.value }]"
          @click="changePeriod(p.value)"
        >{{ p.label }}</button>
      </div>

      <!-- 指标选择器 -->
      <div class="indicator-group">
        <label v-for="ind in indicatorList" :key="ind.key" class="indicator-label">
          <input
            type="checkbox"
            :checked="activeIndicators[ind.key]"
            @change="toggleIndicator(ind.key)"
          />
          <span>{{ ind.label }}</span>
        </label>
      </div>

      <!-- 画线工具栏 -->
      <div class="drawing-toolbar">
        <button
          v-for="tool in drawingTools"
          :key="tool.type"
          :class="['draw-btn', { active: currentTool === tool.type }]"
          @click="startDrawTool(tool.type)"
          :title="tool.label"
        >{{ tool.label }}</button>
        <button
          class="draw-btn"
          :disabled="!selectedId"
          @click="selectedId && deleteDrawing(selectedId)"
          title="删除选中画线 (Del)"
        >删除</button>
        <button
          class="draw-btn"
          :disabled="!drawings.length"
          @click="clearAllDrawings()"
          title="清空当前股票所有画线"
        >清空</button>
      </div>

      <!-- 买卖点标记模式 -->
      <div class="marker-group">
        <button
          :class="['marker-btn', { active: markerMode === '' }]"
          @click="setMarkerMode('')"
        >标记关</button>
        <button
          :class="['marker-btn', { active: markerMode === 'buy' }]"
          @click="setMarkerMode('buy')"
        >买入标记</button>
        <button
          :class="['marker-btn', { active: markerMode === 'sell' }]"
          @click="setMarkerMode('sell')"
        >卖出标记</button>
      </div>

      <!-- 主题切换 -->
      <button class="toolbar-icon-btn" @click="toggleTheme" :title="currentThemeName === 'dark' ? '切换亮色' : '切换暗色'">
        <AppIcon :name="currentThemeName === 'dark' ? 'sun' : 'moon'" :size="14" />
      </button>

      <!-- 导出 -->
      <div class="export-group">
        <button class="toolbar-icon-btn" @click="handleExportPNG" title="导出PNG">PNG</button>
        <button class="toolbar-icon-btn" @click="handleExportCSV" title="导出CSV">CSV</button>
      </div>

      <!-- 股票信息标签 -->
      <span v-if="stockInfo" class="stock-label">
        <span class="stock-name">{{ stockInfo.stockName }}</span>
        <span class="stock-code">({{ stockInfo.stockCode }})</span>
        <span class="stock-price" :style="{ color: priceColor }">¥{{ currentPrice }}</span>
      </span>
    </div>

    <!-- 图表区（含 canvas 叠加层 + 信息面板） -->
    <div
      class="chart-wrapper"
      :class="{ 'tool-active': currentTool || markerMode, 'select-mode': isSelectMode }"
      @mousedown.capture="onCanvasMouseDown"
      @mousemove.capture="onCanvasMouseMove"
      @mouseup.capture="onCanvasMouseUp"
      @mouseleave="onCanvasMouseUp"
    >
      <div ref="chartContainer" class="chart-container" />
      <canvas ref="drawingCanvas" class="drawing-canvas" />
      <!-- 信息面板（内联简单展示，Task 13 将替换为 InfoPanel 组件） -->
      <div v-if="panelData" class="info-panel">
        <div class="info-row">
          <span class="info-date">{{ panelData.date }}</span>
          <span
            class="info-change"
            :class="{ up: panelData.change >= 0, down: panelData.change < 0 }"
          >{{ panelData.change >= 0 ? '+' : '' }}{{ panelData.change }}%</span>
        </div>
        <div class="info-grid">
          <span>开 {{ panelData.open }}</span>
          <span>高 {{ panelData.high }}</span>
          <span>低 {{ panelData.low }}</span>
          <span>收 {{ panelData.close }}</span>
          <span>量 {{ panelData.volume }}</span>
        </div>
        <div v-if="hasIndicatorData" class="info-indicators">
          <span v-if="panelData.ma5 !== null">MA5: {{ formatNum(panelData.ma5) }}</span>
          <span v-if="panelData.ma10 !== null">MA10: {{ formatNum(panelData.ma10) }}</span>
          <span v-if="panelData.ma20 !== null">MA20: {{ formatNum(panelData.ma20) }}</span>
          <span v-if="panelData.ma60 !== null">MA60: {{ formatNum(panelData.ma60) }}</span>
          <span v-if="panelData.macdDif !== null">DIF: {{ formatNum(panelData.macdDif) }}</span>
          <span v-if="panelData.macdDea !== null">DEA: {{ formatNum(panelData.macdDea) }}</span>
          <span v-if="panelData.macdHist !== null">MACD: {{ formatNum(panelData.macdHist) }}</span>
          <span v-if="panelData.kdjK !== null">K: {{ formatNum(panelData.kdjK) }}</span>
          <span v-if="panelData.kdjD !== null">D: {{ formatNum(panelData.kdjD) }}</span>
          <span v-if="panelData.kdjJ !== null">J: {{ formatNum(panelData.kdjJ) }}</span>
          <span v-if="panelData.rsi !== null">RSI: {{ formatNum(panelData.rsi) }}</span>
          <span v-if="panelData.bollUpper !== null">BOLL上: {{ formatNum(panelData.bollUpper) }}</span>
          <span v-if="panelData.bollMiddle !== null">BOLL中: {{ formatNum(panelData.bollMiddle) }}</span>
          <span v-if="panelData.bollLower !== null">BOLL下: {{ formatNum(panelData.bollLower) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch, computed, nextTick } from 'vue'
import type { ISeriesApi, Time, UTCTimestamp } from 'lightweight-charts'
import { message } from 'ant-design-vue'
import {
  getStockMinuteData,
  getStockWeeklyData,
  getStockMonthlyData,
  getStockDailyData,
  saveDrawing
} from '@/api/kline'
import { toTushareCode } from '@/utils/stockCode'
import AppIcon from '@/components/ui/AppIcon.vue'
import type { CandleItem } from '@/types/indicators'
import type { ChartTheme } from '@/types/chart'
import { useChartCore } from '@/composables/useChartCore'
import { useChartTheme } from '@/composables/useChartTheme'
import { useIndicators, type IndicatorConfig } from '@/composables/useIndicators'
import { useTradeMarkers } from '@/composables/useTradeMarkers'
import { useDrawingTools, type DrawingType } from '@/composables/useDrawingTools'
import { useChartExport } from '@/composables/useChartExport'
import { useInfoPanel } from '@/composables/useInfoPanel'

// --- 组件 props / emits ---
const props = defineProps<{
  stockCode: string // 纯数字 300750
}>()

const emit = defineEmits<{
  (e: 'price-change', price: string): void
}>()

// --- 组件 props / emits ---（后端返回，字段名可能不同） ---
interface RawKlineItem {
  time?: string | number
  tradeDate?: string | number
  open?: number
  openPrice?: number
  high?: number
  highPrice?: number
  low?: number
  lowPrice?: number
  close?: number
  closePrice?: number
  volume?: number
  vol?: number
}

// --- 成交量数据格式 ---
interface VolumeItem {
  time: Time
  value: number
  color: string
}

// --- DOM refs ---
const chartContainer = ref<HTMLElement | null>(null)
const drawingCanvas = ref<HTMLCanvasElement | null>(null)

// --- ResizeObserver ref（用于组件卸载时 disconnect） ---
const resizeObserver = ref<ResizeObserver | null>(null)

// --- 集成所有 composables ---
const chartCore = useChartCore(chartContainer)
const { chart, initChart, applyTheme, addCandlestickSeries, addHistogramSeries, destroyChart } = chartCore

const { currentTheme, currentThemeName, toggleTheme: toggleThemeBase } = useChartTheme()
const { activeIndicators, updateIndicators, saveIndicators, clearAllIndicators } = useIndicators(chart, currentTheme)

// candleSeries ref：用于传递给 useTradeMarkers
const candleSeries = ref<ISeriesApi<'Candlestick'> | null>(null)
const { loadAutoMarkers, addManualMarker, disposeMarkers } = useTradeMarkers(candleSeries)

const {
  currentTool, initCanvas, startDraw, cancelDraw, addPoint, updateMousePosition,
  selectDrawing, startDrag, endDrag, isDragging, drawings, selectedId, deleteDrawing, clearDrawings,
  loadDrawings, redraw, subscribeChartMove, disposeDrawingTools
} = useDrawingTools(chart, drawingCanvas, candleSeries)

const { exporting, exportPNG, exportCSV } = useChartExport(chartContainer)

// K 线数据 ref：用于传递给 useInfoPanel
const dailyData = ref<CandleItem[]>([])
const { panelData, subscribeCrosshair, unsubscribeCrosshair } = useInfoPanel(
  chart,
  dailyData,
  activeIndicators
)

// Tushare 代码（用于画线保存/加载，与数据库格式一致）
const tushareCode = computed(() => toTushareCode(props.stockCode))

// --- 周期选项 ---
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

// --- 指标列表 ---
const indicatorList: { key: keyof IndicatorConfig; label: string }[] = [
  { key: 'ma5', label: 'MA5' },
  { key: 'ma10', label: 'MA10' },
  { key: 'ma20', label: 'MA20' },
  { key: 'ma60', label: 'MA60' },
  { key: 'macd', label: 'MACD' },
  { key: 'kdj', label: 'KDJ' },
  { key: 'rsi', label: 'RSI' },
  { key: 'boll', label: 'BOLL' }
]

// --- 画线工具列表 ---
const drawingTools = [
  { type: 'select' as DrawingType, label: '选择' },
  { type: 'trend' as DrawingType, label: '趋势线' },
  { type: 'horizontal' as DrawingType, label: '水平线' },
  { type: 'ray' as DrawingType, label: '射线' },
  { type: 'vertical' as DrawingType, label: '垂直线' },
  { type: 'rectangle' as DrawingType, label: '矩形' },
  { type: 'fibonacci' as DrawingType, label: '斐波那契' },
  { type: 'arrow' as DrawingType, label: '箭头' },
  { type: 'channel' as DrawingType, label: '通道线' },
  { type: 'triangle' as DrawingType, label: '三角形' },
  { type: 'text' as DrawingType, label: '文字' }
]

// 当前工具是否为"选择"模式（非画线）
const isSelectMode = computed(() => currentTool.value === 'select')

// --- 买卖点标记模式 ---
const markerMode = ref<'' | 'buy' | 'sell'>('')

// --- 股票信息（从本地存储读取） ---
const stockInfo = ref<{ stockName: string; stockCode: string } | null>(null)

// --- 当前价格 / 价格颜色 ---
const currentPrice = ref('0.00')
const priceColor = ref('#8b949e')

// --- 信息面板辅助 ---
const hasIndicatorData = computed(() => {
  if (!panelData.value) return false
  const d = panelData.value
  return d.ma5 !== null || d.ma10 !== null || d.ma20 !== null || d.ma60 !== null ||
    d.macdDif !== null || d.kdjK !== null || d.rsi !== null || d.bollUpper !== null
})

const formatNum = (n: number | null | undefined): string => {
  if (n === null || n === undefined || isNaN(n)) return '-'
  return n.toFixed(2)
}

// --- 时间解析（返回 Lightweight Charts 格式） ---
const parseTime = (t: string | number | undefined | null, isDaily: boolean): Time => {
  if (!t && t !== 0) {
    const now = new Date()
    return isDaily ? now.toISOString().slice(0, 10) : Math.floor(now.getTime() / 1000) as UTCTimestamp
  }
  const s = String(t).trim()
  // 8位数字格式：20200529 → 2020-05-29
  if (/^\d{8}$/.test(s)) {
    const formatted = `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`
    return isDaily ? formatted : Math.floor(new Date(formatted + 'T00:00:00+08:00').getTime() / 1000) as UTCTimestamp
  }
  // 已经是 YYYY-MM-DD 格式
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) {
    return isDaily ? s : Math.floor(new Date(s + 'T00:00:00+08:00').getTime() / 1000) as UTCTimestamp
  }
  // 带时间格式 2024-01-02 10:30
  if (/^\d{4}-\d{2}-\d{2}/.test(s)) {
    const ts = new Date(s.replace(' ', 'T') + '+08:00').getTime()
    return isDaily ? s.slice(0, 10) : Math.floor(ts / 1000) as UTCTimestamp
  }
  // 数字时间戳
  const n = Number(s)
  if (!isNaN(n)) {
    return isDaily ? new Date(n > 1e12 ? n : n * 1000).toISOString().slice(0, 10)
      : (n > 1e12 ? Math.floor(n / 1000) : n) as UTCTimestamp
  }
  return isDaily ? s : Math.floor(Date.now() / 1000) as UTCTimestamp
}

// --- 切换周期 ---
const changePeriod = (period: string) => {
  currentPeriod.value = period
}

// --- 切换指标 ---
const toggleIndicator = (key: keyof IndicatorConfig) => {
  activeIndicators[key] = !activeIndicators[key]
  refreshIndicators()
  saveIndicators() // 持久化到 localStorage
}

// --- 刷新指标渲染 ---
const refreshIndicators = () => {
  if (dailyData.value.length > 0) {
    updateIndicators(dailyData.value, activeIndicators)
  }
}

// --- 主题切换 ---
const toggleTheme = () => {
  toggleThemeBase()
}

// --- 画线工具 ---
const startDrawTool = (tool: DrawingType) => {
  markerMode.value = ''
  startDraw(tool)
}

const setMarkerMode = (mode: '' | 'buy' | 'sell') => {
  cancelDraw()
  markerMode.value = mode
}

const exitTools = () => {
  markerMode.value = ''
  cancelDraw()
}

// --- Canvas 鼠标按下（选择 / 画线 / 拖拽控制点 / 手动标记） ---
const consumeMouseEvent = (e: MouseEvent) => {
  e.preventDefault()
  e.stopPropagation()
}

const onCanvasMouseDown = (e: MouseEvent) => {
  if (e.button !== 0 || !drawingCanvas.value) return
  const rect = drawingCanvas.value.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top

  // 手动买卖点标记模式
  if (markerMode.value === 'buy' || markerMode.value === 'sell') {
    if (!chart.value || !candleSeries.value) return
    const time = chart.value.timeScale().coordinateToTime(x)
    const price = candleSeries.value.coordinateToPrice(y)
    if (time == null || price == null) return
    consumeMouseEvent(e)
    addManualMarker(markerMode.value, time, price)
    return
  }

  // 未选择画线工具（或为"选择"模式）时：先尝试拖拽控制点，再尝试选中
  if (!currentTool.value || currentTool.value === 'select') {
    if (startDrag(x, y) || selectDrawing(x, y)) {
      consumeMouseEvent(e)
    }
    // 未命中时让同一次 mousedown 继续到图表，保留首次拖拽。
    return
  }

  // 画线模式：添加点
  consumeMouseEvent(e)
  addPoint(x, y)
}

// --- Canvas 鼠标移动（预览线 / 拖拽更新） ---
const onCanvasMouseMove = (e: MouseEvent) => {
  if (!drawingCanvas.value) return
  const rect = drawingCanvas.value.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  if (isDragging.value || currentTool.value || markerMode.value) consumeMouseEvent(e)
  updateMousePosition(x, y)
}

// --- Canvas 鼠标松开（结束拖拽） ---
const onCanvasMouseUp = (e: MouseEvent) => {
  if (isDragging.value) consumeMouseEvent(e)
  endDrag()
}

// --- 导出 ---
const handleExportPNG = async () => {
  if (exporting.value) return
  const filename = `${props.stockCode}_${currentPeriod.value}_${Date.now()}.png`
  await exportPNG(filename)
}

const handleExportCSV = () => {
  const filename = `${props.stockCode}_${currentPeriod.value}_${Date.now()}.csv`
  exportCSV(filename, dailyData.value)
}

// --- 加载 K 线数据 ---
const loadData = async () => {
  if (!props.stockCode) return
  try {
    let data: RawKlineItem[] = []
    const period = currentPeriod.value
    const tushareCode = toTushareCode(props.stockCode)

    if (period === '1D') {
      const res = await getStockDailyData(tushareCode)
      data = res.data || []
    } else if (['1', '5', '15', '60'].includes(period)) {
      const res = await getStockMinuteData(props.stockCode, parseInt(period))
      data = res.data || []
    } else if (period === '1W') {
      const res = await getStockWeeklyData(props.stockCode)
      data = res.data || []
    } else if (period === '1M') {
      const res = await getStockMonthlyData(props.stockCode)
      data = res.data || []
    }

    // 转换为 Lightweight Charts 格式
    const isDaily = ['1D', '1W', '1M'].includes(currentPeriod.value)
    const candles: CandleItem[] = data.map((d) => ({
      time: parseTime(d.time ?? d.tradeDate, isDaily),
      open: Number(d.open ?? d.openPrice ?? 0),
      high: Number(d.high ?? d.highPrice ?? 0),
      low: Number(d.low ?? d.lowPrice ?? 0),
      close: Number(d.close ?? d.closePrice ?? 0),
      volume: Number(d.volume ?? d.vol ?? 0)
    }))

    const volumes: VolumeItem[] = data.map((d) => ({
      time: parseTime(d.time ?? d.tradeDate, isDaily),
      value: Number(d.volume ?? d.vol ?? 0),
      color: Number(d.close ?? d.closePrice ?? 0) >= Number(d.open ?? d.openPrice ?? 0) ? '#ef535080' : '#26a69a80'
    }))

    // 更新数据 ref（供 useInfoPanel 使用）
    dailyData.value = candles

    // 更新 K 线系列
    candleSeries.value?.setData(candles)

    // 更新成交量系列
    if (volumeSeries) {
      volumeSeries.setData(volumes)
    }

    // 刷新指标渲染
    refreshIndicators()

    // 最新价通知
    if (candles.length > 0) {
      const lastPrice = candles[candles.length - 1].close.toString()
      currentPrice.value = candles[candles.length - 1].close.toFixed(2)
      priceColor.value = (candles[candles.length - 1].close >= (candles[candles.length - 1].open))
        ? '#ef5350' : '#26a69a'
      emit('price-change', lastPrice)
    }
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`加载K线数据失败: ${msg}`)
  }
}

// --- 成交量系列引用 ---
let volumeSeries: ISeriesApi<'Histogram'> | null = null

// --- 初始化图表 ---
const initChartInstance = () => {
  const theme: ChartTheme = currentTheme.value as ChartTheme

  // 初始化核心图表
  initChart(theme)

  // 添加 K 线系列
  const candle = addCandlestickSeries({
    upColor: theme.candle.upColor,
    downColor: theme.candle.downColor,
    borderUpColor: theme.candle.borderUpColor,
    borderDownColor: theme.candle.borderDownColor,
    wickUpColor: theme.candle.wickUpColor,
    wickDownColor: theme.candle.wickDownColor
  })
  candleSeries.value = candle

  // 添加成交量系列
  volumeSeries = addHistogramSeries('volume', {
    color: '#26a69a',
    priceFormat: { type: 'volume' },
    priceScaleId: 'volume'
  })
  if (chart.value) {
    chart.value.priceScale('volume').applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 }
    })
  }

  // ResizeObserver：自适应容器大小
  if (chartContainer.value) {
    resizeObserver.value = new ResizeObserver(entries => {
      if (entries.length === 0 || !entries[0].contentRect || !chart.value) return
      const { width, height } = entries[0].contentRect
      chart.value.applyOptions({ width, height })
      // 同步调整画布大小
      if (drawingCanvas.value) {
        drawingCanvas.value.width = width
        drawingCanvas.value.height = height
        redraw()
      }
    })
    resizeObserver.value.observe(chartContainer.value)
  }
}

// --- 监听主题变化 ---
watch(currentTheme, (newTheme) => {
  applyTheme(newTheme as ChartTheme)
  // 主题变化后刷新指标颜色
  refreshIndicators()
  // 画线颜色保持不变（使用默认蓝色），但需要重绘
  redraw()
})

// --- 监听周期变化 ---
watch(currentPeriod, async () => {
  exitTools()
  drawings.value = [] // 立即清空画线
  selectedId.value = null
  redraw()
  await loadData()
  await nextTick() // 等待 K 线图渲染
  loadDrawings(tushareCode.value, currentPeriod.value)
  loadAutoMarkers(props.stockCode)
})

// --- 监听股票变化 ---
watch(() => props.stockCode, async () => {
  exitTools()
  if (props.stockCode) {
    currentPeriod.value = '1D'
    // 从本地存储读取股票信息
    try {
      const stored = localStorage.getItem('currentStockInfo')
      if (stored) {
        stockInfo.value = JSON.parse(stored)
      } else {
        stockInfo.value = { stockName: props.stockCode, stockCode: props.stockCode }
      }
    } catch {
      stockInfo.value = { stockName: props.stockCode, stockCode: props.stockCode }
    }
    drawings.value = [] // 立即清空画线
    selectedId.value = null
    redraw()
    await loadData()
    await nextTick() // 等待 K 线图渲染
    loadDrawings(tushareCode.value, currentPeriod.value)
    loadAutoMarkers(props.stockCode)
  }
})

// --- 生命周期：挂载 ---
onMounted(() => {
  if (!chartContainer.value) return

  // 读取股票信息
  try {
    const stored = localStorage.getItem('currentStockInfo')
    if (stored) {
      stockInfo.value = JSON.parse(stored)
    } else {
      stockInfo.value = { stockName: props.stockCode, stockCode: props.stockCode }
    }
  } catch {
    stockInfo.value = { stockName: props.stockCode, stockCode: props.stockCode }
  }

  // 初始化图表
  initChartInstance()
  initCanvas()
  subscribeCrosshair()
  subscribeChartMove()

  // 加载数据
  loadData()
  loadDrawings(tushareCode.value, currentPeriod.value)
  loadAutoMarkers(props.stockCode)

  // 监听键盘删除事件
  window.addEventListener('keydown', onKeyDown)
})

// --- 键盘事件（删除选中画线）---
const onKeyDown = (e: KeyboardEvent) => {
  if (e.key === 'Escape') {
    exitTools()
    return
  }
  const target = e.target
  if (target instanceof HTMLElement && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))) return
  if (e.key === 'Delete' || e.key === 'Backspace') {
    if (selectedId.value) {
      deleteDrawing(selectedId.value)
    }
  }
}

// --- 清空当前股票所有画线 ---
const clearAllDrawings = () => {
  if (confirm(`确定要清空当前股票的所有 ${drawings.value.length} 条画线吗？`)) {
    clearDrawings()
  }
}

// --- 生命周期：卸载 ---
onBeforeUnmount(() => {
  resizeObserver.value?.disconnect()
  unsubscribeCrosshair()
  disposeDrawingTools()
  disposeMarkers()
  clearAllIndicators()
  destroyChart()
  window.removeEventListener('keydown', onKeyDown) // 移除键盘监听
  // 组件卸载前保存画线（防止切换股票时组件销毁导致数据丢失）
  saveDrawingsOnUnmount()
})

// --- 组件卸载前保存画线 ---
const saveDrawingsOnUnmount = () => {
  if (!drawings.value.length) return
  const userId = Number(localStorage.getItem('userId') || '0')
  const stockCode = toTushareCode(props.stockCode)
  const data = JSON.stringify(drawings.value)
  // 同步保存到 localStorage（确保不丢失）
  const LOCAL_KEY = 'kline_drawings'
  try {
    const map = JSON.parse(localStorage.getItem(LOCAL_KEY) || '{}')
    map[`${stockCode}|${currentPeriod.value}`] = drawings.value
    localStorage.setItem(LOCAL_KEY, JSON.stringify(map))
  } catch (e) {
    console.error('[onBeforeUnmount] localStorage 保存失败', e)
  }
  if (userId <= 0) return
  saveDrawing({ userId, stockCode, period: currentPeriod.value, data })
    .catch(e => console.error('[onBeforeUnmount] 保存失败', e))
}
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

/* --- 顶部工具栏 --- */
.chart-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 10px;
  background: var(--term-panel-header);
  border-bottom: 1px solid var(--term-border);
  flex-wrap: wrap;
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

/* --- 指标选择器 --- */
.indicator-group {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.indicator-label {
  display: flex;
  align-items: center;
  gap: 3px;
  cursor: pointer;
  font-size: 12px;
  color: var(--term-fg-muted);
  user-select: none;
}

.indicator-label:hover {
  color: var(--term-accent);
}

.indicator-label input[type="checkbox"] {
  cursor: pointer;
  accent-color: var(--term-accent);
}

/* --- 画线工具栏 --- */
.drawing-toolbar {
  display: flex;
  align-items: center;
  gap: 2px;
  flex-wrap: wrap;
}

.draw-btn {
  padding: 3px 7px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 3px;
  color: var(--term-fg-muted);
  font-size: 11px;
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

/* --- 买卖点标记 --- */
.marker-group {
  display: flex;
  gap: 2px;
}

.marker-btn {
  padding: 4px 8px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 3px;
  color: var(--term-fg-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.marker-btn:hover {
  background: var(--term-hover);
  color: var(--term-fg);
}

.marker-btn.active {
  background: var(--term-active);
  color: var(--term-accent);
  border-color: var(--term-accent);
}

/* --- 通用工具栏按钮 --- */
.toolbar-icon-btn {
  padding: 4px 8px;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 3px;
  color: var(--term-fg-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.15s;
}

.toolbar-icon-btn:hover {
  background: var(--term-hover);
  color: var(--term-fg);
}

/* --- 导出组 --- */
.export-group {
  display: flex;
  gap: 2px;
}

/* --- 股票信息标签 --- */
.stock-label {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: auto;
  font-size: 12px;
}

.stock-name {
  color: var(--term-fg);
  font-weight: 600;
}

.stock-code {
  color: var(--term-fg-muted);
}

.stock-price {
  font-weight: 600;
  font-size: 13px;
}

/* --- 图表区 --- */
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

.chart-wrapper.tool-active :deep(canvas) {
  cursor: crosshair;
}

.chart-wrapper.select-mode :deep(canvas) {
  cursor: default;
}

/* --- 信息面板 --- */
.info-panel {
  position: absolute;
  top: 6px;
  left: 6px;
  z-index: 10;
  background: rgba(22, 27, 34, 0.92);
  border: 1px solid var(--term-border);
  border-radius: 4px;
  padding: 8px 10px;
  font-size: 12px;
  color: var(--term-fg);
  min-width: 200px;
  pointer-events: none;
  backdrop-filter: blur(4px);
}

.info-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 4px;
}

.info-date {
  color: var(--term-fg-muted);
  font-weight: 500;
}

.info-change {
  font-weight: 600;
}

.info-change.up {
  color: #ef5350;
}

.info-change.down {
  color: #26a69a;
}

.info-grid {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  color: var(--term-fg-muted);
  font-size: 11px;
}

.info-indicators {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 4px;
  padding-top: 4px;
  border-top: 1px solid var(--term-border);
  color: var(--term-fg-muted);
  font-size: 11px;
}
</style>
