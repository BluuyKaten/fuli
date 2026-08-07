<template>
  <div class="orderbook-panel">
    <div class="panel-header-row">
      <span class="panel-title-text">5 档盘口</span>
      <span v-if="quote" class="quote-code">{{ quote.stockCode }}</span>
    </div>
    <div v-if="quote" class="orderbook-content">
      <!-- 卖盘 5 档 -->
      <div class="ob-row ob-ask" v-for="(item, i) in asks" :key="'ask' + i">
        <span class="ob-label">卖{{ i + 1 }}</span>
        <span class="ob-price down">{{ item.price }}</span>
        <span class="ob-vol">{{ formatVol(item.vol) }}</span>
      </div>

      <!-- 分隔线 -->
      <div class="ob-divider">
        <span class="ob-last-price" :class="{ up: isUp, down: !isUp }">
          {{ quote.price }}
        </span>
        <span class="ob-change" :class="{ up: isUp, down: !isUp }">
          {{ changePercent }}%
        </span>
      </div>

      <!-- 买盘 5 档 -->
      <div class="ob-row ob-bid" v-for="(item, i) in bids" :key="'bid' + i">
        <span class="ob-label">买{{ i + 1 }}</span>
        <span class="ob-price up">{{ item.price }}</span>
        <span class="ob-vol">{{ formatVol(item.vol) }}</span>
      </div>
    </div>
    <div v-else class="empty-book">暂无行情</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getStockQuote } from '@/api/kline'
import { useRealtimeQuote } from '@/composables/useRealtimeQuote'

const props = defineProps<{
  stockCode: string  // 纯数字
}>()

interface QuoteType {
  stockCode: string
  price: number
  preClose: number
  orderBook: {
    bids: { prices: number[]; volumes: number[] }
    asks: { prices: number[]; volumes: number[] }
  }
}

const quote = ref<QuoteType | null>(null)
const { onQuote } = useRealtimeQuote()

// 计算涨跌
const isUp = computed(() => {
  if (!quote.value || !quote.value.preClose) return true
  return quote.value.price >= quote.value.preClose
})

const changePercent = computed(() => {
  if (!quote.value || !quote.value.preClose) return '0.00'
  const change = ((quote.value.price - quote.value.preClose) / quote.value.preClose) * 100
  return (isUp.value ? '+' : '') + change.toFixed(2)
})

// 组装买卖盘数据
const asks = computed(() => {
  if (!quote.value) return []
  const { prices, volumes } = quote.value.orderBook.asks
  return prices.map((price, i) => ({ price: price?.toFixed(2) || '-', vol: volumes[i] || 0 })).reverse()
})

const bids = computed(() => {
  if (!quote.value) return []
  const { prices, volumes } = quote.value.orderBook.bids
  return prices.map((price, i) => ({ price: price?.toFixed(2) || '-', vol: volumes[i] || 0 }))
})

const formatVol = (vol: number) => {
  if (!vol) return '-'
  if (vol >= 10000) return (vol / 10000).toFixed(1) + '万'
  return vol.toLocaleString()
}

const loadQuote = async () => {
  if (!props.stockCode) return
  try {
    const res = await getStockQuote(props.stockCode)
    if (res.code === 200 && res.data) {
      quote.value = res.data
    }
  } catch (e) { /* ignore */ }
}

onMounted(() => {
  loadQuote()
  // 订阅实时行情
  onQuote((q) => {
    if (q.code === props.stockCode && quote.value) {
      quote.value.price = q.price
    }
  })
})
</script>

<style scoped>
.orderbook-panel {
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
.quote-code { font-size: 11px; color: var(--term-fg-muted); }

.orderbook-content {
  flex: 1;
  padding: 4px 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  overflow-y: auto;
}

.ob-row {
  display: flex;
  align-items: center;
  padding: 4px 0;
  font-size: 11px;
}

.ob-label {
  width: 32px;
  color: var(--term-fg-muted);
}

.ob-price {
  flex: 1;
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

.ob-vol {
  flex: 1;
  text-align: right;
  color: var(--term-fg-muted);
  font-variant-numeric: tabular-nums;
}

.ob-price.up { color: #ef5350; }
.ob-price.down { color: #26a69a; }

.ob-divider {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 0;
  margin: 4px 0;
  border-top: 1px solid var(--term-border);
  border-bottom: 1px solid var(--term-border);
}

.ob-last-price {
  font-size: 16px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.ob-change {
  font-size: 12px;
  font-weight: 600;
}

.up { color: #ef5350; }
.down { color: #26a69a; }

.empty-book {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--term-fg-muted);
  font-size: 12px;
}
</style>
