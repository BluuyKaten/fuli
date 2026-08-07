<template>
  <div class="position-panel">
    <div class="panel-header-row">
      <span class="panel-title-text">当前持仓</span>
      <span class="cash-info">现金: ¥{{ cash.toLocaleString() }}</span>
    </div>
    <div class="position-list">
      <table v-if="positions.length > 0" class="position-table">
        <thead>
          <tr>
            <th>股票</th>
            <th style="text-align: right">持仓</th>
            <th style="text-align: right">成本</th>
            <th style="text-align: right">现价</th>
            <th style="text-align: right">盈亏</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="pos in positions"
            :key="pos.stockCode"
            :class="{ active: activeCode === pos.stockCode }"
            @click="emit('select-stock', { stockCode: pos.stockCode, stockName: pos.stockName })"
          >
            <td class="pos-name">
              <div>{{ pos.stockName }}</div>
              <div class="pos-code">{{ pos.stockCode }}</div>
            </td>
            <td style="text-align: right">{{ pos.holdingQuantity.toLocaleString() }}</td>
            <td style="text-align: right">{{ pos.avgCost?.toFixed(2) || '-' }}</td>
            <td style="text-align: right">{{ pos.currentPrice?.toFixed(2) || '-' }}</td>
            <td style="text-align: right" :class="{ up: (pos.profitLoss || 0) >= 0, down: (pos.profitLoss || 0) < 0 }">
              {{ pos.profitLoss != null ? (pos.profitLoss >= 0 ? '+' : '') + pos.profitLoss.toFixed(2) : '-' }}
            </td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-list">暂无持仓</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getDashboardData } from '@/api/analysis'
import type { PositionVO } from '@/types'

defineProps<{
  activeCode?: string
}>()

const emit = defineEmits<{
  (e: 'select-stock', stock: { stockCode: string; stockName: string }): void
}>()

const positions = ref<PositionVO[]>([])
const cash = ref(0)

const loadData = async () => {
  try {
    const res = await getDashboardData()
    if (res.code === 200 && res.data) {
      positions.value = res.data.positions || []
      cash.value = res.data.cash || 0
    }
  } catch (e) {
    console.error('[PositionPanel] 加载失败:', e)
  }
}

onMounted(loadData)
defineExpose({ refresh: loadData })
</script>

<style scoped>
.position-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--term-panel-bg);
  border-radius: 4px;
  overflow: hidden;
}

.panel-header-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: var(--term-panel-header);
  border-bottom: 1px solid var(--term-border);
}

.panel-title-text { font-size: 12px; font-weight: 600; color: var(--term-fg); }
.cash-info { font-size: 11px; color: var(--term-fg-muted); font-variant-numeric: tabular-nums; }

.position-list {
  flex: 1;
  overflow-y: auto;
}

.position-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;
}

.position-table th {
  position: sticky;
  top: 0;
  background: var(--term-panel-header);
  color: var(--term-fg-muted);
  font-weight: 500;
  padding: 5px 6px;
  text-align: left;
  border-bottom: 1px solid var(--term-border);
}

.position-table td {
  padding: 5px 6px;
  color: var(--term-fg);
  border-bottom: 1px solid var(--term-border);
  font-variant-numeric: tabular-nums;
}

.position-table tbody tr {
  cursor: pointer;
  transition: background 0.1s;
}

.position-table tbody tr:hover { background: var(--term-hover); }
.position-table tbody tr.active { background: var(--term-active); }

.pos-name { font-weight: 500; }
.pos-code { font-size: 10px; color: var(--term-fg-muted); }

.up { color: #ef5350; font-weight: 600; }
.down { color: #26a69a; font-weight: 600; }

.empty-list {
  padding: 30px 10px;
  text-align: center;
  color: var(--term-fg-muted);
  font-size: 12px;
}
</style>
