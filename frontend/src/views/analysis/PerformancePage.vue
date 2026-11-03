<template>
  <div class="performance-page">
    <!-- 顶部标题与筛选 -->
    <div class="perf-header">
      <div class="header-left">
        <h2 class="page-title"><AppIcon name="trophy" :size="18" />交易绩效多维分析</h2>
        <span class="page-subtitle">胜率 · 盈亏比 · 连胜连亏 · 持仓周期 · 盈亏分布全景透视</span>
      </div>
      <div class="header-right">
        <span class="filter-label">统计区间：</span>
        <a-range-picker
          v-model:value="dateRange"
          value-format="YYYY-MM-DD"
          style="width: 240px"
          @change="handleDateChange"
        />
        <a-button type="primary" @click="loadPerformance">刷新统计</a-button>
        <a-button @click="resetDateRange">重置区间</a-button>
      </div>
    </div>

    <a-spin :spinning="loading">
      <!-- 核心指标卡片 6 格 -->
      <a-row :gutter="[16, 16]" class="kpi-row">
        <!-- 胜率 -->
        <a-col :xs="24" :sm="12" :md="8" :lg="4">
          <a-card class="kpi-card">
            <a-statistic
              title="交易胜率"
              :value="stats.winRate"
              :precision="1"
              suffix="%"
              :value-style="{ color: stats.winRate >= 50 ? 'var(--term-up)' : 'var(--term-down)' }"
            >
            </a-statistic>
            <div class="kpi-sub">
              <span>赢 {{ stats.winCount }}</span>
              <span>·</span>
              <span>亏 {{ stats.lossCount }}</span>
              <span v-if="stats.breakEvenCount">· 平 {{ stats.breakEvenCount }}</span>
            </div>
          </a-card>
        </a-col>

        <!-- 盈亏比 -->
        <a-col :xs="24" :sm="12" :md="8" :lg="4">
          <a-card class="kpi-card">
            <a-statistic
              title="盈亏比 (均赢/均亏)"
              :value="stats.profitLossRatio"
              :precision="2"
              :value-style="{ color: stats.profitLossRatio >= 1.5 ? 'var(--term-up)' : 'var(--term-fg)' }"
            >
            </a-statistic>
            <div class="kpi-sub">
              <span>均赢 ¥{{ Number(stats.avgWinAmount).toFixed(0) }}</span>
              <span>·</span>
              <span>均亏 ¥{{ Number(stats.avgLossAmount).toFixed(0) }}</span>
            </div>
          </a-card>
        </a-col>

        <!-- 获利因子 -->
        <a-col :xs="24" :sm="12" :md="8" :lg="4">
          <a-card class="kpi-card">
            <a-statistic
              title="获利因子"
              :value="stats.profitFactor"
              :precision="2"
              :value-style="{ color: stats.profitFactor >= 1.2 ? 'var(--term-up)' : 'var(--term-down)' }"
            >
            </a-statistic>
            <div class="kpi-sub">总盈/总亏绝对值</div>
          </a-card>
        </a-col>

        <!-- 连胜/连亏极限 -->
        <a-col :xs="24" :sm="12" :md="8" :lg="4">
          <a-card class="kpi-card">
            <div class="kpi-title">连赢 / 连亏极值</div>
            <div class="streak-box">
 <span class="streak-win">{{ stats.maxConsecutiveWins }} 连赢</span>
 <span class="streak-loss">{{ stats.maxConsecutiveLosses }} 连亏</span>
            </div>
            <div class="kpi-sub">考验顺境把握与逆境回撤</div>
          </a-card>
        </a-col>

        <!-- 平均持仓天数 -->
        <a-col :xs="24" :sm="12" :md="8" :lg="4">
          <a-card class="kpi-card">
            <a-statistic
              title="平均持股天数"
              :value="stats.avgHoldingDays"
              :precision="1"
              suffix="天"
              :value-style="{ color: 'var(--term-accent)' }"
            >
            </a-statistic>
            <div class="kpi-sub">买入至卖出结算均值</div>
          </a-card>
        </a-col>

        <!-- 总实现盈亏 -->
        <a-col :xs="24" :sm="12" :md="8" :lg="4">
          <a-card class="kpi-card">
            <a-statistic
              title="已平仓总盈亏"
              :value="stats.totalProfitLoss"
              :precision="2"
              prefix="¥"
              :value-style="{ color: stats.totalProfitLoss >= 0 ? 'var(--term-up)' : 'var(--term-down)' }"
            />
            <div class="kpi-sub">共完成 {{ stats.sellCount }} 笔平仓</div>
          </a-card>
        </a-col>
      </a-row>

      <!-- 最佳/最差单笔交易对比 -->
      <a-row :gutter="16" class="extremes-row">
        <a-col :xs="24" :md="12">
          <a-card class="extreme-card best-card">
            <div class="extreme-header">
 <span class="extreme-badge win-badge">最佳盈利交易</span>
              <span v-if="stats.bestTrade" class="extreme-date">{{ stats.bestTrade.tradeDate }}</span>
            </div>
            <div v-if="stats.bestTrade" class="extreme-body">
              <div class="stock-info">
                <span class="extreme-stock-name">{{ stats.bestTrade.stockName }}</span>
                <span class="extreme-stock-code">{{ stats.bestTrade.stockCode }}</span>
              </div>
              <div class="extreme-values">
                <span class="val-pnl win">+¥{{ Number(stats.bestTrade.profitLoss).toLocaleString() }}</span>
                <span v-if="stats.bestTrade.profitLossRatio" class="val-ratio win">
                  +{{ Number(stats.bestTrade.profitLossRatio).toFixed(2) }}%
                </span>
              </div>
            </div>
            <a-empty v-else description="暂无平仓盈利记录" style="padding: 12px 0" />
          </a-card>
        </a-col>

        <a-col :xs="24" :md="12">
          <a-card class="extreme-card worst-card">
            <div class="extreme-header">
 <span class="extreme-badge loss-badge">最大亏损交易</span>
              <span v-if="stats.worstTrade" class="extreme-date">{{ stats.worstTrade.tradeDate }}</span>
            </div>
            <div v-if="stats.worstTrade" class="extreme-body">
              <div class="stock-info">
                <span class="extreme-stock-name">{{ stats.worstTrade.stockName }}</span>
                <span class="extreme-stock-code">{{ stats.worstTrade.stockCode }}</span>
              </div>
              <div class="extreme-values">
                <span class="val-pnl loss">-¥{{ Math.abs(Number(stats.worstTrade.profitLoss)).toLocaleString() }}</span>
                <span v-if="stats.worstTrade.profitLossRatio" class="val-ratio loss">
                  {{ Number(stats.worstTrade.profitLossRatio).toFixed(2) }}%
                </span>
              </div>
            </div>
            <a-empty v-else description="暂无平仓亏损记录" style="padding: 12px 0" />
          </a-card>
        </a-col>
      </a-row>

      <!-- 图表区域：收益率区间分布 + 持仓周期分布 -->
      <a-row :gutter="16" class="charts-row">
        <a-col :xs="24" :lg="12">
 <a-card title="交易单笔收益率分布" class="chart-card">
            <div ref="profitDistChartRef" class="chart-container"></div>
          </a-card>
        </a-col>
        <a-col :xs="24" :lg="12">
          <a-card title="持仓周期分布" class="chart-card">
            <div ref="holdingDistChartRef" class="chart-container"></div>
          </a-card>
        </a-col>
      </a-row>

      <!-- 月度趋势 & 个股排行 -->
      <a-row :gutter="16" class="bottom-row">
        <a-col :xs="24" :lg="14">
 <a-card title="月度盈亏与胜率走势" class="chart-card">
            <div ref="monthlyTrendChartRef" class="chart-container"></div>
          </a-card>
        </a-col>

        <a-col :xs="24" :lg="10">
 <a-card title="个股盈亏龙虎榜" class="chart-card">
            <a-tabs default-active-key="profit" size="small">
 <a-tab-pane key="profit" tab="盈利前五">
                <a-table
                  :columns="stockColumns"
                  :data-source="stats.topProfitStocks || []"
                  row-key="stockCode"
                  size="small"
                  :pagination="false"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'pnl'">
                      <span style="color: var(--term-up); font-weight: 600">
                        +¥{{ Number(record.profitLoss).toLocaleString() }}
                      </span>
                    </template>
                    <template v-if="column.key === 'winRate'">
                      <span>{{ Number(record.winRate).toFixed(1) }}%</span>
                    </template>
                  </template>
                </a-table>
              </a-tab-pane>

 <a-tab-pane key="loss" tab="亏损前五">
                <a-table
                  :columns="stockColumns"
                  :data-source="stats.topLossStocks || []"
                  row-key="stockCode"
                  size="small"
                  :pagination="false"
                >
                  <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'pnl'">
                      <span style="color: var(--term-down); font-weight: 600">
                        -¥{{ Math.abs(Number(record.profitLoss)).toLocaleString() }}
                      </span>
                    </template>
                    <template v-if="column.key === 'winRate'">
                      <span>{{ Number(record.winRate).toFixed(1) }}%</span>
                    </template>
                  </template>
                </a-table>
              </a-tab-pane>
            </a-tabs>
          </a-card>
        </a-col>
      </a-row>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import AppIcon from '@/components/ui/AppIcon.vue'
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import echarts from '@/utils/echarts'
import { getPerformanceStats } from '@/api/analysis'
import type { PerformanceStats } from '@/types'

const loading = ref(false)
const dateRange = ref<[string, string] | null>(null)

const stats = ref<PerformanceStats>({
  totalTrades: 0,
  buyCount: 0,
  sellCount: 0,
  winCount: 0,
  lossCount: 0,
  breakEvenCount: 0,
  winRate: 0,
  totalProfitLoss: 0,
  totalProfitAmount: 0,
  totalLossAmount: 0,
  profitLossRatio: 0,
  profitFactor: 0,
  avgWinAmount: 0,
  avgLossAmount: 0,
  avgTradeProfit: 0,
  maxConsecutiveWins: 0,
  maxConsecutiveLosses: 0,
  avgHoldingDays: 0,
  profitDistribution: [],
  holdingDistribution: [],
  topProfitStocks: [],
  topLossStocks: [],
  monthlyStats: []
})

const profitDistChartRef = ref<HTMLDivElement | null>(null)
const holdingDistChartRef = ref<HTMLDivElement | null>(null)
const monthlyTrendChartRef = ref<HTMLDivElement | null>(null)

let profitDistChart: echarts.ECharts | null = null
let holdingDistChart: echarts.ECharts | null = null
let monthlyTrendChart: echarts.ECharts | null = null

const stockColumns = [
  { title: '股票', dataIndex: 'stockName', key: 'stockName' },
  { title: '交易次数', dataIndex: 'tradeCount', key: 'tradeCount', width: 90 },
  { title: '胜率', key: 'winRate', width: 90 },
  { title: '累计盈亏', key: 'pnl', width: 120, align: 'right' as const }
]

const destroyCharts = () => {
  profitDistChart?.dispose()
  holdingDistChart?.dispose()
  monthlyTrendChart?.dispose()
  profitDistChart = null
  holdingDistChart = null
  monthlyTrendChart = null
}

const renderProfitDistChart = () => {
  if (!profitDistChartRef.value) return
  profitDistChart?.dispose()
  profitDistChart = echarts.init(profitDistChartRef.value)

  const items = stats.value.profitDistribution || []
  const categories = items.map(i => i.label)
  const counts = items.map(i => i.count)

  profitDistChart.setOption({
    tooltip: {
      trigger: 'axis',
      formatter: (params: any) => {
        const p = params[0]
        const item = items[p.dataIndex]
        return `${p.name}<br/>笔数: <b>${item.count}</b> 笔 (${item.percentage}%)`
      }
    },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: {
      type: 'category',
      data: categories,
      axisLabel: { color: '#8b949e', fontSize: 11, interval: 0 }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#8b949e' },
      splitLine: { lineStyle: { color: '#30363d' } }
    },
    series: [
      {
        data: counts,
        type: 'bar',
        barWidth: '40%',
        itemStyle: {
          color: (param: any) => {
            // 大赚, 中赚, 小赚 用红; 小亏, 中亏, 大亏 用绿
            return param.dataIndex <= 2 ? '#ef5350' : '#26a69a'
          },
          borderRadius: [4, 4, 0, 0]
        }
      }
    ]
  })
}

const renderHoldingDistChart = () => {
  if (!holdingDistChartRef.value) return
  holdingDistChart?.dispose()
  holdingDistChart = echarts.init(holdingDistChartRef.value)

  const items = stats.value.holdingDistribution || []
  const data = items.map(i => ({ name: i.label, value: i.count }))

  holdingDistChart.setOption({
    tooltip: {
      trigger: 'item',
      formatter: '{b}: <b>{c}</b> 笔 ({d}%)'
    },
    legend: {
      orient: 'horizontal',
      bottom: 'bottom',
      textStyle: { color: '#8b949e' }
    },
    series: [
      {
        name: '持仓周期',
        type: 'pie',
        radius: ['45%', '70%'],
        avoidLabelOverlap: false,
        itemStyle: {
          borderRadius: 4,
          borderColor: '#161b22',
          borderWidth: 2
        },
        label: { show: false },
        emphasis: {
          label: { show: true, fontSize: 13, fontWeight: 'bold', color: '#c9d1d9' }
        },
        data: data.length > 0 ? data : [{ name: '暂无数据', value: 0 }]
      }
    ]
  })
}

const renderMonthlyTrendChart = () => {
  if (!monthlyTrendChartRef.value) return
  monthlyTrendChart?.dispose()
  monthlyTrendChart = echarts.init(monthlyTrendChartRef.value)

  const items = stats.value.monthlyStats || []
  const months = items.map(i => i.month)
  const pnls = items.map(i => i.profitLoss)
  const winRates = items.map(i => i.winRate)

  monthlyTrendChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['月度盈亏 (¥)', '胜率 (%)'], textStyle: { color: '#8b949e' } },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: {
      type: 'category',
      data: months,
      axisLabel: { color: '#8b949e' }
    },
    yAxis: [
      {
        type: 'value',
        name: '盈亏 (¥)',
        nameTextStyle: { color: '#8b949e' },
        axisLabel: { color: '#8b949e' },
        splitLine: { lineStyle: { color: '#30363d' } }
      },
      {
        type: 'value',
        name: '胜率 (%)',
        min: 0,
        max: 100,
        nameTextStyle: { color: '#8b949e' },
        axisLabel: { color: '#8b949e', formatter: '{value}%' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '月度盈亏 (¥)',
        type: 'bar',
        data: pnls,
        itemStyle: {
          color: (param: any) => param.value >= 0 ? '#ef5350' : '#26a69a'
        }
      },
      {
        name: '胜率 (%)',
        type: 'line',
        yAxisIndex: 1,
        data: winRates,
        smooth: true,
        itemStyle: { color: '#58a6ff' },
        lineStyle: { width: 3 }
      }
    ]
  })
}

const loadPerformance = async () => {
  loading.value = true
  try {
    const params: { startDate?: string; endDate?: string } = {}
    if (dateRange.value && dateRange.value.length === 2) {
      params.startDate = dateRange.value[0]
      params.endDate = dateRange.value[1]
    }
    const res = await getPerformanceStats(params)
    if (res.data) {
      stats.value = res.data
      await nextTick()
      renderProfitDistChart()
      renderHoldingDistChart()
      renderMonthlyTrendChart()
    }
  } catch (err: any) {
    message.error(err.message || '加载绩效数据失败')
  } finally {
    loading.value = false
  }
}

const handleDateChange = () => {
  loadPerformance()
}

const resetDateRange = () => {
  dateRange.value = null
  loadPerformance()
}

onMounted(() => {
  loadPerformance()
})

onBeforeUnmount(() => {
  destroyCharts()
})
</script>

<style scoped>
.performance-page {
  padding: 16px;
  height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
}

.perf-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
  flex-wrap: wrap;
  gap: 12px;
}

.page-title {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--term-fg);
}

.page-subtitle {
  font-size: 12px;
  color: var(--term-fg-muted);
  margin-top: 4px;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.filter-label {
  font-size: 13px;
  color: var(--term-fg-muted);
}

/* 核心指标 6 格 */
.kpi-row {
  margin-bottom: 16px;
}

.kpi-card {
  border: 1px solid var(--term-border);
  height: 100%;
}

.kpi-title {
  font-size: 14px;
  color: var(--term-fg-muted);
  margin-bottom: 6px;
}

.kpi-sub {
  font-size: 11px;
  color: var(--term-fg-muted);
  margin-top: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
}

.streak-box {
  display: flex;
  align-items: center;
  gap: 12px;
  margin: 6px 0;
}

.streak-win {
  font-size: 16px;
  font-weight: 700;
  color: var(--term-up);
}

.streak-loss {
  font-size: 16px;
  font-weight: 700;
  color: var(--term-down);
}

/* 最佳/最差对比 */
.extremes-row {
  margin-bottom: 16px;
}

.extreme-card {
  border: 1px solid var(--term-border);
}

.best-card {
  border-left: 4px solid var(--term-up);
}

.worst-card {
  border-left: 4px solid var(--term-down);
}

.extreme-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.extreme-badge {
  font-size: 13px;
  font-weight: 600;
}

.extreme-badge.win-badge {
  color: var(--term-up);
}

.extreme-badge.loss-badge {
  color: var(--term-down);
}

.extreme-date {
  font-size: 12px;
  color: var(--term-fg-muted);
}

.extreme-body {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.stock-info {
  display: flex;
  flex-direction: column;
}

.extreme-stock-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--term-fg);
}

.extreme-stock-code {
  font-size: 12px;
  color: var(--term-fg-muted);
}

.extreme-values {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}

.val-pnl {
  font-size: 18px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.val-pnl.win { color: var(--term-up); }
.val-pnl.loss { color: var(--term-down); }

.val-ratio {
  font-size: 13px;
  font-weight: 600;
}

.val-ratio.win { color: var(--term-up); }
.val-ratio.loss { color: var(--term-down); }

/* 图表区 */
.charts-row, .bottom-row {
  margin-bottom: 16px;
}

.chart-card {
  border: 1px solid var(--term-border);
}

.chart-container {
  width: 100%;
  height: 260px;
}
</style>
