<template>
  <div class="report-page">
    <!-- 工具栏（打印时隐藏） -->
    <div class="report-toolbar no-print">
      <a-range-picker
        v-model:value="dateRange"
        value-format="YYYY-MM-DD"
        :placeholder="['开始日期', '结束日期']"
        :allow-empty="[true, true]"
      />
      <a-button type="primary" :loading="loading" @click="load">生成报告</a-button>
      <a-button :disabled="!stats" @click="exportPdf">导出 PDF</a-button>
    </div>

    <!-- 报告内容（打印区域） -->
    <div v-if="stats" id="report-content" class="report-content">
      <div class="report-header">
        <h1>复盘报告</h1>
        <p class="report-sub">{{ rangeLabel }} · 生成于 {{ generatedAt }}</p>
      </div>

      <section class="report-section">
        <h2>核心指标</h2>
        <div class="metric-grid">
          <div class="metric">
            <span class="metric-label">总交易</span>
            <span class="metric-value">{{ stats.totalTrades }}</span>
          </div>
          <div class="metric">
            <span class="metric-label">胜率</span>
            <span class="metric-value">{{ fmtPct(stats.winRate) }}</span>
          </div>
          <div class="metric">
            <span class="metric-label">总盈亏</span>
            <span class="metric-value" :class="pnlClass(stats.totalProfitLoss)">{{ fmtMoney(stats.totalProfitLoss) }}</span>
          </div>
          <div class="metric">
            <span class="metric-label">获利因子</span>
            <span class="metric-value">{{ fmtNum(stats.profitFactor) }}</span>
          </div>
          <div class="metric">
            <span class="metric-label">盈亏比</span>
            <span class="metric-value">{{ fmtNum(stats.profitLossRatio) }}</span>
          </div>
          <div class="metric">
            <span class="metric-label">平均持仓天数</span>
            <span class="metric-value">{{ fmtNum(stats.avgHoldingDays) }}</span>
          </div>
        </div>
      </section>

      <section v-if="stats.monthlyStats?.length" class="report-section">
        <h2>月度盈亏</h2>
        <table class="report-table">
          <thead>
            <tr><th>月份</th><th>笔数</th><th>胜率</th><th>盈亏</th></tr>
          </thead>
          <tbody>
            <tr v-for="m in stats.monthlyStats" :key="m.month">
              <td>{{ m.month }}</td>
              <td>{{ m.tradeCount }}</td>
              <td>{{ fmtPct(m.winRate) }}</td>
              <td :class="pnlClass(m.profitLoss)">{{ fmtMoney(m.profitLoss) }}</td>
            </tr>
          </tbody>
        </table>
      </section>

      <section v-if="stats.topProfitStocks?.length || stats.topLossStocks?.length" class="report-section">
        <h2>个股盈亏排行</h2>
        <div class="stock-cols">
          <div v-if="stats.topProfitStocks?.length" class="stock-col">
            <h3>盈利前五</h3>
            <table class="report-table">
              <thead><tr><th>股票</th><th>笔数</th><th>盈亏</th></tr></thead>
              <tbody>
                <tr v-for="s in stats.topProfitStocks" :key="s.stockCode">
                  <td>{{ s.stockName || s.stockCode }}</td>
                  <td>{{ s.tradeCount }}</td>
                  <td class="pnl-up">{{ fmtMoney(s.profitLoss) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div v-if="stats.topLossStocks?.length" class="stock-col">
            <h3>亏损前五</h3>
            <table class="report-table">
              <thead><tr><th>股票</th><th>笔数</th><th>盈亏</th></tr></thead>
              <tbody>
                <tr v-for="s in stats.topLossStocks" :key="s.stockCode">
                  <td>{{ s.stockName || s.stockCode }}</td>
                  <td>{{ s.tradeCount }}</td>
                  <td class="pnl-down">{{ fmtMoney(s.profitLoss) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </section>

      <section v-if="journals.length" class="report-section">
        <h2>复盘笔记</h2>
        <div v-for="j in journals" :key="j.id" class="journal-item">
          <div class="journal-head">
            <span class="journal-title">{{ j.title }}</span>
            <span class="journal-meta">{{ j.journalDate }} · 情绪 {{ j.emotionScore }}/5{{ j.strategyType ? ' · ' + j.strategyType : '' }}</span>
          </div>
          <p class="journal-content">{{ j.content }}</p>
          <p v-if="j.reflection" class="journal-reflection">反思：{{ j.reflection }}</p>
        </div>
      </section>
    </div>

    <div v-else class="report-empty no-print">
      <a-empty description="点击「生成报告」查看复盘总结" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { message } from 'ant-design-vue'
import { getPerformanceStats } from '@/api/analysis'
import { getJournalPage } from '@/api/journal'
import type { PerformanceStats, TradeJournal } from '@/types'

const dateRange = ref<[string, string] | null>(null)
const stats = ref<PerformanceStats | null>(null)
const journals = ref<TradeJournal[]>([])
const loading = ref(false)

const rangeLabel = computed(() => {
  if (!dateRange.value) return '全部时间'
  const [s, e] = dateRange.value
  if (!s && !e) return '全部时间'
  return `${s || '…'} 至 ${e || '…'}`
})

const generatedAt = ref('')

const load = async () => {
  loading.value = true
  try {
    const params: { startDate?: string; endDate?: string } = {}
    if (dateRange.value) {
      const [s, e] = dateRange.value
      if (s) params.startDate = s
      if (e) params.endDate = e
    }
    const [perf, journalRes] = await Promise.all([
      getPerformanceStats(params),
      getJournalPage({ ...params, pageNum: 1, pageSize: 200 })
    ])
    stats.value = perf.data
    journals.value = journalRes.data?.records ?? []
    generatedAt.value = new Date().toLocaleString('zh-CN')
  } catch (err: any) {
    message.error(err.message || '生成报告失败')
  } finally {
    loading.value = false
  }
}

const exportPdf = () => {
  window.print()
}

const fmtMoney = (v?: number | null) =>
  v == null ? '-' : `${v < 0 ? '-' : ''}¥${Math.abs(v).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`

const fmtPct = (v?: number | null) => (v == null ? '-' : `${v.toFixed(2)}%`)

const fmtNum = (v?: number | null) => (v == null ? '-' : v.toLocaleString('zh-CN', { maximumFractionDigits: 2 }))

const pnlClass = (v?: number | null) => (v == null || v === 0 ? '' : v > 0 ? 'pnl-up' : 'pnl-down')

onMounted(load)
</script>

<style scoped>
.report-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 8px;
}
.report-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.report-content {
  display: flex;
  flex-direction: column;
  gap: 24px;
}
.report-header h1 {
  margin: 0 0 4px;
  font-size: 24px;
  font-weight: 600;
}
.report-sub {
  margin: 0;
  color: var(--fg-muted, #8b8f98);
  font-size: 13px;
}
.report-section h2 {
  font-size: 16px;
  font-weight: 600;
  margin: 0 0 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--border, rgba(255, 255, 255, 0.12));
}
.report-section h3 {
  font-size: 14px;
  font-weight: 600;
  margin: 0 0 8px;
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
}
.metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px;
  border-radius: 10px;
  background: var(--bg-panel, rgba(255, 255, 255, 0.04));
  border: 1px solid var(--border, rgba(255, 255, 255, 0.08));
}
.metric-label {
  font-size: 12px;
  color: var(--fg-muted, #8b8f98);
}
.metric-value {
  font-size: 18px;
  font-weight: 600;
}
.report-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.report-table th,
.report-table td {
  text-align: left;
  padding: 8px 10px;
  border-bottom: 1px solid var(--border, rgba(255, 255, 255, 0.08));
}
.report-table th {
  color: var(--fg-muted, #8b8f98);
  font-weight: 500;
}
.stock-cols {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}
.journal-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--border, rgba(255, 255, 255, 0.08));
}
.journal-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 6px;
}
.journal-title {
  font-weight: 600;
}
.journal-meta {
  font-size: 12px;
  color: var(--fg-muted, #8b8f98);
  white-space: nowrap;
}
.journal-content {
  margin: 0 0 6px;
  white-space: pre-wrap;
  line-height: 1.6;
}
.journal-reflection {
  margin: 0;
  color: var(--fg-muted, #8b8f98);
  font-size: 13px;
}
.pnl-up {
  color: #ff4d4f;
}
.pnl-down {
  color: #52c41a;
}
.report-empty {
  padding: 48px 0;
}
</style>
