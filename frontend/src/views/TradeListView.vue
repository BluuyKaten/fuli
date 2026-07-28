<template>
  <a-card title="交易记录">
    <a-table :columns="columns" :data-source="rows" :pagination="false" row-key="id" />
  </a-card>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import http from '../api/http'
import type { Result } from '../types/result'

interface TradeRecord {
  id: number
  symbol: string
  side: string
  quantity: number
  price: number
  profitLoss: number
  tradeTime: string
}

interface PageResult<T> {
  total: number
  page: number
  size: number
  records: T[]
}

const rows = ref<TradeRecord[]>([])
const columns = [
  { title: 'ID', dataIndex: 'id' },
  { title: '标的', dataIndex: 'symbol' },
  { title: '方向', dataIndex: 'side' },
  { title: '数量', dataIndex: 'quantity' },
  { title: '价格', dataIndex: 'price' },
  { title: '盈亏', dataIndex: 'profitLoss' },
  { title: '交易时间', dataIndex: 'tradeTime' },
]

onMounted(async () => {
  const { data } = await http.get<Result<PageResult<TradeRecord>>>('/api/trade/records?page=1&size=20')
  rows.value = data.data?.records || []
})
</script>
