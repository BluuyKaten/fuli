<template>
  <a-card title="交易记录">
    <template #extra>
      <a-button type="primary" @click="handleAdd">新增交易</a-button>
    </template>

    <a-form layout="inline" :model="queryForm" style="margin-bottom: 16px">
      <a-form-item label="股票代码">
        <a-input v-model:value="queryForm.stockCode" placeholder="请输入股票代码" allow-clear />
      </a-form-item>
      <a-form-item label="交易类型">
        <a-select v-model:value="queryForm.tradeType" placeholder="请选择" allow-clear style="width: 120px">
          <a-select-option :value="1">买入</a-select-option>
          <a-select-option :value="2">卖出</a-select-option>
        </a-select>
      </a-form-item>
      <a-form-item label="交易日期">
        <a-range-picker v-model:value="dateRange" />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" @click="loadData">查询</a-button>
        <a-button style="margin-left: 8px" @click="resetQuery">重置</a-button>
      </a-form-item>
    </a-form>

    <a-table :columns="columns" :data-source="dataSource" :loading="loading" :pagination="pagination" row-key="id" @change="handleTableChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'tradeType'">
          <a-tag :color="record.tradeType === 1 ? 'blue' : 'green'">{{ record.tradeTypeName }}</a-tag>
        </template>
        <template v-if="column.key === 'profitLoss'">
          <span :style="{ color: record.profitLoss >= 0 ? '#cf1322' : '#3f8600' }">
            {{ record.profitLoss != null ? record.profitLoss.toFixed(2) : '-' }}
          </span>
        </template>
        <template v-if="column.key === 'action'">
          <a-space>
            <a @click="handleEdit(record)">编辑</a>
            <a-popconfirm title="确认删除？" @confirm="handleDelete(record.id)">
              <a style="color: #cf1322">删除</a>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>
  </a-card>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import dayjs from 'dayjs'
import { getTradePage, deleteTrade, type TradeRecord, type TradeQueryParams } from '@/api/trade'

const router = useRouter()
const loading = ref(false)
const dataSource = ref<TradeRecord[]>([])
const dateRange = ref<[dayjs.Dayjs, dayjs.Dayjs] | null>(null)

const queryForm = reactive<TradeQueryParams>({
  pageNum: 1,
  pageSize: 10,
  stockCode: '',
  tradeType: undefined
})

const pagination = reactive({
  current: 1,
  pageSize: 10,
  total: 0,
  showSizeChanger: true,
  showTotal: (total: number) => `共 ${total} 条`
})

const columns = [
  { title: '股票代码', dataIndex: 'stockCode', key: 'stockCode' },
  { title: '股票名称', dataIndex: 'stockName', key: 'stockName' },
  { title: '类型', dataIndex: 'tradeType', key: 'tradeType' },
  { title: '成交价', dataIndex: 'tradePrice', key: 'tradePrice' },
  { title: '数量', dataIndex: 'tradeQuantity', key: 'tradeQuantity' },
  { title: '成交金额', dataIndex: 'tradeAmount', key: 'tradeAmount' },
  { title: '盈亏', dataIndex: 'profitLoss', key: 'profitLoss' },
  { title: '交易日期', dataIndex: 'tradeDate', key: 'tradeDate' },
  { title: '操作', key: 'action' }
]

const loadData = async () => {
  loading.value = true
  try {
    const params = { ...queryForm, pageNum: pagination.current, pageSize: pagination.pageSize }
    if (dateRange.value) {
      params.startDate = dateRange.value[0].format('YYYY-MM-DD')
      params.endDate = dateRange.value[1].format('YYYY-MM-DD')
    }
    const res = await getTradePage(params)
    if (res.code === 200) {
      dataSource.value = res.data.records
      pagination.total = res.data.total
    }
  } finally {
    loading.value = false
  }
}

const handleTableChange = (pag: { current: number; pageSize: number }) => {
  pagination.current = pag.current
  pagination.pageSize = pag.pageSize
  loadData()
}

const resetQuery = () => {
  queryForm.stockCode = ''
  queryForm.tradeType = undefined
  dateRange.value = null
  loadData()
}

const handleAdd = () => {
  router.push('/trade/add')
}

const handleEdit = (record: TradeRecord) => {
  router.push(`/trade/edit/${record.id}`)
}

const handleDelete = async (id: number) => {
  const res = await deleteTrade(id)
  if (res.code === 200) {
    ElMessage.success('删除成功')
    loadData()
  }
}

onMounted(loadData)
</script>
