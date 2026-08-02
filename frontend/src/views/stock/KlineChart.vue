<template>
  <div class="kline-container">
    <a-card title="K线图">
      <a-row :gutter="16" style="margin-bottom: 16px">
        <a-col :span="6">
          <a-select
            v-model:value="searchKeyword"
            show-search
            placeholder="搜索股票代码或名称"
            :filter-option="false"
            :options="searchOptions"
            @search="handleSearch"
            @change="handleSelectStock"
            style="width: 100%"
          />
        </a-col>
        <a-col :span="5">
          <a-range-picker v-model:value="dateRange" value-format="YYYYMMDD" />
        </a-col>
        <a-col :span="3">
          <a-button type="primary" @click="loadData" :loading="loading">查询</a-button>
        </a-col>
        <a-col :span="4">
          <a-radio-group v-model:value="tradeType" button-style="solid">
            <a-radio-button :value="1">买入</a-radio-button>
            <a-radio-button :value="2">卖出</a-radio-button>
          </a-radio-group>
        </a-col>
        <a-col :span="3">
          <a-input-number v-model:value="defaultQuantity" :min="100" :step="100" style="width: 100%" addon-after="股" />
        </a-col>
        <a-col :span="3">
          <a-button type="primary" @click="saveTradePoints" :disabled="selectedPoints.length === 0">
            保存 ({{ selectedPoints.length }})
          </a-button>
        </a-col>
      </a-row>

      <div v-if="stockInfo" style="margin-bottom: 8px">
        <span style="font-size: 18px; font-weight: bold">{{ stockInfo.stockName }} ({{ stockInfo.stockCode }})</span>
        <span style="margin-left: 16px; color: #666">{{ stockInfo.industry }} | {{ stockInfo.market }}</span>
        <span style="margin-left: 16px; color: #1677ff">可用现金: ¥{{ userCash.toLocaleString() }}</span>
      </div>

      <div ref="klineChartRef" style="height: 600px; width: 100%"></div>
      <div ref="volumeChartRef" style="height: 220px; width: 100%; margin-top: 8px"></div>
      <div ref="macdChartRef" style="height: 220px; width: 100%; margin-top: 8px"></div>
      <div ref="kdjChartRef" style="height: 220px; width: 100%; margin-top: 8px"></div>

      <a-divider />

       <div v-if="selectedPoints.length > 0">
         <h3>已选买卖点</h3>
         <a-table :columns="pointColumns" :data-source="selectedPoints" row-key="id" size="small" :pagination="false">
           <template #bodyCell="{ column, record }">
             <template v-if="column.key === 'type'">
               <a-tag :color="record.type === 1 ? 'blue' : 'green'">{{ record.type === 1 ? '买入' : '卖出' }}</a-tag>
             </template>
             <template v-if="column.key === 'price'">
               <a-input-number v-model:value="record.price" :min="0.01" :step="0.01" :precision="2" size="small" style="width: 100px" />
             </template>
             <template v-if="column.key === 'quantity'">
               <a-input-number v-model:value="record.quantity" :min="100" :step="100" size="small" style="width: 100px" />
             </template>
             <template v-if="column.key === 'amount'">
               <span :style="{ color: record.type === 1 ? '#1677ff' : '#52c41a' }">
                 ¥{{ (record.price * record.quantity).toLocaleString() }}
               </span>
             </template>
             <template v-if="column.key === 'action'">
               <a @click="removePoint(record.id)" style="color: #cf1322">删除</a>
             </template>
           </template>
         </a-table>
       </div>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { searchStocks, getStockDaily } from '@/api/stock'
import { createTrade } from '@/api/trade'
import { getProfile } from '@/api/auth'

const searchKeyword = ref('')
const searchOptions = ref<any[]>([])
const selectedStockCode = ref('')
const dateRange = ref<[dayjs.Dayjs, dayjs.Dayjs] | null>(null)
const loading = ref(false)
const stockInfo = ref<any>(null)
const dailyData = ref<any[]>([])
const selectedPoints = ref<any[]>([])
const tradeType = ref(1)
const defaultQuantity = ref(100)
const userCash = ref(0)

const klineChartRef = ref()
const volumeChartRef = ref()
const macdChartRef = ref()
const kdjChartRef = ref()

let klineChart: echarts.ECharts | null = null
let volumeChart: echarts.ECharts | null = null
let macdChart: echarts.ECharts | null = null
let kdjChart: echarts.ECharts | null = null

const pointColumns = [
  { title: '类型', key: 'type' },
  { title: '日期', dataIndex: 'date', key: 'date' },
  { title: '价格', dataIndex: 'price', key: 'price' },
  { title: '数量', key: 'quantity' },
  { title: '金额', key: 'amount' },
  { title: '操作', key: 'action' }
]

const handleSearch = async (value: string) => {
  if (!value || value.length < 1) return
  try {
    const res = await searchStocks(value)
    if (res.code === 200) {
      searchOptions.value = res.data.map((item: any) => ({
        value: item.stockCode,
        label: `${item.stockName} (${item.stockCode})`,
        ...item
      }))
    }
  } catch {
    // ignore
  }
}

const handleSelectStock = async (value: string) => {
  selectedStockCode.value = value
  const option = searchOptions.value.find((o: any) => o.value === value)
  if (option) {
    stockInfo.value = option
  }
  selectedPoints.value = []
}

const loadProfile = async () => {
  try {
    const res = await getProfile()
    if (res.code === 200 && res.data) {
      userCash.value = res.data.cash || 0
    }
  } catch {
    // ignore
  }
}

const calculateMA = (dayCount: number, data: any[]) => {
  const result: string[] = []
  for (let i = 0; i < data.length; i++) {
    if (i < dayCount - 1) {
      result.push('-')
      continue
    }
    let sum = 0
    for (let j = 0; j < dayCount; j++) {
      sum += Number(data[i - j].closePrice)
    }
    result.push((sum / dayCount).toFixed(2))
  }
  return result
}

const calculateEMA = (dayCount: number, data: number[]) => {
  const result: string[] = []
  const k = 2 / (dayCount + 1)
  for (let i = 0; i < data.length; i++) {
    if (i < dayCount - 1) {
      result.push('-')
    } else if (i === dayCount - 1) {
      let sum = 0
      for (let j = 0; j < dayCount; j++) {
        sum += data[i - j]
      }
      result.push((sum / dayCount).toFixed(3))
    } else {
      result.push((data[i] * k + Number(result[i - 1]) * (1 - k)).toFixed(3))
    }
  }
  return result
}

const calculateMACD = (data: any[]) => {
  const closes = data.map((d: any) => Number(d.closePrice))
  const ema12 = calculateEMA(12, closes)
  const ema26 = calculateEMA(26, closes)
  const dif: string[] = []
  for (let i = 0; i < ema12.length; i++) {
    if (ema12[i] === '-' || ema26[i] === '-') {
      dif.push('-')
    } else {
      dif.push((Number(ema12[i]) - Number(ema26[i])).toFixed(3))
    }
  }
  const dea: string[] = new Array(dif.length).fill('-')
  const deaK = 2 / (9 + 1)
  const validDifIndices: number[] = []
  for (let i = 0; i < dif.length; i++) {
    if (dif[i] !== '-') validDifIndices.push(i)
  }
  for (let j = 0; j < validDifIndices.length; j++) {
    const i = validDifIndices[j]
    if (j < 8) {
      // 前8个有效DIF，DEA为'-'
      continue
    } else if (j === 8) {
      // 第9个有效DIF，DEA = 前9个DIF的SMA
      let sum = 0
      for (let k = 0; k < 9; k++) {
        sum += Number(dif[validDifIndices[k]])
      }
      dea[i] = (sum / 9).toFixed(3)
    } else {
      // 第10个起，DEA = EMA公式
      const prevIdx = validDifIndices[j - 1]
      dea[i] = (Number(dif[i]) * deaK + Number(dea[prevIdx]) * (1 - deaK)).toFixed(3)
    }
  }
  const macd: string[] = []
  for (let i = 0; i < dif.length; i++) {
    if (dif[i] === '-' || dea[i] === '-') {
      macd.push('-')
    } else {
      macd.push(((Number(dif[i]) - Number(dea[i])) * 2).toFixed(3))
    }
  }
  return { dif, dea, macd }
}

const calculateKDJ = (data: any[], n = 9) => {
  const kArr: number[] = []
  const dArr: number[] = []
  const jArr: number[] = []
  for (let i = 0; i < data.length; i++) {
    if (i < n - 1) {
      kArr.push(50)
      dArr.push(50)
      jArr.push(50)
      continue
    }
    const highList = []
    const lowList = []
    for (let j = i - n + 1; j <= i; j++) {
      highList.push(Number(data[j].highPrice))
      lowList.push(Number(data[j].lowPrice))
    }
    const high = Math.max(...highList)
    const low = Math.min(...lowList)
    const close = Number(data[i].closePrice)
    const rsv = high === low ? 50 : ((close - low) / (high - low)) * 100
    const k = (2 / 3) * kArr[i - 1] + (1 / 3) * rsv
    const d = (2 / 3) * dArr[i - 1] + (1 / 3) * k
    const j = 3 * k - 2 * d
    kArr.push(parseFloat(k.toFixed(2)))
    dArr.push(parseFloat(d.toFixed(2)))
    jArr.push(parseFloat(j.toFixed(2)))
  }
  return { k: kArr, d: dArr, j: jArr }
}

const updateMarkPoints = () => {
  if (!klineChart) return
  klineChart.setOption({
    series: [{
      markPoint: {
        symbol: 'pin',
        symbolSize: 40,
        data: selectedPoints.value.map((p: any) => ({
          name: p.type === 1 ? '买入' : '卖出',
          coord: [p.date, p.price],
          value: p.type === 1 ? '买' : '卖',
          itemStyle: { color: p.type === 1 ? '#1677ff' : '#52c41a' }
        }))
      }
    }]
  })
}

const renderCharts = () => {
  if (!klineChartRef.value || dailyData.value.length === 0) return

  const dates = dailyData.value.map((d: any) => d.tradeDate)
  const klineData = dailyData.value.map((d: any) => [
    Number(d.openPrice),
    Number(d.closePrice),
    Number(d.lowPrice),
    Number(d.highPrice)
  ])
  const volumes = dailyData.value.map((d: any) => Number(d.vol))
  const ma5 = calculateMA(5, dailyData.value)
  const ma10 = calculateMA(10, dailyData.value)
  const ma20 = calculateMA(20, dailyData.value)
  const { dif, dea, macd } = calculateMACD(dailyData.value)
  const { k, d, j } = calculateKDJ(dailyData.value)

  if (klineChart) klineChart.dispose()
  if (volumeChart) volumeChart.dispose()
  if (macdChart) macdChart.dispose()
  if (kdjChart) kdjChart.dispose()

  klineChart = echarts.init(klineChartRef.value)
  volumeChart = echarts.init(volumeChartRef.value)
  macdChart = echarts.init(macdChartRef.value)
  kdjChart = echarts.init(kdjChartRef.value)

  klineChart.setOption({
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'cross' },
      formatter: (params: any[]) => {
        const date = params[0].axisValue
        const fullDate = date.substring(0, 4) + '-' + date.substring(4, 6) + '-' + date.substring(6, 8)
        let html = `<b>${fullDate}</b><br/>`
        for (const p of params) {
          if (p.seriesType === 'candlestick') {
            html += `开盘: ${p.data[1]}<br/>收盘: ${p.data[2]}<br/>最低: ${p.data[3]}<br/>最高: ${p.data[4]}<br/>`
          } else if (p.value !== '-') {
            html += `${p.marker} ${p.seriesName}: ${p.value}<br/>`
          }
        }
        return html
      }
    },
    legend: {
      data: ['K线', 'MA5', 'MA10', 'MA20'],
      top: 0
    },
    grid: {
      left: '8%',
      right: '3%',
      top: 30,
      bottom: 20
    },
    xAxis: {
      type: 'category',
      data: dates,
      boundaryGap: true,
      axisLine: { onZero: false },
      axisLabel: {
        formatter: (val: string) => val.substring(4, 6) + '-' + val.substring(6, 8)
      }
    },
    yAxis: {
      scale: true,
      splitArea: { show: true },
      axisLabel: { fontSize: 11 }
    },
    dataZoom: [
      { type: 'inside', start: 95, end: 100 },
      { show: true, type: 'slider', top: '90%', start: 95, end: 100 }
    ],
    series: [
      {
        name: 'K线',
        type: 'candlestick',
        data: klineData,
        itemStyle: {
          color: '#cf1322',
          color0: '#3f8600',
          borderColor: '#cf1322',
          borderColor0: '#3f8600'
        },
        markPoint: {
          symbol: 'pin',
          symbolSize: 40,
          data: selectedPoints.value.map((p: any) => ({
            name: p.type === 1 ? '买入' : '卖出',
            coord: [p.date, p.price],
            value: p.type === 1 ? '买' : '卖',
            itemStyle: { color: p.type === 1 ? '#1677ff' : '#52c41a' }
          }))
        }
      },
      { name: 'MA5', type: 'line', data: ma5, smooth: true, lineStyle: { width: 1.5 }, itemStyle: { color: '#ff9800' } },
      { name: 'MA10', type: 'line', data: ma10, smooth: true, lineStyle: { width: 1.5 }, itemStyle: { color: '#2196f3' } },
      { name: 'MA20', type: 'line', data: ma20, smooth: true, lineStyle: { width: 1.5 }, itemStyle: { color: '#e91e63' } }
    ]
  })

  volumeChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any[]) => {
        const p = params[0]
        const date = p.axisValue
        const fullDate = date.substring(0, 4) + '-' + date.substring(4, 6) + '-' + date.substring(6, 8)
        return `<b>${fullDate}</b><br/>${p.marker} 成交量: ${Number(p.value).toLocaleString()}`
      }
    },
    grid: { left: '8%', right: '3%', top: 15, bottom: 20 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { show: false }
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        fontSize: 10,
        formatter: (val: number) => {
          if (val >= 1e8) return (val / 1e8).toFixed(1) + '亿'
          if (val >= 1e4) return (val / 1e4).toFixed(0) + '万'
          return val
        }
      },
      splitNumber: 3
    },
    dataZoom: [
      { type: 'inside', start: 95, end: 100 },
      { show: true, type: 'slider', top: '90%', start: 95, end: 100 }
    ],
    series: [{
      name: '成交量',
      type: 'bar',
      data: volumes,
      itemStyle: {
        color: (params: any) => {
          const idx = params.dataIndex
          const d = dailyData.value[idx]
          return Number(d.closePrice) >= Number(d.openPrice) ? '#cf1322' : '#3f8600'
        }
      }
    }]
  })

  macdChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any[]) => {
        const date = params[0].axisValue
        const fullDate = date.substring(0, 4) + '-' + date.substring(4, 6) + '-' + date.substring(6, 8)
        let html = `<b>${fullDate}</b><br/>`
        for (const p of params) {
          html += `${p.marker} ${p.seriesName}: ${Number(p.value).toFixed(3)}<br/>`
        }
        return html
      }
    },
    legend: { data: ['DIF', 'DEA', 'MACD'], top: 0, textStyle: { fontSize: 11 } },
    grid: { left: '8%', right: '3%', top: 15, bottom: 20 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { show: false }
    },
    yAxis: {
      type: 'value',
      scale: true,
      axisLabel: { fontSize: 10 },
      splitNumber: 3
    },
    dataZoom: [
      { type: 'inside', start: 95, end: 100 },
      { show: true, type: 'slider', top: '90%', start: 95, end: 100 }
    ],
    series: [
      { name: 'DIF', type: 'line', data: dif, smooth: true, lineStyle: { width: 1 }, itemStyle: { color: '#ff9800' } },
      { name: 'DEA', type: 'line', data: dea, smooth: true, lineStyle: { width: 1 }, itemStyle: { color: '#2196f3' } },
      {
        name: 'MACD',
        type: 'bar',
        data: macd,
        itemStyle: {
          color: (params: any) => Number(params.data) >= 0 ? '#cf1322' : '#3f8600'
        }
      }
    ]
  })

  kdjChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any[]) => {
        const date = params[0].axisValue
        const fullDate = date.substring(0, 4) + '-' + date.substring(4, 6) + '-' + date.substring(6, 8)
        let html = `<b>${fullDate}</b><br/>`
        for (const p of params) {
          html += `${p.marker} ${p.seriesName}: ${p.value}<br/>`
        }
        return html
      }
    },
    legend: { data: ['K', 'D', 'J'], top: 0, textStyle: { fontSize: 11 } },
    grid: { left: '8%', right: '3%', top: 15, bottom: 20 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: {
        formatter: (val: string) => val.substring(4, 6) + '-' + val.substring(6, 8)
      }
    },
    yAxis: {
      type: 'value',
      axisLabel: {
        fontSize: 10,
        formatter: (val: number) => val.toFixed(0)
      },
      splitNumber: 3
    },
    dataZoom: [
      { type: 'inside', start: 95, end: 100 },
      { show: true, type: 'slider', top: '90%', start: 95, end: 100 }
    ],
    series: [
      { name: 'K', type: 'line', data: k, smooth: true, lineStyle: { width: 1 }, itemStyle: { color: '#ff9800' } },
      { name: 'D', type: 'line', data: d, smooth: true, lineStyle: { width: 1 }, itemStyle: { color: '#2196f3' } },
      { name: 'J', type: 'line', data: j, smooth: true, lineStyle: { width: 1 }, itemStyle: { color: '#e91e63' } }
    ]
  })

  echarts.connect([klineChart, volumeChart, macdChart, kdjChart])

  klineChart.off('click')
  klineChart.on('click', (params: any) => {
    if (params.componentType === 'series' && params.seriesType === 'candlestick') {
      const dataIndex = params.dataIndex
      const d = dailyData.value[dataIndex]
      const price = params.data[1]
      selectedPoints.value.push({
        id: Date.now() + Math.random(),
        type: tradeType.value,
        date: d.tradeDate,
        price: price,
        quantity: defaultQuantity.value,
        stockCode: d.stockCode
      })
      updateMarkPoints()
    }
  })
}

const downsample = (data: any[]) => {
  return data
}

const loadData = async () => {
  if (!selectedStockCode.value) {
    message.warning('请先选择股票')
    return
  }
  loading.value = true
  try {
    let startDate: string | undefined
    let endDate: string | undefined
    if (dateRange.value) {
      startDate = dateRange.value[0].format('YYYYMMDD')
      endDate = dateRange.value[1].format('YYYYMMDD')
    } else {
      endDate = dayjs().format('YYYYMMDD')
      startDate = '20050713'
    }
    const res = await getStockDaily(selectedStockCode.value, startDate, endDate)
    if (res.code === 200) {
      dailyData.value = downsample(res.data)
      await nextTick()
      renderCharts()
    }
  } finally {
    loading.value = false
  }
}

const removePoint = (id: number) => {
  selectedPoints.value = selectedPoints.value.filter((p: any) => p.id !== id)
  updateMarkPoints()
}

const saveTradePoints = async () => {
  if (selectedPoints.value.length === 0) return
  try {
    for (const point of selectedPoints.value) {
      await createTrade({
        stockCode: point.stockCode,
        stockName: stockInfo.value?.stockName || '',
        tradeType: point.type,
        tradePrice: point.price,
        tradeQuantity: point.quantity,
        tradeDate: point.date.substring(0, 4) + '-' + point.date.substring(4, 6) + '-' + point.date.substring(6, 8)
      })
    }
    message.success(`成功保存 ${selectedPoints.value.length} 条交易记录`)
    selectedPoints.value = []
    updateMarkPoints()
    loadProfile()
  } catch (error: any) {
    message.error(error.message || '保存失败')
  }
}

const handleResize = () => {
  klineChart?.resize()
  volumeChart?.resize()
  macdChart?.resize()
  kdjChart?.resize()
}

onMounted(() => {
  window.addEventListener('resize', handleResize)
  loadProfile()
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
.kline-container {
  max-width: 1400px;
  margin: 0 auto;
}
</style>
