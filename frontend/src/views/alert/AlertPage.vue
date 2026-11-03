<template>
  <div class="alert-page">
    <!-- 顶部工具栏 -->
    <div class="alert-header">
      <div class="header-left">
        <h2 class="page-title"><AppIcon name="shield" :size="18" />风控预警与止盈止损</h2>
        <span class="page-subtitle">监控目标价位与涨跌幅 · 严格执行交易纪律 · 保护本金收益</span>
      </div>
      <div class="header-right">
        <a-button :loading="checking" @click="handleCheckAlerts">
          立即刷新行情检查
        </a-button>
        <a-button type="primary" @click="openCreateModal">
          新增预警规则
        </a-button>
      </div>
    </div>

    <!-- 顶部统计概览 -->
    <a-row :gutter="16" class="stats-row">
      <a-col :xs="24" :sm="8">
        <a-card class="stat-card">
          <a-statistic
            title="监控中预警"
            :value="activeCount"
            :value-style="{ color: 'var(--term-accent)' }"
            suffix="条"
          >
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :xs="24" :sm="8">
        <a-card class="stat-card">
          <a-statistic
            title="已触发预警 (需处理)"
            :value="triggeredCount"
            :value-style="{ color: 'var(--term-danger)' }"
            suffix="条"
          >
          </a-statistic>
        </a-card>
      </a-col>
      <a-col :xs="24" :sm="8">
        <a-card class="stat-card">
          <a-statistic
            title="已停用预警"
            :value="disabledCount"
            :value-style="{ color: 'var(--term-fg-muted)' }"
            suffix="条"
          >
          </a-statistic>
        </a-card>
      </a-col>
    </a-row>

    <!-- 预警规则表格卡片 -->
    <a-card class="table-card">
      <div class="table-toolbar">
        <a-radio-group v-model:value="statusFilter" button-style="solid" @change="loadAlerts">
          <a-radio-button :value="undefined">全部 ({{ alertList.length }})</a-radio-button>
          <a-radio-button :value="0">监控中 ({{ activeCount }})</a-radio-button>
          <a-radio-button :value="1">已触发 ({{ triggeredCount }})</a-radio-button>
          <a-radio-button :value="2">已停用 ({{ disabledCount }})</a-radio-button>
        </a-radio-group>
      </div>

      <a-table
        :columns="columns"
        :data-source="filteredAlerts"
        row-key="id"
        size="middle"
        :loading="loading"
        :pagination="{ pageSize: 10, showSizeChanger: true }"
      >
        <template #bodyCell="{ column, record }">
          <!-- 股票 -->
          <template v-if="column.key === 'stock'">
            <div class="stock-cell">
              <span class="stock-name">{{ record.stockName }}</span>
              <span class="stock-code">{{ record.stockCode }}</span>
            </div>
          </template>

          <!-- 预警类型 -->
          <template v-if="column.key === 'alertType'">
            <a-tag :color="getAlertTypeColor(record.alertType)">
              {{ getAlertTypeName(record.alertType) }}
            </a-tag>
          </template>

          <!-- 目标数值 -->
          <template v-if="column.key === 'targetValue'">
            <span class="target-val">
              {{ record.alertType <= 2 ? `¥${Number(record.targetValue).toFixed(2)}` : `${record.targetValue}%` }}
            </span>
          </template>

          <!-- 最新行情 -->
          <template v-if="column.key === 'currentPrice'">
            <div v-if="record.currentPrice != null" class="price-cell">
              <span class="price-val">¥{{ Number(record.currentPrice).toFixed(2) }}</span>
              <span
                v-if="record.changePct != null"
                class="pct-val"
                :style="{ color: record.changePct >= 0 ? 'var(--term-up)' : 'var(--term-down)' }"
              >
                {{ record.changePct >= 0 ? '+' : '' }}{{ Number(record.changePct).toFixed(2) }}%
              </span>
            </div>
            <span v-else class="text-muted">--</span>
          </template>

          <!-- 距目标距离 -->
          <template v-if="column.key === 'distance'">
            <div v-if="record.distancePct != null" class="distance-cell">
              <span :class="['distance-pill', record.distancePct <= 0 ? 'reached' : 'approaching']">
                {{ record.distancePct > 0 ? `还差 ${record.distancePct}%` : `已达到目标` }}
              </span>
            </div>
            <span v-else class="text-muted">--</span>
          </template>

          <!-- 状态 -->
          <template v-if="column.key === 'status'">
            <a-badge
              :status="getStatusBadge(record.status)"
              :text="getStatusText(record.status)"
            />
            <div v-if="record.status === 1 && record.triggeredTime" class="trigger-time">
              {{ record.triggeredTime.replace('T', ' ').slice(0, 16) }}
            </div>
          </template>

          <!-- 操作 -->
          <template v-if="column.key === 'action'">
            <div class="action-buttons">
              <a-button
                v-if="record.status !== 1"
                type="link"
                size="small"
                @click="handleToggleStatus(record)"
              >
                {{ record.status === 0 ? '停用' : '启用' }}
              </a-button>
              <a-button
                v-else
                type="link"
                size="small"
                @click="handleResetAlert(record)"
              >
                重新监控
              </a-button>
              <a-button type="link" size="small" @click="openEditModal(record)">编辑</a-button>
              <a-popconfirm title="确定要删除该预警吗？" @confirm="handleDelete(record.id)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </div>
          </template>
        </template>
      </a-table>
    </a-card>

    <!-- 新增 / 编辑预警弹窗 -->
    <a-modal
      v-model:open="showModal"
      :title="isEdit ? '编辑风控预警' : '新增风控预警规则'"
      width="540px"
      :confirm-loading="submitLoading"
      @ok="handleModalSubmit"
    >
      <a-form layout="vertical">
        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="股票代码" required>
              <a-input
                v-model:value="form.stockCode"
                placeholder="如 600519.SH"
                :disabled="isEdit"
                @blur="fetchStockName"
              />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="股票名称">
              <a-input v-model:value="form.stockName" placeholder="自动带出或手动填写" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="预警触发类型" required>
          <a-select v-model:value="form.alertType">
            <a-select-option :value="1"><span class="type-dot is-up" />价格止盈上限 (当前价 ≥ 目标价)</a-select-option>
            <a-select-option :value="2"><span class="type-dot is-down" />价格止损下限 (当前价 ≤ 目标价)</a-select-option>
            <a-select-option :value="3"><span class="type-dot is-up" />当日涨幅超过 % (涨幅 ≥ 目标%)</a-select-option>
            <a-select-option :value="4"><span class="type-dot is-down" />当日跌幅超过 % (跌幅 ≤ -目标%)</a-select-option>
          </a-select>
        </a-form-item>

        <a-form-item
          :label="form.alertType <= 2 ? '目标价格 (元)' : '目标涨跌幅度 (%)'"
          required
        >
          <a-input-number
            v-model:value="form.targetValue"
            :min="0.01"
            :step="form.alertType <= 2 ? 0.5 : 1"
            style="width: 100%"
            :placeholder="form.alertType <= 2 ? '如 1850.00' : '如 5.0 表示 5%'"
          />
        </a-form-item>

        <a-form-item label="风控备注 / 纪律提示">
          <a-input
            v-model:value="form.notes"
            placeholder="例如：触及止损无条件减半仓 / 压力位止盈清仓"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import AppIcon from '@/components/ui/AppIcon.vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  createAlert,
  updateAlert,
  deleteAlert,
  toggleAlertStatus,
  getAlertList,
  checkAlerts
} from '@/api/alert'
import { getStockInfo } from '@/api/stock'
import type { PriceAlert } from '@/types'

const loading = ref(false)
const checking = ref(false)
const submitLoading = ref(false)
const alertList = ref<PriceAlert[]>([])
const statusFilter = ref<number | undefined>(undefined)

const activeCount = computed(() => alertList.value.filter(a => a.status === 0).length)
const triggeredCount = computed(() => alertList.value.filter(a => a.status === 1).length)
const disabledCount = computed(() => alertList.value.filter(a => a.status === 2).length)

const filteredAlerts = computed(() => {
  if (statusFilter.value === undefined) return alertList.value
  return alertList.value.filter(a => a.status === statusFilter.value)
})

const columns = [
  { title: '股票', key: 'stock', width: 140 },
  { title: '预警类型', key: 'alertType', width: 150 },
  { title: '目标数值', key: 'targetValue', width: 120 },
  { title: '最新行情', key: 'currentPrice', width: 160 },
  { title: '触发距离', key: 'distance', width: 140 },
  { title: '状态', key: 'status', width: 130 },
  { title: '风控策略备注', dataIndex: 'notes', key: 'notes' },
  { title: '操作', key: 'action', width: 180, align: 'right' as const }
]

const showModal = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)

const form = reactive({
  stockCode: '',
  stockName: '',
  alertType: 2,
  targetValue: undefined as number | undefined,
  notes: ''
})

const getAlertTypeName = (type: number) => {
  switch (type) {
    case 1: return '价格止盈'
    case 2: return '价格止损'
    case 3: return '当日大涨'
    case 4: return '当日大跌'
    default: return '预警'
  }
}

/** 与全站一致：红涨绿跌，止盈/大涨用红，止损/大跌用绿 */
const getAlertTypeColor = (type: number) => {
  switch (type) {
    case 1: return 'red'
    case 2: return 'green'
    case 3: return 'red'
    case 4: return 'green'
    default: return 'default'
  }
}

const getStatusBadge = (status: number) => {
  switch (status) {
    case 0: return 'processing'
    case 1: return 'error'
    case 2: return 'default'
    default: return 'default'
  }
}

const getStatusText = (status: number) => {
  switch (status) {
    case 0: return '监控中'
    case 1: return '已触发'
    case 2: return '已停用'
    default: return '未知'
  }
}

const loadAlerts = async () => {
  loading.value = true
  try {
    const res = await getAlertList()
    if (res.data) {
      alertList.value = res.data || []
    }
  } catch (err: any) {
    message.error(err.message || '加载预警规则失败')
  } finally {
    loading.value = false
  }
}

const handleCheckAlerts = async () => {
  checking.value = true
  try {
    const res = await checkAlerts()
    const triggered = res.data || []
    if (triggered.length > 0) {
      message.warning(`检测到 ${triggered.length} 条预警规则已触发，请及时处理！`)
    } else {
      message.success('行情已刷新，当前无新增触发预警')
    }
    await loadAlerts()
  } catch (err: any) {
    message.error(err.message || '检查预警失败')
  } finally {
    checking.value = false
  }
}

const fetchStockName = async () => {
  if (!form.stockCode) return
  try {
    const res = await getStockInfo(form.stockCode.trim())
    if (res.data && res.data.stockName) {
      form.stockName = res.data.stockName
    }
  } catch (e) {
    // ignore
  }
}

const openCreateModal = () => {
  isEdit.value = false
  editId.value = null
  form.stockCode = ''
  form.stockName = ''
  form.alertType = 2
  form.targetValue = undefined
  form.notes = ''
  showModal.value = true
}

const openEditModal = (item: PriceAlert) => {
  isEdit.value = true
  editId.value = item.id
  form.stockCode = item.stockCode
  form.stockName = item.stockName
  form.alertType = item.alertType
  form.targetValue = item.targetValue
  form.notes = item.notes || ''
  showModal.value = true
}

const handleModalSubmit = async () => {
  if (!form.stockCode.trim()) {
    message.warning('请输入股票代码')
    return
  }
  if (!form.targetValue || form.targetValue <= 0) {
    message.warning('请输入有效的目标数值')
    return
  }

  submitLoading.value = true
  try {
    if (isEdit.value && editId.value) {
      await updateAlert(editId.value, {
        stockCode: form.stockCode,
        stockName: form.stockName,
        alertType: form.alertType,
        targetValue: form.targetValue,
        notes: form.notes
      })
      message.success('更新预警成功')
    } else {
      await createAlert({
        stockCode: form.stockCode,
        stockName: form.stockName,
        alertType: form.alertType,
        targetValue: form.targetValue,
        notes: form.notes
      })
      message.success('创建风控预警成功')
    }
    showModal.value = false
    loadAlerts()
  } catch (err: any) {
    message.error(err.message || '保存预警失败')
  } finally {
    submitLoading.value = false
  }
}

const handleToggleStatus = async (item: PriceAlert) => {
  const newStatus = item.status === 0 ? 2 : 0
  try {
    await toggleAlertStatus(item.id, newStatus)
    message.success(newStatus === 0 ? '已开启监控' : '已暂停监控')
    loadAlerts()
  } catch (err: any) {
    message.error(err.message || '操作失败')
  }
}

const handleResetAlert = async (item: PriceAlert) => {
  try {
    await toggleAlertStatus(item.id, 0)
    message.success('已重新进入监控状态')
    loadAlerts()
  } catch (err: any) {
    message.error(err.message || '重置失败')
  }
}

const handleDelete = async (id: number) => {
  try {
    await deleteAlert(id)
    message.success('已删除预警')
    loadAlerts()
  } catch (err: any) {
    message.error(err.message || '删除失败')
  }
}

onMounted(() => {
  loadAlerts()
})
</script>

<style scoped>
.alert-page {
  padding: 16px;
  height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
}

.alert-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
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
  gap: 10px;
}

.stats-row {
  margin-bottom: 16px;
}

.stat-card {
  border: 1px solid var(--term-border);
}

.table-card {
  border: 1px solid var(--term-border);
}

.table-toolbar {
  margin-bottom: 16px;
}

.stock-cell {
  display: flex;
  flex-direction: column;
}

.stock-name {
  font-weight: 600;
  color: var(--term-fg);
}

.stock-code {
  font-size: 11px;
  color: var(--term-fg-muted);
}

.target-val {
  font-weight: 600;
  font-size: 13px;
  color: var(--term-accent);
}

.price-cell {
  display: flex;
  flex-direction: column;
}

.price-val {
  font-weight: 600;
  font-size: 13px;
  color: var(--term-fg);
}

.pct-val {
  font-size: 11px;
  font-weight: 600;
}

.distance-cell {
  display: flex;
  align-items: center;
}

.distance-pill {
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 4px;
  background: var(--term-panel-header);
  border: 1px solid var(--term-border);
  color: var(--term-fg-muted);
}

.distance-pill.reached {
  background: #f8514920;
  border-color: #f8514960;
  color: var(--term-danger);
  font-weight: bold;
}

.distance-pill.approaching {
  color: var(--term-warning);
}

.trigger-time {
  font-size: 10px;
  color: var(--term-fg-muted);
  margin-top: 2px;
}

.action-buttons {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 4px;
}

.text-muted {
  color: var(--term-fg-muted);
}

/* 预警类型前的语义色点：红=涨/止盈，绿=跌/止损 */
.type-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-right: 7px;
  border-radius: 50%;
  vertical-align: 1px;
}

.type-dot.is-up {
  background: var(--term-up);
  box-shadow: 0 0 6px var(--term-glow-up);
}

.type-dot.is-down {
  background: var(--term-down);
}
</style>
