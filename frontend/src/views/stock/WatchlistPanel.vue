<template>
  <div class="watchlist-panel">
    <div class="panel-header-row">
      <span class="panel-title-text">自选股</span>
    </div>
    <div class="watchlist-search">
      <a-input
        v-model:value="keyword"
        placeholder="搜索股票添加"
        size="small"
        allow-clear
        @change="onKeywordChange"
      />
    </div>
    <div class="watchlist-list">
      <div
        v-for="stock in displayList"
        :key="stock.stockCode"
        class="watchlist-item"
        :class="{ active: activeCode === stock.stockCode }"
        @click="selectStock(stock)"
      >
        <div class="stock-info">
          <span class="wl-name">{{ stock.stockName }}</span>
          <span class="wl-code">{{ stock.stockCode }}</span>
        </div>
        <div class="stock-extra">
          <span v-if="stock.industry" class="wl-industry">{{ stock.industry }}</span>
          <span v-if="stock.changePercent !== undefined" class="wl-change" :class="{ up: stock.changePercent >= 0, down: stock.changePercent < 0 }">
            {{ stock.changePercent >= 0 ? '+' : '' }}{{ stock.changePercent.toFixed(2) }}%
          </span>
        </div>
      </div>
      <div v-if="displayList.length === 0" class="empty-list">
        {{ keyword ? '未找到股票' : '搜索添加自选股' }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { searchStocks } from '@/api/stock'
import type { StockInfo } from '@/types'
import { getWatchlist, addToWatchlist } from '@/api/watchlist'

const emit = defineEmits<{
  (e: 'select-stock', stock: StockInfo): void
}>()

defineProps<{
  activeCode?: string
}>()

const keyword = ref('')
const searchResults = ref<StockInfo[]>([])
const watchlist = ref<StockInfo[]>([])

// 模拟一些行情数据（实际项目中应该从 WebSocket 或轮询获取）
const changeMap = ref<Record<string, number>>({})

const displayList = computed(() => {
  if (keyword.value && searchResults.value.length > 0) return searchResults.value
  return watchlist.value.map(s => ({
    ...s,
    changePercent: changeMap.value[s.stockCode]
  }))
})

// 实时搜索（防抖）
let searchTimer: ReturnType<typeof setTimeout> | null = null
const onKeywordChange = () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    handleSearch(keyword.value)
  }, 300)
}

const handleSearch = async (value: string) => {
  keyword.value = value
  if (!value) { searchResults.value = []; return }
  try {
    const res = await searchStocks(value)
    if (res.code === 200) {
      searchResults.value = res.data.slice(0, 20)
    }
  } catch { /* ignore */ }
}

const selectStock = async (stock: StockInfo) => {
  // 如果是搜索结果，加到自选列表（纯数字代码）
  if (keyword.value && searchResults.value.length > 0) {
    const pureCode = stock.stockCode.split('.')[0]
    if (!watchlist.value.find(s => s.stockCode === pureCode)) {
      watchlist.value.push({ ...stock, stockCode: pureCode })
      // 同步到后端
      try {
        await addToWatchlist({ stockCode: pureCode, stockName: stock.stockName })
      } catch { /* ignore */ }
    }
    keyword.value = ''
    searchResults.value = []
  }
  emit('select-stock', { ...stock, stockCode: stock.stockCode.split('.')[0] })
}

const loadWatchlist = async () => {
  try {
    const res = await getWatchlist()
    if (res.code === 200 && res.data) {
      watchlist.value = res.data.map((item: any) => ({
        stockCode: item.stockCode,
        stockName: item.stockName || item.stockCode,
        industry: item.industry,
        market: item.market
      }))
    }
  } catch {
    // 失败时使用默认
    watchlist.value = [
      { stockCode: '600519', stockName: '贵州茅台', industry: '白酒', market: '上海' },
      { stockCode: '000001', stockName: '平安银行', industry: '银行', market: '深圳' },
      { stockCode: '300750', stockName: '宁德时代', industry: '电池', market: '深圳' }
    ]
  }
  simulatePriceChange()
}

// 模拟行情波动（实际项目中应该从 WebSocket 或轮询获取）
const simulatePriceChange = () => {
  watchlist.value.forEach(s => {
    changeMap.value[s.stockCode] = +(Math.random() * 10 - 5).toFixed(2)
  })
}

onMounted(() => {
  loadWatchlist()
})
</script>

<style scoped>
.watchlist-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--term-panel-bg);
  border-radius: 4px;
  overflow: hidden;
}

.panel-header-row {
  padding: 6px 10px;
  background: var(--term-panel-header);
  border-bottom: 1px solid var(--term-border);
}

.panel-title-text { font-size: 12px; font-weight: 600; color: var(--term-fg); }

.watchlist-search {
  padding: 6px;
  border-bottom: 1px solid var(--term-border);
}

.watchlist-list {
  flex: 1;
  overflow-y: auto;
}

.watchlist-item {
  padding: 8px 10px;
  cursor: pointer;
  border-bottom: 1px solid var(--term-border);
  transition: background 0.1s;
}

.watchlist-item:hover { background: var(--term-hover); }
.watchlist-item.active { background: var(--term-active); border-left: 2px solid var(--term-accent); }

.stock-info {
  display: flex;
  align-items: baseline;
  gap: 6px;
}

.wl-name { font-size: 13px; color: var(--term-fg); font-weight: 500; }
.wl-code { font-size: 11px; color: var(--term-fg-muted); }

.stock-extra {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 2px;
}

.wl-industry {
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 2px;
  background: var(--term-bg);
  color: var(--term-fg-muted);
}

.wl-change {
  font-size: 11px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.wl-change.up { color: #ef5350; }
.wl-change.down { color: #26a69a; }

.empty-list {
  padding: 30px 10px;
  text-align: center;
  color: var(--term-fg-muted);
  font-size: 12px;
}
</style>
