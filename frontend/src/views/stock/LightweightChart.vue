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
      </div>

      <!-- 买卖点标记模式 -->
      <div class="marker-group">
        <button
          :class="['marker-btn', { active: markerMode === '' }]"
          @click="markerMode = ''"
        >标记关</button>
        <button
          :class="['marker-btn', { active: markerMode === 'buy' }]"
          @click="markerMode = 'buy'"
        >买入标记</button>
        <button
          :class="['marker-btn', { active: markerMode === 'sell' }]"
          @click="markerMode = 'sell'"
        >卖出标记</button>
      </div>

      <!-- 主题切换 -->
      <button class="toolbar-icon-btn" @click="toggleTheme" :title="currentThemeName === 'dark' ? '切换亮色' : '切换暗色'">
        <span v-if="currentThemeName === 'dark'">☀</span>
        <span v-else>☾</span>
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
    <div class="chart-wrapper">
      <div ref="chartContainer" class="chart-container" />
      <canvas
        ref="drawingCanvas"
        class="drawing-canvas"
        :class="{ active: currentTool }"
        @click="onCanvasClick"
      />
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
import { onBeforeUnmount, onMounted, ref, watch, computed } from 'vue'
import type { ISeriesApi } from 'lightweight-charts'
import { message } from 'ant-design-vue'
import {
  getStockMinuteData,
  getStockWeeklyData,
  getStockMonthlyData,
  getStockDailyData
} from '@/api/kline'
import { toTushareCode } from '@/utils/stockCode'
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
  time: string | number
  value: number
  color: string
}

// --- DOM refs ---
const chartContainer = ref<HTMLElement | null>(null)
const drawingCanvas = ref<HTMLCanvasElement | null>(null)

// --- 集成所有 composables ---
const chartCore = useChartCore(chartContainer)
const { chart, initChart, applyTheme, addCandlestickSeries, addHistogramSeries, destroyChart } = chartCore

const { currentTheme, currentThemeName, toggleTheme: toggleThemeBase } = useChartTheme()
const { activeIndicators, updateIndicators } = useIndicators(chart, currentTheme as any)

// candleSeries ref：用于传递给 useTradeMarkers
const candleSeries = ref<ISeriesApi<any> | null>(null)
const { loadAutoMarkers } = useTradeMarkers(chart, candleSeries)

const {
  currentTool, initCanvas, startDraw, addPoint,
  loadDrawings, redraw
} = useDrawingTools(chart, drawingCanvas)

const { exporting, exportPNG, exportCSV } = useChartExport(chartContainer)

// K 线数据 ref：用于传递给 useInfoPanel
const dailyData = ref<CandleItem[]>([])
const { panelData, subscribeCrosshair, unsubscribeCrosshair } = useInfoPanel(
  chart,
  dailyData,
  activeIndicators as IndicatorConfig
)

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
const parseTime = (t: string | number | undefined | null, isDaily: boolean): string | number => {
  if (!t && t !== 0) {
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
    return isDaily ? new Date(n > 1e12 ? n : n * 1000).toISOString().slice(0, 10)
      : (n > 1e12 ? Math.floor(n / 1000) : n)
  }
  return isDaily ? s : Math.floor(Date.now() / 1000)
}

// --- 切换周期 ---
const changePeriod = (period: string) => {
  currentPeriod.value = period
}

// --- 切换指标 ---
const toggleIndicator = (key: string) => {
  ;(activeIndicators as any)[key] = !(activeIndicators as any)[key]
  refreshIndicators()
}

// --- 刷新指标渲染 ---
const refreshIndicators = () => {
  if (dailyData.value.length > 0) {
    updateIndicators(dailyData.value, activeIndicators as IndicatorConfig)
  }
}

// --- 主题切换 ---
const toggleTheme = () => {
  toggleThemeBase()
}

// --- 画线工具 ---
const startDrawTool = (tool: DrawingType) => {
  if (currentTool.value === tool) {
    // 再次点击取消当前工具
    // useDrawingTools 不暴露 cancelDraw，通过 startDraw 重置
  }
  startDraw(tool)
}

// --- Canvas 点击（画线） ---
const onCanvasClick = (e: MouseEvent) => {
  if (!currentTool.value || !drawingCanvas.value) return
  const rect = drawingCanvas.value.getBoundingClientRect()
  const x = e.clientX - rect.left
  const y = e.clientY - rect.top
  addPoint(x, y)
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
      time: parseTime(d.time ?? d.tradeDate, isDaily) as string | number,
      open: Number(d.open ?? d.openPrice ?? 0),
      high: Number(d.high ?? d.highPrice ?? 0),
      low: Number(d.low ?? d.lowPrice ?? 0),
      close: Number(d.close ?? d.closePrice ?? 0),
      volume: Number(d.volume ?? d.vol ?? 0)
    }))

    const volumes: VolumeItem[] = data.map((d) => ({
      time: parseTime(d.time ?? d.tradeDate, isDaily) as string | number,
      value: Number(d.volume ?? d.vol ?? 0),
      color: Number(d.close ?? d.closePrice ?? 0) >= Number(d.open ?? d.openPrice ?? 0) ? '#ef535080' : '#26a69a80'
    }))

    // 更新数据 ref（供 useInfoPanel 使用）
    dailyData.value = candles

    // 更新 K 线系列
    candleSeries.value?.setData(candles as any)

    // 更新成交量系列
    if (volumeSeries) {
      volumeSeries.setData(volumes as any)
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
let volumeSeries: ISeriesApi<any> | null = null

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
    const resizeObserver = new ResizeObserver(entries => {
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
    resizeObserver.observe(chartContainer.value)
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
watch(currentPeriod, () => {
  loadData()
  loadDrawings(props.stockCode, currentPeriod.value)
  loadAutoMarkers(props.stockCode)
})

// --- 监听股票变化 ---
watch(() => props.stockCode, () => {
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
    loadData()
    loadDrawings(props.stockCode, currentPeriod.value)
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

  // 加载数据
  loadData()
  loadDrawings(props.stockCode, currentPeriod.value)
  loadAutoMarkers(props.stockCode)
})

// --- 生命周期：卸载 ---
onBeforeUnmount(() => {
  unsubscribeCrosshair()
  destroyChart()
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

.drawing-canvas.active {
  pointer-events: auto;
  cursor: crosshair;
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
