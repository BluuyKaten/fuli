<template>
  <div class="kline-view">
    <!-- 工具栏：搜索 + 日期 -->
    <div class="kline-toolbar">
      <a-select
        v-model:value="searchKeyword"
        show-search
        placeholder="搜索股票代码或名称"
        :filter-option="false"
        :options="searchOptions"
        style="width: 220px"
        @search="handleSearch"
        @change="handleSelectStock"
      />
      <a-range-picker
        v-model:value="dateRange"
        value-format="YYYYMMDD"
        style="width: 240px"
      />
      <a-button type="primary" :loading="loading" @click="loadData">查询</a-button>

      <!-- 指标选择 -->
      <a-popover trigger="click" placement="bottomRight">
        <template #content>
          <div class="indicator-selector">
            <label class="indicator-option">
              <a-checkbox :checked="indicators.volume" @change="indicators.volume = $event.target.checked; renderCharts()" />
              <span>成交量 (VOL)</span>
            </label>
            <label class="indicator-option">
              <a-checkbox :checked="indicators.macd" @change="indicators.macd = $event.target.checked; renderCharts()" />
              <span>MACD</span>
            </label>
            <label class="indicator-option">
              <a-checkbox :checked="indicators.kdj" @change="indicators.kdj = $event.target.checked; renderCharts()" />
              <span>KDJ</span>
            </label>
          </div>
        </template>
        <a-button>
          <template #icon><SettingOutlined /></template>
          指标
        </a-button>
      </a-popover>

      <span v-if="stockInfo" class="stock-label">
        <span class="stock-name">{{ stockInfo.stockName }}</span>
        <span class="stock-code">({{ stockInfo.stockCode }})</span>
        <span v-if="currentPrice" class="stock-price" :style="{ color: priceColor }">
          ¥{{ currentPrice }}
        </span>
      </span>
    </div>

    <!-- 图表区 -->
    <div class="kline-charts">
      <div ref="klineChartRef" class="chart chart-kline" />
      <div v-if="indicators.volume" ref="volumeChartRef" class="chart chart-volume" />
      <div v-if="indicators.macd" ref="macdChartRef" class="chart chart-indicator" />
      <div v-if="indicators.kdj" ref="kdjChartRef" class="chart chart-indicator" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import { SettingOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import { searchStocks, getStockDaily, getStockLatestPrice } from '@/api/stock'
import type { StockInfo, StockDailyData } from '@/types'
import { toPureCode, toTushareCode } from '@/utils/stockCode'

const emit = defineEmits<{
  (e: 'select-stock', stock: StockInfo): void
  (e: 'price-change', price: string): void
}>()

const UP_COLOR = '#ef5350'
const DOWN_COLOR = '#26a69a'

const searchKeyword = ref('')
const searchOptions = ref<(StockInfo & { value: string; label: string })[]>([])
const selectedStockCode = ref('')
const dateRange = ref<[dayjs.Dayjs, dayjs.Dayjs] | null>(null)
const loading = ref(false)
const stockInfo = ref<StockInfo | null>(null)
const dailyData = ref<StockDailyData[]>([])
const currentPrice = ref('')

const priceColor = ref('#c9d1d9')

const klineChartRef = ref()
const volumeChartRef = ref()
const macdChartRef = ref()
const kdjChartRef = ref()

// 指标显示开关
const indicators = reactive({
  volume: true,
  macd: true,
  kdj: true
})

let klineChart: echarts.ECharts | null = null
let volumeChart: echarts.ECharts | null = null
let macdChart: echarts.ECharts | null = null
let kdjChart: echarts.ECharts | null = null

const handleSearch = async (value: string) => {
  if (!value || value.length < 1) return
  try {
    const res = await searchStocks(value)
    if (res.code === 200) {
      searchOptions.value = res.data.map((item: StockInfo) => ({
        ...item,
        value: item.stockCode,
        label: `${item.stockName} (${item.stockCode})`
      }))
    }
  } catch { /* ignore */ }
}

const handleSelectStock = async (value: string) => {
  const selected = searchOptions.value.find(o => o.value === value)
  if (selected) {
    stockInfo.value = { ...selected, stockCode: toPureCode(selected.stockCode) }
    emit('select-stock', stockInfo.value)
  }
  currentPrice.value = ''
  await loadCurrentPrice(value)
}

const loadCurrentPrice = async (stockCode: string) => {
  try {
    const res = await getStockLatestPrice(toTushareCode(stockCode))
    if (res.code === 200 && res.data) {
      currentPrice.value = res.data.closePrice || ''
      emit('price-change', currentPrice.value)
    }
  } catch { currentPrice.value = '' }
}

const calculateMA = (dayCount: number, data: StockDailyData[]) => {
  const result: (number | '-')[] = []
  for (let i = 0; i < data.length; i++) {
    if (i < dayCount - 1) { result.push('-'); continue }
    let sum = 0
    for (let j = 0; j < dayCount; j++) sum += Number(data[i - j].closePrice)
    result.push(+(sum / dayCount).toFixed(2))
  }
  return result
}

const calculateEMA = (dayCount: number, closes: number[]) => {
  const result: string[] = []
  const k = 2 / (dayCount + 1)
  for (let i = 0; i < closes.length; i++) {
    if (i < dayCount - 1) { result.push('-') }
    else if (i === dayCount - 1) {
      let sum = 0
      for (let j = 0; j < dayCount; j++) sum += closes[i - j]
      result.push((sum / dayCount).toFixed(3))
    } else {
      result.push((closes[i] * k + Number(result[i - 1]) * (1 - k)).toFixed(3))
    }
  }
  return result
}

const calculateMACD = (data: StockDailyData[]) => {
  const closes = data.map(d => Number(d.closePrice))
  const ema12 = calculateEMA(12, closes)
  const ema26 = calculateEMA(26, closes)
  const dif: string[] = []
  for (let i = 0; i < ema12.length; i++) {
    dif.push(ema12[i] === '-' || ema26[i] === '-' ? '-' : (Number(ema12[i]) - Number(ema26[i])).toFixed(3))
  }
  const dea: string[] = new Array(dif.length).fill('-')
  const deaK = 2 / 10
  const validIdx: number[] = []
  for (let i = 0; i < dif.length; i++) if (dif[i] !== '-') validIdx.push(i)
  for (let j = 0; j < validIdx.length; j++) {
    const i = validIdx[j]
    if (j < 8) continue
    else if (j === 8) {
      let sum = 0
      for (let k = 0; k < 9; k++) sum += Number(dif[validIdx[k]])
      dea[i] = (sum / 9).toFixed(3)
    } else {
      const prevIdx = validIdx[j - 1]
      dea[i] = (Number(dif[i]) * deaK + Number(dea[prevIdx]) * (1 - deaK)).toFixed(3)
    }
  }
  const macd: string[] = []
  for (let i = 0; i < dif.length; i++) {
    macd.push(dif[i] === '-' || dea[i] === '-' ? '-' : ((Number(dif[i]) - Number(dea[i])) * 2).toFixed(3))
  }
  return { dif, dea, macd }
}

const calculateKDJ = (data: StockDailyData[], n = 9) => {
  const kArr: number[] = [], dArr: number[] = [], jArr: number[] = []
  for (let i = 0; i < data.length; i++) {
    if (i < n - 1) { kArr.push(50); dArr.push(50); jArr.push(50); continue }
    const highs: number[] = [], lows: number[] = []
    for (let j = i - n + 1; j <= i; j++) {
      highs.push(Number(data[j].highPrice))
      lows.push(Number(data[j].lowPrice))
    }
    const high = Math.max(...highs)
    const low = Math.min(...lows)
    const close = Number(data[i].closePrice)
    const rsv = high === low ? 50 : ((close - low) / (high - low)) * 100
    const k = (2 / 3) * kArr[i - 1] + (1 / 3) * rsv
    const d = (2 / 3) * dArr[i - 1] + (1 / 3) * k
    const j = 3 * k - 2 * d
    kArr.push(+k.toFixed(2)); dArr.push(+d.toFixed(2)); jArr.push(+j.toFixed(2))
  }
  return { k: kArr, d: dArr, j: jArr }
}

const renderCharts = () => {
  if (!klineChartRef.value || dailyData.value.length === 0) return

  const dates = dailyData.value.map(d => d.tradeDate)
  const klineData = dailyData.value.map(d => [Number(d.openPrice), Number(d.closePrice), Number(d.lowPrice), Number(d.highPrice)])
  const volumes = dailyData.value.map(d => Number(d.vol))
  const ma5 = calculateMA(5, dailyData.value)
  const ma10 = calculateMA(10, dailyData.value)
  const ma20 = calculateMA(20, dailyData.value)
  const { dif, dea, macd } = calculateMACD(dailyData.value)
  const { k, d, j } = calculateKDJ(dailyData.value)

  if (klineChart) klineChart.dispose()
  if (volumeChart) volumeChart.dispose()
  if (macdChart) macdChart.dispose()
  if (kdjChart) kdjChart.dispose()
  klineChart = null
  volumeChart = null
  macdChart = null
  kdjChart = null

  nextTick(() => {
    renderKlineChart(dates, klineData, ma5, ma10, ma20)
    if (indicators.volume && volumeChartRef.value) renderVolumeChart(dates, volumes)
    if (indicators.macd && macdChartRef.value) renderMacdChart(dates, dif, dea, macd)
    if (indicators.kdj && kdjChartRef.value) renderKdjChart(dates, k, d, j)

    const charts = [klineChart, volumeChart, macdChart, kdjChart].filter(Boolean) as echarts.ECharts[]
    if (charts.length > 1) echarts.connect(charts)
  })
}

const renderKlineChart = (dates: string[], klineData: number[][], ma5: (number | '-')[], ma10: (number | '-')[], ma20: (number | '-')[]) => {
  if (!klineChartRef.value) return
  klineChart = echarts.init(klineChartRef.value)
  klineChart.setOption({
    animation: false,
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params: any[]) => {
        const date = params[0].axisValue
        const full = date.substring(0, 4) + '-' + date.substring(4, 6) + '-' + date.substring(6, 8)
        let html = `<b>${full}</b><br/>`
        for (const p of params) {
          if (p.seriesType === 'candlestick') {
            html += `开:${p.data[1]} 收:${p.data[2]} 低:${p.data[3]} 高:${p.data[4]}<br/>`
          } else if (p.value !== '-') {
            html += `${p.marker} ${p.seriesName}: ${p.value}<br/>`
          }
        }
        return html
      }
    },
    legend: { data: ['K线', 'MA5', 'MA10', 'MA20'], top: 0, textStyle: { color: '#8b949e', fontSize: 11 } },
    grid: { left: '8%', right: '3%', top: 28, bottom: 20 },
    xAxis: { type: 'category', data: dates, boundaryGap: true, axisLine: { onZero: false }, axisLabel: { color: '#8b949e', fontSize: 10, formatter: (v: string) => v.substring(4, 6) + '-' + v.substring(6, 8) } },
    yAxis: { scale: true, splitArea: { show: true }, axisLabel: { color: '#8b949e', fontSize: 10 } },
    dataZoom: [{ type: 'inside', start: 95, end: 100 }, { show: true, type: 'slider', top: '90%', start: 95, end: 100, textStyle: { color: '#8b949e' } }],
    series: [
      { name: 'K线', type: 'candlestick', data: klineData, itemStyle: { color: UP_COLOR, color0: DOWN_COLOR, borderColor: UP_COLOR, borderColor0: DOWN_COLOR } },
      { name: 'MA5', type: 'line', data: ma5, smooth: true, showSymbol: false, lineStyle: { width: 1 }, itemStyle: { color: '#ff9800' } },
      { name: 'MA10', type: 'line', data: ma10, smooth: true, showSymbol: false, lineStyle: { width: 1 }, itemStyle: { color: '#2196f3' } },
      { name: 'MA20', type: 'line', data: ma20, smooth: true, showSymbol: false, lineStyle: { width: 1 }, itemStyle: { color: '#e91e63' } }
    ]
  })
}

const renderVolumeChart = (dates: string[], volumes: number[]) => {
  if (!volumeChartRef.value) return
  volumeChart = echarts.init(volumeChartRef.value)
  volumeChart.setOption({
    animation: false,
    tooltip: { trigger: 'axis', formatter: (params: any[]) => { const p = params[0]; return `${p.axisValue}<br/>${p.marker} 成交量: ${Number(p.value).toLocaleString()}` } },
    grid: { left: '8%', right: '3%', top: 8, bottom: 20 },
    xAxis: { type: 'category', data: dates, axisLabel: { show: false } },
    yAxis: { type: 'value', axisLabel: { color: '#8b949e', fontSize: 9, formatter: (v: number) => v >= 1e8 ? (v / 1e8).toFixed(1) + '亿' : v >= 1e4 ? (v / 1e4).toFixed(0) + '万' : v }, splitNumber: 2 },
    dataZoom: [{ type: 'inside', start: 95, end: 100 }, { show: true, type: 'slider', top: '90%', start: 95, end: 100, textStyle: { color: '#8b949e' } }],
    series: [{ name: '成交量', type: 'bar', data: volumes, itemStyle: { color: (params: any) => { const d = dailyData.value[params.dataIndex]; return Number(d.closePrice) >= Number(d.openPrice) ? UP_COLOR : DOWN_COLOR } } }]
  })
}

const renderMacdChart = (dates: string[], dif: string[], dea: string[], macd: string[]) => {
  if (!macdChartRef.value) return
  macdChart = echarts.init(macdChartRef.value)
  macdChart.setOption({
    animation: false,
    tooltip: { trigger: 'axis', formatter: (params: any[]) => { let html = params[0].axisValue + '<br/>'; for (const p of params) html += `${p.marker} ${p.seriesName}: ${p.value}<br/>`; return html } },
    legend: { data: ['DIF', 'DEA', 'MACD'], top: 0, textStyle: { color: '#8b949e', fontSize: 10 } },
    grid: { left: '8%', right: '3%', top: 18, bottom: 20 },
    xAxis: { type: 'category', data: dates, axisLabel: { show: false } },
    yAxis: { type: 'value', scale: true, axisLabel: { color: '#8b949e', fontSize: 9 }, splitNumber: 2 },
    dataZoom: [{ type: 'inside', start: 95, end: 100 }, { show: true, type: 'slider', top: '90%', start: 95, end: 100, textStyle: { color: '#8b949e' } }],
    series: [
      { name: 'DIF', type: 'line', data: dif, smooth: true, showSymbol: false, lineStyle: { width: 1 }, itemStyle: { color: '#ff9800' } },
      { name: 'DEA', type: 'line', data: dea, smooth: true, showSymbol: false, lineStyle: { width: 1 }, itemStyle: { color: '#2196f3' } },
      { name: 'MACD', type: 'bar', data: macd, itemStyle: { color: (params: any) => Number(params.data) >= 0 ? UP_COLOR : DOWN_COLOR } }
    ]
  })
}

const renderKdjChart = (dates: string[], k: number[], d: number[], j: number[]) => {
  if (!kdjChartRef.value) return
  kdjChart = echarts.init(kdjChartRef.value)
  kdjChart.setOption({
    animation: false,
    tooltip: { trigger: 'axis', formatter: (params: any[]) => { let html = params[0].axisValue + '<br/>'; for (const p of params) html += `${p.marker} ${p.seriesName}: ${p.value}<br/>`; return html } },
    legend: { data: ['K', 'D', 'J'], top: 0, textStyle: { color: '#8b949e', fontSize: 10 } },
    grid: { left: '8%', right: '3%', top: 18, bottom: 20 },
    xAxis: { type: 'category', data: dates, axisLabel: { color: '#8b949e', fontSize: 9, formatter: (v: string) => v.substring(4, 6) + '-' + v.substring(6, 8) } },
    yAxis: { type: 'value', axisLabel: { color: '#8b949e', fontSize: 9, formatter: (v: number) => v.toFixed(0) }, splitNumber: 2 },
    dataZoom: [{ type: 'inside', start: 95, end: 100 }, { show: true, type: 'slider', top: '90%', start: 95, end: 100, textStyle: { color: '#8b949e' } }],
    series: [
      { name: 'K', type: 'line', data: k, smooth: true, showSymbol: false, lineStyle: { width: 1 }, itemStyle: { color: '#ff9800' } },
      { name: 'D', type: 'line', data: d, smooth: true, showSymbol: false, lineStyle: { width: 1 }, itemStyle: { color: '#2196f3' } },
      { name: 'J', type: 'line', data: j, smooth: true, showSymbol: false, lineStyle: { width: 1 }, itemStyle: { color: '#e91e63' } }
    ]
  })
}

const loadData = async () => {
  if (!selectedStockCode.value) return
  loading.value = true
  try {
    let startDate: string | undefined
    let endDate: string | undefined
    if (dateRange.value) {
      startDate = dateRange.value[0].format('YYYYMMDD')
      endDate = dateRange.value[1].format('YYYYMMDD')
    } else {
      endDate = dayjs().format('YYYYMMDD')
      startDate = dayjs().subtract(1, 'year').format('YYYYMMDD')
    }
    const tushareCode = toTushareCode(selectedStockCode.value)
    const res = await getStockDaily(tushareCode, startDate, endDate)
    if (res.code === 200) {
      dailyData.value = res.data
      renderCharts()
    }
  } finally {
    loading.value = false
  }
}

const handleResize = () => {
  klineChart?.resize()
  volumeChart?.resize()
  macdChart?.resize()
  kdjChart?.resize()
}

// 外部调用：加载指定股票
const loadStock = async (stock: StockInfo) => {
  stockInfo.value = stock
  selectedStockCode.value = toPureCode(stock.stockCode)
  searchKeyword.value = toPureCode(stock.stockCode)
  searchOptions.value = [{ ...stock, value: toPureCode(stock.stockCode), label: `${stock.stockName} (${toPureCode(stock.stockCode)})` }]
  currentPrice.value = ''
  await loadCurrentPrice(stock.stockCode)
  await loadData()
}

defineExpose({ loadStock })

onMounted(() => {
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  klineChart?.dispose()
  volumeChart?.dispose()
  macdChart?.dispose()
  kdjChart?.dispose()
})
</script>

<style scoped>
.kline-view {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--term-panel-bg);
  border-radius: 4px;
  overflow: hidden;
}

.kline-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  background: var(--term-panel-header);
  border-bottom: 1px solid var(--term-border);
  flex-wrap: wrap;
}

.stock-label {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-left: auto;
  font-size: 13px;
}

.stock-name { font-weight: 600; color: var(--term-fg); }
.stock-code { color: var(--term-fg-muted); font-size: 12px; }
.stock-price { font-weight: 600; font-size: 14px; font-variant-numeric: tabular-nums; }

.kline-charts {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 4px;
  gap: 2px;
}

.chart {
  width: 100%;
  min-height: 0;
}
.chart-kline { flex: 3; }
.chart-volume { flex: 1; }
.chart-indicator { flex: 1; }
</style>

<style>
/* 指标选择器弹窗 */
.indicator-selector {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px 0;
  min-width: 120px;
}

.indicator-option {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 13px;
  color: #c9d1d9;
  user-select: none;
}

.indicator-option:hover {
  color: #58a6ff;
}
</style>
