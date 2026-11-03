<template>
  <div ref="pageRef" class="dashboard">
    <PageHeader
      title="仪表盘"
      subtitle="账户总览 · 持仓结构与近期表现"
      icon="dashboard"
      :badge="marketBadge"
    >
      <template #actions>
        <a-button size="small" :loading="loading" @click="loadDashboardData">
          <AppIcon name="refresh" :size="13" style="margin-right: 5px" />
          刷新数据
        </a-button>
        <a-button size="small" type="primary" @click="router.push('/trade/add')">
          <AppIcon name="plus" :size="13" style="margin-right: 5px" />
          记一笔
        </a-button>
      </template>
    </PageHeader>

    <!-- 风控预警强提醒 -->
    <Transition name="alert-pop">
      <div v-if="triggeredAlerts.length > 0" class="risk-banner">
        <span class="risk-pulse" aria-hidden="true" />
        <div class="risk-body">
          <span class="risk-title">
            <AppIcon name="alert" :size="14" />
            风控预警
            <b>{{ triggeredAlerts.length }}</b>
            条已触发
          </span>
          <span class="risk-desc">
            {{ triggeredAlerts.map((a) => a.stockName).slice(0, 3).join('、') }}
            {{ triggeredAlerts.length > 3 ? ' 等' : '' }}触及止损/止盈线，请严格执行纪律
          </span>
        </div>
        <a-button type="primary" danger size="small" @click="router.push('/alert')">前往风控中心</a-button>
      </div>
    </Transition>

    <!-- 快捷操作 -->
    <div class="quick-bar">
      <span class="quick-label"><AppIcon name="sparkles" :size="13" /> 快捷入口</span>
      <div class="quick-links">
        <button class="quick-chip" @click="router.push('/journal')">
          <AppIcon name="notebook" :size="13" /> 写复盘笔记
        </button>
        <button class="quick-chip" @click="router.push('/alert')">
          <AppIcon name="bell" :size="13" /> 设置价格预警
        </button>
        <button class="quick-chip" @click="router.push('/performance')">
          <AppIcon name="trophy" :size="13" /> 绩效报告
        </button>
        <button class="quick-chip" @click="router.push('/practice')">
          <AppIcon name="target" :size="13" /> 历史交易练习
        </button>
      </div>
      <span v-if="errorMessage" class="quick-error">{{ errorMessage }}</span>
    </div>

    <!-- 核心指标 -->
    <section class="stat-grid">
      <StatCard
        label="总资产"
        :value="dashboardData.totalAssets || 0"
        prefix="¥"
        icon="wallet"
        dim
        hint="含市值与现金"
      />
      <StatCard
        label="盈利百分比"
        :value="dashboardData.profitPercentage || 0"
        suffix="%"
        icon="trendUp"
        :spark="profitSpark"
        hint="累计收益率"
      />
      <StatCard
        label="浮动盈亏"
        :value="dashboardData.floatingProfitLoss || 0"
        prefix="¥"
        icon="activity"
        hint="当前持仓浮动"
      />
      <StatCard
        label="总市值"
        :value="dashboardData.totalMarketValue || 0"
        prefix="¥"
        icon="layers"
        dim
        hint="持仓市值合计"
      />
      <StatCard
        label="资金余额"
        :value="dashboardData.cashBalance || 0"
        prefix="¥"
        icon="coin"
        dim
        hint="可用于交易"
      />
      <StatCard
        label="持仓数"
        :value="(dashboardData.positions || []).length"
        suffix="只"
        icon="clipboard"
        dim
        :precision="0"
        hint="当前持有标的"
      />
    </section>

    <!-- 持仓 -->
    <NeonCard class="block" title="当前持仓" icon="clipboard" :body-style="{ padding: '0' }">
      <template #extra>
        <span class="card-meta">{{ (dashboardData.positions || []).length }} 只 · 当日盈亏</span>
      </template>

      <SkeletonPanel v-if="loading && !hasPositions" :rows="4" :height="180" />
      <a-table
        v-else-if="hasPositions"
        :columns="positionColumns"
        :data-source="dashboardData.positions"
        row-key="stockCode"
        size="small"
        :pagination="{ pageSize: 5, size: 'small', showSizeChanger: false }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'stockName'">
            <span class="pos-name">{{ record.stockName }}</span>
            <span class="pos-code mono">{{ record.stockCode }}</span>
          </template>
          <template v-else-if="column.key === 'marketValue'">
            <span class="mono">¥{{ Number(record.marketValue).toLocaleString() }}</span>
          </template>
          <template v-else-if="column.key === 'dailyProfitLoss'">
            <span class="mono" :class="toneClass(record.dailyProfitLoss)">
              ¥{{ Number(record.dailyProfitLoss).toLocaleString() }}
            </span>
          </template>
          <template v-else-if="column.key === 'floatingProfitLoss'">
            <span class="mono" :class="toneClass(record.floatingProfitLoss)">
              ¥{{ Number(record.floatingProfitLoss).toLocaleString() }}
            </span>
          </template>
          <template v-else-if="column.key === 'quantity'">
            <span class="mono">{{ record.holdingQuantity }} / {{ record.availableQuantity }}</span>
          </template>
          <template v-else-if="column.key === 'price'">
            <span class="mono muted">{{ record.costPrice }}</span>
            <span class="price-arrow">→</span>
            <span class="mono">{{ record.currentPrice }}</span>
          </template>
        </template>
      </a-table>
      <a-empty v-else description="暂无持仓，先记录一笔交易吧" class="empty" />
    </NeonCard>

    <!-- 图表 -->
    <div class="chart-grid">
      <NeonCard class="block" title="月度盈亏" icon="activity" subtitle="按自然月汇总">
        <div v-show="monthlyData.length > 0" ref="monthlyChartRef" class="chart" />
        <SkeletonPanel v-if="loading && monthlyData.length === 0" :rows="5" :height="220" />
        <a-empty v-else-if="monthlyData.length === 0" description="暂无月度盈亏数据" class="empty" />
      </NeonCard>

      <NeonCard class="block" title="资产曲线" icon="trendUp" subtitle="账户净值走势" glow="violet">
        <div v-show="assetCurveData.dates.length > 0" ref="assetChartRef" class="chart" />
        <SkeletonPanel v-if="loading && assetCurveData.dates.length === 0" :rows="5" :height="220" />
        <a-empty v-else-if="assetCurveData.dates.length === 0" description="暂无资产曲线数据" class="empty" />
      </NeonCard>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import echarts from '@/utils/echarts'
import { getMonthlyProfit, getAssetCurve, getDashboardData } from '@/api/analysis'
import { getAlertList } from '@/api/alert'
import type { DashboardData, MonthlyProfit, AssetCurve, PriceAlert } from '@/types'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatCard from '@/components/ui/StatCard.vue'
import NeonCard from '@/components/ui/NeonCard.vue'
import SkeletonPanel from '@/components/ui/SkeletonPanel.vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import { alpha } from '@/theme/tokens'
import { revealIn } from '@/theme/motion'

const router = useRouter()
const pageRef = ref<HTMLElement | null>(null)

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
const assetCurveData = ref<AssetCurve>({ dates: [], assets: [] })
const triggeredAlerts = ref<PriceAlert[]>([])
const monthlyChartRef = ref<HTMLElement>()
const assetChartRef = ref<HTMLElement>()
const loading = ref(false)
const errorMessage = ref('')

let monthlyChart: echarts.ECharts | null = null
let assetChart: echarts.ECharts | null = null

const hasPositions = computed(() => (dashboardData.value.positions || []).length > 0)

/** 盈利曲线的迷你趋势线：优先用资产曲线，退化为月度盈亏 */
const profitSpark = computed(() => {
  if (assetCurveData.value.assets?.length > 2) return assetCurveData.value.assets.slice(-24)
  if (monthlyData.value.length > 2) return monthlyData.value.map((m) => m.profitLoss)
  return []
})

const marketBadge = computed(() => (loading.value ? 'SYNC' : 'LIVE'))

const positionColumns = [
  { title: '股票', dataIndex: 'stockName', key: 'stockName' },
  { title: '市值', key: 'marketValue', align: 'right' as const },
  { title: '当日盈亏', key: 'dailyProfitLoss', align: 'right' as const },
  { title: '浮动盈亏', key: 'floatingProfitLoss', align: 'right' as const },
  { title: '持仓/可用', key: 'quantity', align: 'right' as const },
  { title: '成本 / 现价', key: 'price', align: 'right' as const }
]

/** 涨跌语义：红涨绿跌 */
const toneClass = (v: unknown) => {
  const n = Number(v)
  if (!Number.isFinite(n) || n === 0) return 'muted'
  return n > 0 ? 'text-up' : 'text-down'
}

/** 从 CSS 变量读主题色，保证图表与界面同源 */
const chartColors = () => {
  const s = getComputedStyle(document.documentElement)
  const get = (name: string, fallback: string) => s.getPropertyValue(name).trim() || fallback
  return {
    up: get('--term-up', '#ff4d6d'),
    down: get('--term-down', '#00e5a0'),
    accent: get('--term-accent', '#38bdf8'),
    accent2: get('--term-accent-2', '#a78bfa'),
    border: get('--term-border', '#1e2740'),
    muted: get('--term-fg-muted', '#8b9bb4'),
    fg: get('--term-fg', '#e6edf6'),
    panel: get('--term-panel-bg', '#0f1420'),
    header: get('--term-panel-header', '#121a2b')
  }
}

const tooltipStyle = (c: ReturnType<typeof chartColors>) => ({
  backgroundColor: c.header,
  borderColor: c.border,
  borderWidth: 1,
  padding: [8, 10] as [number, number],
  textStyle: { color: c.fg, fontSize: 12 },
  extraCssText: 'box-shadow: 0 8px 24px rgba(0,0,0,.5); border-radius: 6px;'
})

const renderMonthlyChart = async () => {
  if (!monthlyChartRef.value || monthlyData.value.length === 0) return
  await nextTick()
  monthlyChart?.dispose()
  monthlyChart = echarts.init(monthlyChartRef.value)
  const c = chartColors()

  monthlyChart.setOption({
    animationDuration: 900,
    animationEasing: 'cubicOut',
    grid: { left: 6, right: 12, top: 18, bottom: 4, containLabel: true },
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: alpha(c.accent, 0.06) } },
      ...tooltipStyle(c)
    },
    xAxis: {
      type: 'category',
      data: monthlyData.value.map((i: MonthlyProfit) => i.month),
      axisLine: { lineStyle: { color: c.border } },
      axisTick: { show: false },
      axisLabel: { color: c.muted, fontSize: 10.5 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { color: c.border, type: 'dashed', opacity: 0.55 } },
      axisLabel: { color: c.muted, fontSize: 10.5 }
    },
    series: [
      {
        type: 'bar',
        data: monthlyData.value.map((i: MonthlyProfit) => i.profitLoss),
        barWidth: '46%',
        itemStyle: {
          borderRadius: [3, 3, 0, 0],
          color: (params: any) => {
            const isUp = params.value >= 0
            const base = isUp ? c.up : c.down
            return new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: alpha(base, 0.95) },
              { offset: 1, color: alpha(base, 0.25) }
            ])
          },
          shadowBlur: 12,
          shadowColor: alpha(c.up, 0.18)
        }
      }
    ]
  })
}

const renderAssetChart = async () => {
  if (!assetChartRef.value || assetCurveData.value.dates.length === 0) return
  await nextTick()
  assetChart?.dispose()
  assetChart = echarts.init(assetChartRef.value)
  const c = chartColors()

  assetChart.setOption({
    animationDuration: 1100,
    animationEasing: 'cubicOut',
    grid: { left: 6, right: 12, top: 18, bottom: 4, containLabel: true },
    tooltip: { trigger: 'axis', ...tooltipStyle(c) },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: assetCurveData.value.dates,
      axisLine: { lineStyle: { color: c.border } },
      axisTick: { show: false },
      axisLabel: { color: c.muted, fontSize: 10.5 }
    },
    yAxis: {
      type: 'value',
      scale: true,
      splitLine: { lineStyle: { color: c.border, type: 'dashed', opacity: 0.55 } },
      axisLabel: { color: c.muted, fontSize: 10.5 }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        symbol: 'none',
        data: assetCurveData.value.assets,
        lineStyle: {
          width: 2,
          color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
            { offset: 0, color: c.accent },
            { offset: 1, color: c.accent2 }
          ]),
          shadowColor: alpha(c.accent, 0.55),
          shadowBlur: 14,
          shadowOffsetY: 4
        },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: alpha(c.accent, 0.3) },
            { offset: 0.6, color: alpha(c.accent2, 0.08) },
            { offset: 1, color: alpha(c.accent, 0) }
          ])
        }
      }
    ]
  })
}

const resizeCharts = () => {
  monthlyChart?.resize()
  assetChart?.resize()
}

const destroyCharts = () => {
  monthlyChart?.dispose()
  assetChart?.dispose()
  monthlyChart = null
  assetChart = null
}

const loadDashboardData = async () => {
  loading.value = true
  errorMessage.value = ''

  try {
    const [dashboardRes, monthlyRes, assetRes, alertRes] = await Promise.all([
      getDashboardData(),
      getMonthlyProfit(),
      getAssetCurve(),
      getAlertList(1).catch(() => ({ data: [] }))
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
    triggeredAlerts.value = alertRes?.data || []

    await renderMonthlyChart()
    await renderAssetChart()

    if (monthlyData.value.length === 0 && assetCurveData.value.dates.length === 0) {
      errorMessage.value = '当前账户暂无可展示的交易分析数据'
    }
  } catch (error: any) {
    errorMessage.value = error?.response
      ? error?.message || '仪表盘数据加载失败'
      : '无法连接后端服务，请确认网关已启动'
  } finally {
    loading.value = false
    // 数据落地后让卡片依次入场
    await nextTick()
    revealIn(pageRef.value, '.stat-grid > *, .block', { stagger: 0.05, delay: 0.02 })
  }
}

onMounted(() => {
  loadDashboardData()
  window.addEventListener('resize', resizeCharts)
})

onBeforeUnmount(() => {
  destroyCharts()
  window.removeEventListener('resize', resizeCharts)
})
</script>

<style scoped>
.dashboard {
  width: 100%;
  min-height: 100%;
  padding: 14px 16px 24px;
}

/* ============ 风控横幅 ============ */
.risk-banner {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  padding: 10px 14px;
  border-radius: var(--term-radius);
  border: 1px solid rgba(244, 63, 94, 0.45);
  background: linear-gradient(90deg, rgba(244, 63, 94, 0.14), rgba(244, 63, 94, 0.03));
  box-shadow: 0 0 26px rgba(244, 63, 94, 0.12);
  position: relative;
  overflow: hidden;
}

.risk-banner::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, rgba(244, 63, 94, 0.16), transparent);
  width: 40%;
  animation: neon-sweep 3.6s linear infinite;
  pointer-events: none;
}

.risk-pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--term-danger);
  box-shadow: 0 0 12px rgba(244, 63, 94, 0.9);
  animation: neon-breathe 1.4s var(--term-ease) infinite;
  flex: 0 0 auto;
}

.risk-body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.risk-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #ffd9de;
}

.risk-title b {
  color: #fff;
  font-family: var(--term-font-mono);
}

.risk-desc {
  font-size: 11.5px;
  color: rgba(255, 217, 222, 0.72);
}

.alert-pop-enter-active,
.alert-pop-leave-active {
  transition: opacity var(--term-dur), transform var(--term-dur) var(--term-ease);
}

.alert-pop-enter-from,
.alert-pop-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

/* ============ 快捷入口 ============ */
.quick-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 14px;
  padding: 8px 12px;
  border-radius: var(--term-radius);
  border: 1px solid var(--term-border);
  background: rgba(255, 255, 255, 0.015);
  flex-wrap: wrap;
}

.quick-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  letter-spacing: 0.5px;
  color: var(--term-fg-dim);
  white-space: nowrap;
}

.quick-links {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.quick-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 27px;
  padding: 0 10px;
  font-family: inherit;
  font-size: 12px;
  color: var(--term-fg-muted);
  background: rgba(255, 255, 255, 0.025);
  border: 1px solid var(--term-border);
  border-radius: 999px;
  cursor: pointer;
  transition: all var(--term-dur-fast) var(--term-ease);
}

.quick-chip:hover {
  color: var(--term-accent);
  border-color: rgba(56, 189, 248, 0.45);
  background: var(--term-active);
  box-shadow: 0 0 16px rgba(56, 189, 248, 0.14);
  transform: translateY(-1px);
}

.quick-error {
  margin-left: auto;
  font-size: 11.5px;
  color: var(--term-warning);
}

/* ============ 指标网格 ============ */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(184px, 1fr));
  gap: 12px;
  margin-top: 14px;
}

/* ============ 区块 ============ */
.block {
  margin-top: 14px;
}

.card-meta {
  font-size: 11px;
  color: var(--term-fg-dim);
}

.pos-name {
  color: var(--term-fg);
  margin-right: 6px;
}

.pos-code {
  font-size: 10.5px;
  color: var(--term-fg-dim);
}

.price-arrow {
  color: var(--term-fg-dim);
  margin: 0 4px;
  font-size: 10px;
}

.muted {
  color: var(--term-fg-muted);
}

.chart {
  width: 100%;
  height: 232px;
}

.chart-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}

.empty {
  padding: 42px 0;
}

@media (min-width: 1080px) {
  .chart-grid {
    grid-template-columns: 1fr 1fr;
  }

  .chart-grid > .block {
    margin-top: 14px;
  }
}

@media (max-width: 640px) {
  .dashboard {
    padding: 12px 10px 20px;
  }

  .quick-label {
    display: none;
  }
}
</style>
