<template>
  <div class="dashboard">
    <a-alert
      v-if="errorMessage"
      :message="errorMessage"
      type="warning"
      show-icon
      style="margin-bottom: 16px"
    />

    <a-row :gutter="[16, 16]" style="width: 100%;">
      <a-col :xs="24" :sm="12" :md="8" :lg="4" :xl="4">
        <a-card style="height: 100%;">
          <a-statistic title="总资产" :value="dashboardData.totalAssets || 0" :precision="2" prefix="¥" />
        </a-card>
      </a-col>
      <a-col :xs="24" :sm="12" :md="8" :lg="4" :xl="4">
        <a-card style="height: 100%;">
          <a-statistic title="盈利百分比" :value="dashboardData.profitPercentage || 0" :precision="2" suffix="%" :value-style="{ color: (dashboardData.profitPercentage || 0) >= 0 ? '#cf1322' : '#3f8600' }" />
        </a-card>
      </a-col>
      <a-col :xs="24" :sm="12" :md="8" :lg="4" :xl="4">
        <a-card style="height: 100%;">
          <a-statistic title="浮动盈亏" :value="dashboardData.floatingProfitLoss || 0" :precision="2" prefix="¥" :value-style="{ color: (dashboardData.floatingProfitLoss || 0) >= 0 ? '#cf1322' : '#3f8600' }" />
        </a-card>
      </a-col>
      <a-col :xs="24" :sm="12" :md="8" :lg="4" :xl="4">
        <a-card style="height: 100%;">
          <a-statistic title="总市值" :value="dashboardData.totalMarketValue || 0" :precision="2" prefix="¥" />
        </a-card>
      </a-col>
      <a-col :xs="24" :sm="12" :md="8" :lg="4" :xl="4">
        <a-card style="height: 100%;">
          <a-statistic title="资金余额" :value="dashboardData.cashBalance || 0" :precision="2" prefix="¥" />
        </a-card>
      </a-col>
      <a-col :xs="24" :sm="12" :md="8" :lg="4" :xl="4">
        <a-card style="height: 100%;">
          <a-statistic title="持仓数" :value="(dashboardData.positions || []).length" suffix="只" />
        </a-card>
      </a-col>
    </a-row>

    <!-- 当前持仓 - 限制高度 -->
    <a-card title="当前持仓" style="margin-top: 16px; margin-bottom: 24px; max-height: 320px; overflow: hidden; display: flex; flex-direction: column;">
      <a-spin :spinning="loading" style="overflow-y: auto; flex: 1; min-height: 0;">
        <div style="max-height: 250px; overflow-y: auto; border: 1px solid var(--term-border); border-radius: 4px;">
          <a-table
            v-if="dashboardData.positions && dashboardData.positions.length > 0"
            :columns="positionColumns"
            :data-source="dashboardData.positions"
            row-key="stockCode"
            size="small"
            :pagination="{ pageSize: 3, size: 'small', showSizeChanger: false }"
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
        </div>
        <a-empty v-if="!dashboardData.positions || dashboardData.positions.length === 0" description="暂无持仓" />
      </a-spin>
    </a-card>

    <!-- 图表区域 - 月度盈亏 + 资产曲线 -->
    <a-row :gutter="[16, 16]" style="margin-top: 16px; width: 100%;">
      <a-col :xs="24" :md="12">
        <a-card title="月度盈亏" :body-style="{ height: '260px', padding: '12px' }">
          <a-spin :spinning="loading" style="height: 100%;">
            <div v-if="monthlyData.length > 0" ref="monthlyChartRef" style="width: 100%; height: 220px;"></div>
            <a-empty v-else description="暂无月度盈亏数据" style="height: 220px; display: flex; align-items: center; justify-content: center;" />
          </a-spin>
        </a-card>
      </a-col>
      <a-col :xs="24" :md="12">
        <a-card title="资产曲线" :body-style="{ height: '260px', padding: '12px' }">
          <a-spin :spinning="loading" style="height: 100%;">
            <div v-if="assetCurveData.dates.length > 0" ref="assetChartRef" style="width: 100%; height: 220px;"></div>
            <a-empty v-else description="暂无资产曲线数据" style="height: 220px; display: flex; align-items: center; justify-content: center;" />
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
import type { DashboardData, MonthlyProfit, AssetCurve } from '@/types'

const dashboardData = ref<DashboardData>({
  totalAssets: 0,
  profitPercentage: 0,
  floatingProfitLoss: 0,
  totalMarketValue: 0,
  cashBalance: 0,
  cash: 0,
  totalCost: 0,
  totalProfitLoss: 0,
  totalProfitLossPercent: 0,
  positions: []
})
const monthlyData = ref<MonthlyProfit[]>([])
const assetCurveData = ref<AssetCurve>({
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
    xAxis: { type: 'category', data: monthlyData.value.map((i: MonthlyProfit) => i.month) },
    yAxis: { type: 'value' },
    series: [{
      data: monthlyData.value.map((i: MonthlyProfit) => i.profitLoss),
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

    dashboardData.value = dashboardRes.data || {
      totalAssets: 0,
      profitPercentage: 0,
      floatingProfitLoss: 0,
      totalMarketValue: 0,
      cashBalance: 0,
      positions: []
    }
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
  width: 100%;
  height: 100%;
  overflow-y: auto;
  padding: 16px;
  box-sizing: border-box;
}

.dashboard :deep(.ant-row) {
  width: 100%;
  margin: 0 !important;
}

.dashboard :deep(.ant-col) {
  margin-bottom: 16px;
}

.dashboard :deep(.ant-card) {
  height: 100%;
}

.dashboard :deep(.ant-statistic) {
  text-align: center;
}

/* 限制当前持仓表格高度 */
.dashboard :deep(.ant-table-wrapper) {
  max-height: 250px;
  overflow-y: auto;
}

.dashboard :deep(.ant-table-body) {
  max-height: 200px !important;
  overflow-y: auto !important;
}
</style>
