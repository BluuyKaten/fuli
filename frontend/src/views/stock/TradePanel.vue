<template>
  <div class="trade-panel">
    <div class="trade-header">
      <span class="panel-title-text">交易</span>
    </div>
    <div class="trade-body">
      <!-- 股票信息 -->
      <div class="field">
        <label>股票代码</label>
        <a-input v-model:value="form.stockCode" placeholder="选择股票" size="small" />
      </div>
      <div class="field">
        <label>股票名称</label>
        <a-input v-model:value="form.stockName" placeholder="" size="small" disabled />
      </div>
      <div class="field">
        <label>当前价格</label>
        <span class="price-display" :style="{ color: price ? '#ef5350' : '#8b949e' }">
          {{ price ? `¥${price}` : '-' }}
        </span>
      </div>

      <!-- 交易类型 -->
      <div class="field">
        <label>交易类型</label>
        <a-radio-group v-model:value="form.tradeType" button-style="solid" size="small" style="width: 100%">
          <a-radio-button :value="1" style="width: 50%">买入</a-radio-button>
          <a-radio-button :value="2" style="width: 50%">卖出</a-radio-button>
        </a-radio-group>
      </div>

      <div class="field-row">
        <div class="field">
          <label>价格</label>
          <a-input-number v-model:value="form.tradePrice" :min="0" :precision="2" :step="0.01" size="small" style="width: 100%" />
        </div>
        <div class="field">
          <label>数量</label>
          <a-input-number v-model:value="form.tradeQuantity" :min="100" :step="100" size="small" style="width: 100%" />
        </div>
      </div>

      <!-- 可用数量提示 -->
      <div v-if="form.tradeType === 2" class="hint sell-hint">
        可卖: <strong>{{ availableQuantity.toLocaleString() }}</strong> 股
      </div>
      <div v-if="form.tradeType === 1" class="hint buy-hint">
        可买: <strong>{{ maxBuyQuantity.toLocaleString() }}</strong> 股
      </div>

      <div class="amount-display">
        金额: <strong :style="{ color: form.tradeType === 1 ? '#58a6ff' : '#26a69a' }">¥{{ tradeAmount.toLocaleString() }}</strong>
      </div>

      <a-button
        :type="form.tradeType === 1 ? 'primary' : 'default'"
        :danger="form.tradeType === 2"
        block
        size="middle"
        :loading="submitting"
        @click="handleSubmit"
      >
        {{ form.tradeType === 1 ? '买入' : '卖出' }} {{ form.stockName || '股票' }}
      </a-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { getAvailableQuantity } from '@/api/stock'
import { createTrade } from '@/api/trade'
import { getProfile } from '@/api/auth'
import type { StockInfo } from '@/types'
import { toPureCode, toTushareCode } from '@/utils/stockCode'

const props = defineProps<{
  stock: StockInfo | null
  price: string
}>()

const emit = defineEmits<{
  (e: 'trade-success'): void
}>()

const form = reactive({
  stockCode: '',
  stockName: '',
  tradeType: 1,
  tradePrice: 0,
  tradeQuantity: 100
})

const availableQuantity = ref(0)
const userCash = ref(0)
const submitting = ref(false)
let userId = 0

const price = computed(() => props.price || (form.tradePrice ? String(form.tradePrice) : ''))

const tradeAmount = computed(() => {
  return (form.tradePrice || 0) * (form.tradeQuantity || 0)
})

const maxBuyQuantity = computed(() => {
  if (!form.tradePrice || form.tradePrice <= 0 || userCash.value <= 0) return 0
  return Math.max(0, Math.floor(userCash.value / form.tradePrice / 100) * 100)
})

// 股票变化时更新表单
watch(() => props.stock, async (s) => {
  if (s) {
    form.stockCode = toPureCode(s.stockCode)
    form.stockName = s.stockName
    await loadAvailableQuantity(toTushareCode(s.stockCode))
  }
})

// 价格变化
watch(() => props.price, (p) => {
  if (p) form.tradePrice = Number(p)
})

watch(() => form.tradeType, async (t) => {
  if (t === 2 && form.stockCode) await loadAvailableQuantity(toTushareCode(form.stockCode))
})

const loadAvailableQuantity = async (stockCode: string) => {
  if (!userId) {
    const res = await getProfile()
    if (res.code === 200 && res.data) userId = res.data.id
  }
  if (!userId) return
  try {
    const res = await getAvailableQuantity(userId, toTushareCode(stockCode))
    if (res.code === 200 && res.data) {
      availableQuantity.value = res.data.availableQuantity || 0
    }
  } catch { availableQuantity.value = 0 }
}

const handleSubmit = async () => {
  if (!form.stockCode || !form.stockName) { message.warning('请先选择股票'); return }
  if (!form.tradePrice || form.tradePrice <= 0) { message.warning('请输入价格'); return }
  if (!form.tradeQuantity || form.tradeQuantity <= 0) { message.warning('请输入数量'); return }

  if (form.tradeType === 2 && form.tradeQuantity > availableQuantity.value) {
    message.error(`超出可卖数量！可卖 ${availableQuantity.value.toLocaleString()} 股`)
    return
  }
  if (form.tradeType === 1 && form.tradeQuantity > maxBuyQuantity.value) {
    message.error(`超出可买数量！可买 ${maxBuyQuantity.value.toLocaleString()} 股`)
    return
  }

  submitting.value = true
  try {
    const res = await createTrade({
      stockCode: form.stockCode,
      stockName: form.stockName,
      tradeType: form.tradeType,
      tradePrice: form.tradePrice,
      tradeQuantity: form.tradeQuantity,
      tradeDate: new Date().toISOString().slice(0, 10)
    })
    if (res.code === 200) {
      message.success(`${form.tradeType === 1 ? '买入' : '卖出'}成功`)
      emit('trade-success')
      if (form.stockCode) await loadAvailableQuantity(toTushareCode(form.stockCode))
    }
  } catch (e: any) {
    message.error(e.message || '交易失败')
  } finally {
    submitting.value = false
  }
}

const loadProfile = async () => {
  const res = await getProfile()
  if (res.code === 200 && res.data) {
    userId = res.data.id
    userCash.value = res.data.cash || 0
  }
}

loadProfile()
</script>

<style scoped>
.trade-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--term-panel-bg);
  border-radius: 4px;
  overflow: hidden;
}

.trade-header {
  padding: 6px 10px;
  background: var(--term-panel-header);
  border-bottom: 1px solid var(--term-border);
}

.panel-title-text { font-size: 12px; font-weight: 600; color: var(--term-fg); }

.trade-body {
  flex: 1;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  overflow-y: auto;
}

.field { display: flex; flex-direction: column; gap: 2px; }
.field label { font-size: 11px; color: var(--term-fg-muted); }
.field-row { display: flex; gap: 8px; }
.field-row .field { flex: 1; }

.price-display { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; color: #ef5350; }

.hint { font-size: 11px; padding: 4px 6px; border-radius: 3px; background: var(--term-bg); }
.hint strong { font-variant-numeric: tabular-nums; }
.sell-hint { color: #26a69a; }
.buy-hint { color: #58a6ff; }

.amount-display {
  font-size: 12px;
  color: var(--term-fg-muted);
  padding: 4px 0;
  text-align: right;
}
.amount-display strong { font-size: 14px; font-variant-numeric: tabular-nums; }
</style>
