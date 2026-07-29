<template>
  <div class="dashboard">
    <a-alert
      v-if="errorMessage"
      :message="errorMessage"
      type="warning"
      show-icon
      style="margin-bottom: 16px"
    />

    <a-row :gutter="16">
      <a-col :span="6">
        <a-card>
          <a-statistic title="总交易次数" :value="statistics.totalTrades || 0" />
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card>
          <a-statistic title="总盈亏" :value="statistics.totalProfitLoss || 0" :precision="2" :value-style="{ color: (statistics.totalProfitLoss || 0) >= 0 ? '#cf1322' : '#3f8600' }" />
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card>
          <a-statistic title="胜率" :value="(statistics.winRate || 0) * 100" :precision="2" suffix="%" />
        </a-card>
      </a-col>
      <a-col :span="6">
        <a-card>
          <a-statistic title="盈亏比" :value="statistics.profitLossRatio || 0" :precision="2" />
        </a-card>
      </a-col>
    </a-row>

    <a-row :gutter="16" style="margin-top: 16px">
      <a-col :span="12">
        <a-card title="月度盈亏">
          <a-spin :spinning="loading">
            <div v-if="monthlyData.length > 0" ref="monthlyChartRef" style="height: 300px"></div>
            <a-empty v-else description="暂无月度盈亏数据" style="height: 300px; display: flex; align-items: center; justify-content: center;" />
          </a-spin>
        </a-card>
      </a-col>
      <a-col :span="12">
        <a-card title="资产曲线">
          <a-spin :spinning="loading">
            <div v-if="assetCurveData.dates.length > 0" ref="assetChartRef" style="height: 300px"></div>
            <a-empty v-else description="暂无资产曲线数据" style="height: 300px; display: flex; align-items: center; justify-content: center;" />
          </a-spin>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { getAnalysisStatistics, getMonthlyProfit, getAssetCurve } from '@/api/analysis'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const statistics = ref<any>({
  totalTrades: 0,
  totalProfitLoss: 0,
  winRate: 0,
  profitLossRatio: 0
})
const monthlyData = ref<any[]>([])
const assetCurveData = ref<{ dates: string[]; assets: number[] }>({
  dates: [],
  assets: []
})
const monthlyChartRef = ref()
const assetChartRef = ref()
const loading = ref(false)
const errorMessage = ref('')

let monthlyChart: echarts.ECharts | null = null
let assetChart: echarts.ECharts | null = null

const decodeJwtPayload = (token: string) => {
  try {
    const payload = token.split('.')[1]
    if (!payload) return null
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/')
    const json = decodeURIComponent(
      atob(base64)
        .split('')
        .map((char) => `%${(`00${char.charCodeAt(0).toString(16)}`).slice(-2)}`)
        .join('')
    )
    return JSON.parse(json)
  } catch {
    return null
  }
}

const currentUserId = computed(() => {
  const token = userStore.token || localStorage.getItem('token') || ''
  const payload = token ? decodeJwtPayload(token) : null
  const userId = payload?.userId
  return typeof userId === 'number' ? userId : Number(userId) || 0
})

const destroyCharts = () => {
  monthlyChart?.dispose()
  assetChart?.dispose()
  monthlyChart = null
  assetChart = null
}

const renderMonthlyChart = async () => {
  if (!monthlyChartRef.value || monthlyData.value.length === 0) return
  await nextTick()
  monthlyChart?.dispose()
  monthlyChart = echarts.init(monthlyChartRef.value)
  monthlyChart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: monthlyData.value.map((i: any) => i.month) },
    yAxis: { type: 'value' },
    series: [{
      data: monthlyData.value.map((i: any) => i.profitLoss),
      type: 'bar',
      itemStyle: { color: (params: any) => params.value >= 0 ? '#cf1322' : '#3f8600' }
    }]
  })
}

const renderAssetChart = async () => {
  if (!assetChartRef.value || assetCurveData.value.dates.length === 0) return
  await nextTick()
  assetChart?.dispose()
  assetChart = echarts.init(assetChartRef.value)
  assetChart.setOption({
    tooltip: { trigger: 'axis' },
    xAxis: { type: 'category', data: assetCurveData.value.dates },
    yAxis: { type: 'value' },
    series: [{
      data: assetCurveData.value.assets,
      type: 'line',
      smooth: true,
      areaStyle: { opacity: 0.3 }
    }]
  })
}

const loadDashboardData = async () => {
  loading.value = true
  errorMessage.value = ''

  try {
    const [statisticsRes, monthlyRes, assetRes] = await Promise.all([
      getAnalysisStatistics(),
      getMonthlyProfit(),
      getAssetCurve()
    ])

    statistics.value = statisticsRes.data || statistics.value
    monthlyData.value = Array.isArray(monthlyRes.data) ? monthlyRes.data : []
    assetCurveData.value = assetRes.data || { dates: [], assets: [] }

    await renderMonthlyChart()
    await renderAssetChart()

    if (monthlyData.value.length === 0 && assetCurveData.value.dates.length === 0) {
      errorMessage.value = '当前账户暂无可展示的交易分析数据'
    }
  } catch (error: any) {
    errorMessage.value = error?.message || '仪表盘数据加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadDashboardData()
})

onBeforeUnmount(() => {
  destroyCharts()
})
</script>

<style scoped>
.dashboard {
  max-width: 1400px;
  margin: 0 auto;
}
</style>
