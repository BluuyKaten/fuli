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
      <a-col :span="4">
        <a-card>
          <a-statistic title="总资产" :value="dashboardData.totalAssets || 0" :precision="2" prefix="¥" />
        </a-card>
      </a-col>
      <a-col :span="4">
        <a-card>
          <a-statistic title="盈利百分比" :value="dashboardData.profitPercentage || 0" :precision="2" suffix="%" :value-style="{ color: (dashboardData.profitPercentage || 0) >= 0 ? '#cf1322' : '#3f8600' }" />
        </a-card>
      </a-col>
      <a-col :span="4">
        <a-card>
          <a-statistic title="浮动盈亏" :value="dashboardData.floatingProfitLoss || 0" :precision="2" prefix="¥" :value-style="{ color: (dashboardData.floatingProfitLoss || 0) >= 0 ? '#cf1322' : '#3f8600' }" />
        </a-card>
      </a-col>
      <a-col :span="4">
        <a-card>
          <a-statistic title="总市值" :value="dashboardData.totalMarketValue || 0" :precision="2" prefix="¥" />
        </a-card>
      </a-col>
      <a-col :span="4">
        <a-card>
          <a-statistic title="资金余额" :value="dashboardData.cashBalance || 0" :precision="2" prefix="¥" />
        </a-card>
      </a-col>
      <a-col :span="4">
        <a-card>
          <a-statistic title="持仓数" :value="(dashboardData.positions || []).length" suffix="只" />
        </a-card>
      </a-col>
    </a-row>

    <a-card title="当前持仓" style="margin-top: 16px">
      <a-spin :spinning="loading">
        <a-table
          v-if="dashboardData.positions && dashboardData.positions.length > 0"
          :columns="positionColumns"
          :data-source="dashboardData.positions"
          row-key="stockCode"
          size="small"
          :pagination="false"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'marketValue'">
              <span>¥{{ Number(record.marketValue).toLocaleString() }}</span>
            </template>
            <template v-if="column.key === 'dailyProfitLoss'">
              <span :style="{ color: Number(record.dailyProfitLoss) >= 0 ? '#cf1322' : '#3f8600' }">
                ¥{{ Number(record.dailyProfitLoss).toLocaleString() }}
              </span>
            </template>
            <template v-if="column.key === 'floatingProfitLoss'">
              <span :style="{ color: Number(record.floatingProfitLoss) >= 0 ? '#cf1322' : '#3f8600' }">
                ¥{{ Number(record.floatingProfitLoss).toLocaleString() }}
              </span>
            </template>
            <template v-if="column.key === 'quantity'">
              <span>{{ record.holdingQuantity }} / {{ record.availableQuantity }}</span>
            </template>
            <template v-if="column.key === 'price'">
              <span>{{ record.costPrice }} / {{ record.currentPrice }}</span>
            </template>
          </template>
        </a-table>
        <a-empty v-else description="暂无持仓" />
      </a-spin>
    </a-card>

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
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import * as echarts from 'echarts'
import { getMonthlyProfit, getAssetCurve, getDashboardData } from '@/api/analysis'

const dashboardData = ref<any>({})
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

const positionColumns = [
  { title: '股票名称', dataIndex: 'stockName', key: 'stockName' },
  { title: '市值', key: 'marketValue' },
  { title: '当日盈亏', key: 'dailyProfitLoss' },
  { title: '浮动盈亏', key: 'floatingProfitLoss' },
  { title: '持仓/可用', key: 'quantity' },
  { title: '成本/现价', key: 'price' }
]

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
    const [dashboardRes, monthlyRes, assetRes] = await Promise.all([
      getDashboardData(),
      getMonthlyProfit(),
      getAssetCurve()
    ])

    dashboardData.value = dashboardRes.data || {}
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
