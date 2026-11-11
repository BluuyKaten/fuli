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

      <!-- 时间轴口径：美股也是北京时间，不标注会被当成数据错误 -->
      <span v-if="timezoneHint" class="tz-hint" title="数据源统一返回北京时间">时间：{{ timezoneHint }}</span>

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

    <!-- 全貌缩略条：主图只加载最近 620 根，这条负责保留「全历史长什么样」 -->
    <SparklineStrip
      v-if="currentPeriod === '1D'"
      :points="sparkPoints"
      :window-from="windowFrom"
      :window-to="windowTo"
      :markers="sparkMarkers"
      @seek="onSeek"
    />
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, shallowRef, watch, computed, nextTick } from 'vue'
import type { ISeriesApi, Time, UTCTimestamp } from 'lightweight-charts'
import { message } from 'ant-design-vue'
import {
  getStockMinuteData,
  getStockWeeklyData,
  getStockMonthlyData,
  getStockDailyData,
  getStockSparkline,
  getStockPeriods,
  saveDrawing,
  type KlinePeriod
} from '@/api/kline'
import AppIcon from '@/components/ui/AppIcon.vue'
import SparklineStrip from '@/components/chart/SparklineStrip.vue'
import { shouldSkipLoad } from '@/utils/loadGuard'
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
  stockCode: string // 完整符号 600519.SH（后端原值，解析交给服务端）
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
const { markers, loadAutoMarkers, clearAutoMarkers, addManualMarker, disposeMarkers } = useTradeMarkers(candleSeries)

const {
  currentTool, initCanvas, startDraw, cancelDraw, addPoint, updateMousePosition,
  selectDrawing, startDrag, endDrag, isDragging, drawings, selectedId, deleteDrawing, clearDrawings,
  loadDrawings, redraw, subscribeChartMove, disposeDrawingTools
} = useDrawingTools(chart, drawingCanvas, candleSeries)

const { exporting, exportPNG, exportCSV } = useChartExport(chartContainer)

// K 线数据 ref：用于传递给 useInfoPanel
//
// 必须用 shallowRef 而不是 ref。ref 会对数组做深度响应式，几千个 bar 的
// 每个字段都被 Proxy 递归包裹；指标计算要遍历全部 bar，等于逐字段触发代理创建。
// 实测（docs/perf/bench-frontend.mjs）：5,071 根时 reactive 数组的指标计算
// 是裸数组的 8.2 倍（91.4ms vs 11.2ms）。
// 这里只整体赋值、整体读取，从不需要对单个 bar 做响应式修改，深度代理纯属浪费。
// 详见 docs/adr/0002-kline-windowed-loading.md。
const dailyData = shallowRef<CandleItem[]>([])
const { panelData, subscribeCrosshair, unsubscribeCrosshair } = useInfoPanel(
  chart,
  dailyData,
  activeIndicators
)

// --- 周期选项：服务端下发，这里只是接口不可用时的兜底 ---
// 兜底不能省：/stock/periods 挂了不该让整张图不可用，至少日线要能出。
const FALLBACK_PERIODS: KlinePeriod[] = [
  { value: '1', label: '1分', kind: 'minute' },
  { value: '5', label: '5分', kind: 'minute' },
  { value: '15', label: '15分', kind: 'minute' },
  { value: '30', label: '30分', kind: 'minute' },
  { value: '60', label: '60分', kind: 'minute' },
  { value: '1D', label: '日线', kind: 'day' },
  { value: '1W', label: '周线', kind: 'week' },
  { value: '1M', label: '月线', kind: 'month' }
]
const periods = ref<KlinePeriod[]>(FALLBACK_PERIODS)
const currentPeriod = ref('1D')
const market = ref('')
const timezoneHint = ref('')

/** 当前周期属于哪一类。所有「该调哪个端点」的判断都走这里，不再靠字符串 includes 猜。 */
const periodKind = () => periods.value.find(p => p.value === currentPeriod.value)?.kind

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

/** 默认视窗根数。1200px 宽的图区画 500 根已是每根 2.4px，再多也分辨不出。 */
const DAILY_WINDOW = 500

/** 指标前置窗口。MA60 需 59 根、MACD 需 33 根；取 120 是留一倍余量，
 *  用户把均线周期改成 MA120 时边缘也不会断线。 */
const DAILY_LOOKBACK = 120

// --- 全貌缩略条 ---

/** 降采样后的全历史收盘序列（服务端 LTTB，约 240 点） */
const sparkPoints = ref<{ d: number; c: number }[]>([])
/** 当前视窗在全历史中的起止比例，0~1 */
const windowFrom = ref(0)
const windowTo = ref(1)

const loadSparkline = async () => {
  try {
    const res = await getStockSparkline(props.stockCode, 240)
    sparkPoints.value = (res.data || []) as { d: number; c: number }[]
  } catch {
    // 缩略条只是辅助信息，拿不到就不显示，不影响主图
    sparkPoints.value = []
  }
}

/** 把主图可视区间换算成缩略条上的比例 */
const syncWindowRatio = (range: { from: number; to: number } | null) => {
  const bars = dailyData.value
  const pts = sparkPoints.value
  if (!range || !bars.length || pts.length < 2) return
  const i0 = Math.max(0, Math.floor(range.from))
  const i1 = Math.min(bars.length - 1, Math.ceil(range.to))
  const d0 = Number(String(bars[i0].time).replace(/-/g, ''))
  const d1 = Number(String(bars[i1].time).replace(/-/g, ''))
  const first = pts[0].d
  const last = pts[pts.length - 1].d
  const span = last - first || 1
  windowFrom.value = Math.min(1, Math.max(0, (d0 - first) / span))
  windowTo.value = Math.min(1, Math.max(0, (d1 - first) / span))
}

/** 缩略条上的买卖点；坐标由子组件按价格区间换算 */
const sparkMarkers = computed(() =>
  markers.value.map(m => ({
    d: String(m.time),
    direction: m.direction,
    price: m.price
  }))
)

/** 点击缩略条时最多自动向前补几页（每页 500 根）。3 页 ≈ 1500 根，覆盖绝大多数回看需求；
 *  再多就等于把全历史拉进内存，窗口化的收益会被吃回去。 */
const MAX_SEEK_PAGES = 3

/**
 * 点击缩略条，把主图移到对应日期。
 *
 * 目标常常早于已加载窗口（主图只拿最近 620 根），此时自动向前补页，而不是只弹一句
 * "不在已加载区间" 让用户自己拖。补页数有上限，见 {@link MAX_SEEK_PAGES}。
 */
const onSeek = async (date: number) => {
  const s = String(date)
  const iso = `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6, 8)}`
  const locate = () => dailyData.value.findIndex(b => String(b.time) === iso)

  let idx = locate()
  let pages = 0

  if (idx < 0 && dailyData.value.length) {
    const earliest = String(dailyData.value[0].time)
    if (iso < earliest) {
      // 目标早于已加载范围：逐页向前补，直到覆盖或达到上限
      while (pages < MAX_SEEK_PAGES && hasMoreHistory.value) {
        const before = dailyData.value.length
        await loadOlder()
        pages++
        // 没有新增说明已经到上市首日，停止
        if (dailyData.value.length === before) break
        if (String(dailyData.value[0].time) <= iso) break
      }
      idx = locate()
    }
  }

  if (idx < 0) {
    message.info(
      pages > 0
        ? `已向前加载 ${pages} 页仍未覆盖该日期，继续向左拖动可加载更早数据`
        : '该日期无行情数据'
    )
    return
  }

  const half = Math.floor(DAILY_WINDOW / 2)
  chart.value?.timeScale().setVisibleLogicalRange({
    from: Math.max(0, idx - half),
    to: Math.min(dailyData.value.length - 1, idx + half)
  })
}

/**
 * 把可视区间定位到「前置区之后」，让用户看到的是最近 500 根，
 * 而不是把 620 根全挤进视窗。数据不足窗口长度时不做处理。
 */
const applyInitialWindow = (barCount: number) => {
  const lookback = Math.min(DAILY_LOOKBACK, Math.max(0, barCount - DAILY_WINDOW))
  if (lookback <= 0 || !chart.value) return
  chart.value.timeScale().setVisibleLogicalRange({ from: lookback, to: barCount - 1 })
}

/** 当前已加载 K 线的日期范围，用于过滤越界的成交标记 */
const barRange = (): { from: string; to: string } | undefined => {
  const d = dailyData.value
  if (!d.length) return undefined
  return { from: String(d[0].time), to: String(d[d.length - 1].time) }
}

/**
 * 刷新历史成交标记（买入点/卖出点）。
 *
 * 两个约束：
 * 1. 窗口化后只加载最近 620 根，早于此的成交不在 series 数据范围内，
 *    传给 lightweight-charts 会被拒绝渲染。由 loadAutoMarkers 内部按范围过滤。
 * 2. 分钟周期的时间轴是 timestamp，而成交记录只有日期（"2025-01-01"），
 *    类型不同无法定位，因此分钟周期不显示成交标记。
 */
const refreshAutoMarkers = async () => {
  if (!isDailyPeriod()) {
    clearAutoMarkers()
    return
  }
  // 传完整符号（002636.SZ）。props.stockCode 由上层透传后端原值，不再本地剥离后缀；
  // trade_record.stock_code 存的是同一格式，直接传即可命中。
  await loadAutoMarkers(props.stockCode, barRange())
}

const isDailyPeriod = () => {
  const kind = periodKind()
  return kind !== undefined && kind !== 'minute'
}

/** 后端原始 bar → 图表 CandleItem。抽出来是为了首次加载和左滑加载共用同一套字段兜底。 */
const toCandles = (data: RawKlineItem[], isDaily: boolean): CandleItem[] =>
  data.map(d => ({
    time: parseTime(d.time ?? d.tradeDate, isDaily),
    open: Number(d.open ?? d.openPrice ?? 0),
    high: Number(d.high ?? d.highPrice ?? 0),
    low: Number(d.low ?? d.lowPrice ?? 0),
    close: Number(d.close ?? d.closePrice ?? 0),
    volume: Number(d.volume ?? d.vol ?? 0)
  }))

const toVolumes = (data: RawKlineItem[], isDaily: boolean): VolumeItem[] =>
  data.map(d => ({
    time: parseTime(d.time ?? d.tradeDate, isDaily),
    value: Number(d.volume ?? d.vol ?? 0),
    color: Number(d.close ?? d.closePrice ?? 0) >= Number(d.open ?? d.openPrice ?? 0) ? '#ef535080' : '#26a69a80'
  }))

/** 已合并的 candles 直接生成成交量柱，省一次重新映射 */
const candlesToVolumes = (bars: CandleItem[]): VolumeItem[] =>
  bars.map(b => ({
    time: b.time,
    value: b.volume ?? 0,
    color: b.close >= b.open ? '#ef535080' : '#26a69a80'
  }))

// --- 左滑加载更早历史 ---

/** 每次向前补多少根 */
const LOAD_MORE_SIZE = 500
/** 距离左边缘还剩多少根时开始预加载 */
const LOAD_MORE_THRESHOLD = 20

const loadingMore = ref(false)
const hasMoreHistory = ref(true)

/** "2024-04-11" → "2024-04-10"（days 为负） */
const shiftDate = (iso: string, days: number): string => {
  const d = new Date(`${iso}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + days)
  return d.toISOString().slice(0, 10)
}

/**
 * 向前（更早）加载一页日线。
 *
 * 两个必须处理的副作用：
 * 1. setData 会把 timeScale 重置为「显示全部」，用户当前浏览位置会丢，
 *    所以要记录 logicalRange 并按新增根数偏移后恢复。
 * 2. 画线是屏幕坐标，数据变了必须 redraw；成交标记的范围也变了，要重新过滤。
 */
const loadOlder = async () => {
  if (loadingMore.value || !hasMoreHistory.value) return
  if (currentPeriod.value !== '1D') return
  const bars = dailyData.value
  if (bars.length === 0) return

  loadingMore.value = true
  try {
    const earliest = String(bars[0].time)
    const res = await getStockDailyData(props.stockCode, {
      endDate: shiftDate(earliest, -1).replace(/-/g, ''),
      limit: LOAD_MORE_SIZE
    })
    const raw = (res.data || []) as RawKlineItem[]
    if (raw.length === 0) {
      hasMoreHistory.value = false
      return
    }

    const older = toCandles(raw, true)
    const existing = new Set(bars.map(b => String(b.time)))
    const fresh = older.filter(b => !existing.has(String(b.time)))
    if (fresh.length === 0) {
      // 后端返回的都已在本地（边界重复），说明到头了
      hasMoreHistory.value = false
      return
    }

    const merged = [...fresh, ...bars]
    const range = chart.value?.timeScale().getVisibleLogicalRange()

    dailyData.value = merged
    candleSeries.value?.setData(merged)
    volumeSeries?.setData(candlesToVolumes(merged))
    refreshIndicators()

    if (range) {
      chart.value?.timeScale().setVisibleLogicalRange({
        from: range.from + fresh.length,
        to: range.to + fresh.length
      })
    }

    redraw()
    refreshAutoMarkers()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : '未知错误'
    message.error(`加载更早数据失败: ${msg}`)
  } finally {
    loadingMore.value = false
  }
}

const loadData = async () => {
  if (!props.stockCode) return
  // 同参数 400ms 内只放行一次。状态放在 loadGuard 模块里而不是这里——
  // `<script setup>` 的变量属于组件实例，:key 变化重建后会归零，闸门就失效了。
  const dedupeKey = `${props.stockCode}|${currentPeriod.value}`
  if (shouldSkipLoad(dedupeKey)) {
    console.info('[K线] 跳过重复加载', dedupeKey)
    return
  }
  console.count('[K线] loadData')
  try {
    let data: RawKlineItem[] = []
    const period = currentPeriod.value
    const code = props.stockCode

    if (period === '1D') {
      const res = await getStockDailyData(code, { limit: DAILY_WINDOW + DAILY_LOOKBACK })
      data = res.data || []
    } else if (periodKind() === 'minute') {
      const res = await getStockMinuteData(props.stockCode, parseInt(period, 10))
      data = res.data || []
    } else if (periodKind() === 'week') {
      const res = await getStockWeeklyData(props.stockCode)
      data = res.data || []
    } else if (periodKind() === 'month') {
      const res = await getStockMonthlyData(props.stockCode)
      data = res.data || []
    } else {
      // 周期不在下发列表里（理论上不会发生）。不静默画空图：那会让人以为「没数据」。
      message.warning(`不支持的周期：${period}`)
      return
    }

    // 转换为 Lightweight Charts 格式
    const isDaily = isDailyPeriod()
    const candles = toCandles(data, isDaily)
    const volumes = toVolumes(data, isDaily)

    // 日线首屏：拿满了窗口长度才认为后面还有更早的数据可加载
    if (period === '1D') {
      hasMoreHistory.value = candles.length >= DAILY_WINDOW + DAILY_LOOKBACK
      // 不 await：缩略条是辅助信息，不应阻塞主图渲染
      void loadSparkline()
    }

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

    // 视窗定位到前置区之后。必须在 setData 与 refreshIndicators 之后执行，
    // 因为 setData 会把 timeScale 重置为「显示全部」。
    if (period === '1D') {
      applyInitialWindow(candles.length)
    }

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

// --- 本组件自己注册的视窗变化回调（卸载时要按同一引用退订） ---
let onVisibleRangeChange: ((range: { from: number; to: number } | null) => void) | null = null

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

  // 左滑到接近左边缘时，向前补一页历史。
  // 该事件同时被 useDrawingTools 订阅用于 redraw，lightweight-charts 支持多订阅者。
  // 首次 setVisibleLogicalRange 时 from=120，不会触发预加载。
  // 注意：订阅的回调必须存下来，卸载时按同一个引用退订——inline 箭头函数订阅后
  // 无法退订，图表若被复用（HMR / 切换标的）就会残留旧闭包，重复触发 loadOlder。
  onVisibleRangeChange = range => {
    if (!range) return
    syncWindowRatio(range)
    if (range.from < LOAD_MORE_THRESHOLD) {
      void loadOlder()
    }
  }
  chart.value?.timeScale().subscribeVisibleLogicalRangeChange(onVisibleRangeChange)
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
  loadDrawings(props.stockCode, currentPeriod.value)
  refreshAutoMarkers()
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
    // 换标的后周期集合/时区口径都可能变（A 股 ↔ 美股），先刷新再取数
    await loadPeriods()
    await loadData()
    await nextTick() // 等待 K 线图渲染
    loadDrawings(props.stockCode, currentPeriod.value)
    refreshAutoMarkers()
  }
})

// --- 生命周期：挂载 ---
/**
 * 拉取该标的可用周期（服务端下发）。
 *
 * <p>失败时保持兜底列表——周期接口挂了不该让整张图不可用，这是刻意的降级。
 */
const loadPeriods = async () => {
  try {
    const meta = (await getStockPeriods(props.stockCode)).data
    const list = meta?.periods
    if (!Array.isArray(list) || list.length === 0) return

    periods.value = list
    market.value = meta.market || ''
    // 美股分钟线也是北京时间（东财口径）。不标注的话，用户看到 22:30 开盘会以为数据错了。
    if (meta.market === 'US' && meta.timezone) {
      timezoneHint.value = meta.timezone === 'Asia/Shanghai' ? '北京时间' : meta.timezone
    } else {
      timezoneHint.value = ''
    }

    if (!list.some(p => p.value === currentPeriod.value)) {
      const fallback = meta.defaultPeriod
      currentPeriod.value = list.some(p => p.value === fallback) ? fallback : list[0].value
    }
  } catch {
    // 用兜底列表继续，不打断看图
  }
}

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
  // 标记必须在数据落地之后再加载：loadAutoMarkers 依赖 barRange() 过滤越界点，
  // 提前调用会拿到空范围，等于不过滤，反而让越界标记进入 series。
  loadPeriods().then(() => {
    loadData().then(() => refreshAutoMarkers())
  })
  loadDrawings(props.stockCode, currentPeriod.value)

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
  // 先退订本组件的视窗回调，再销毁图表（顺序反了 chart 已置空，退订就拿不到 timeScale）
  if (onVisibleRangeChange) {
    chart.value?.timeScale().unsubscribeVisibleLogicalRangeChange(onVisibleRangeChange)
    onVisibleRangeChange = null
  }
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
  const stockCode = props.stockCode
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

/* 时间轴口径提示（目前只在美股出现：北京时间 22:30 开盘，不标注像数据错了） */
.tz-hint {
  margin-left: 6px;
  font-size: 11px;
  color: var(--term-fg-dim);
  white-space: nowrap;
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
