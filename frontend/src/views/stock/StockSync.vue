<template>
  <div class="sync-container">
    <a-card title="数据同步">
      <a-tabs v-model:activeKey="activeTab">
        <a-tab-pane key="auto" tab="一键智能同步">
          <a-space direction="vertical" style="width: 100%">
            <a-alert
              type="info"
              show-icon
              description="自动检测所有股票的最新数据日期，只同步缺失的数据，避免重复更新。支持单只股票或全部股票。"
            />

            <a-row :gutter="16" align="middle" style="margin-top: 16px">
              <a-col :span="6">
                <a-input
                  v-model:value="tsCode"
                  placeholder="股票代码（留空同步全部）"
                  allow-clear
                />
              </a-col>
              <a-col :span="6">
                <a-range-picker
                  v-model:value="dateRange"
                  value-format="YYYYMMDD"
                  placeholder="['开始日期', '结束日期']"
                  style="width: 100%"
                />
              </a-col>
              <a-col :span="4">
                <a-button
                  type="primary"
                  size="large"
                  :loading="syncingAuto"
                  @click="handleAutoSync"
                  style="width: 100%"
                >
                  <template #icon><CloudUploadOutlined /></template>
                  {{ tsCode ? '同步该股票' : '同步全部股票' }}
                </a-button>
              </a-col>
              <a-col :span="3">
                <a-button :loading="checkingStatus" @click="handleCheckStatus">
                  检查状态
                </a-button>
              </a-col>
            </a-row>

            <a-descriptions v-if="syncStatus" bordered :column="2" size="small" style="margin-top: 16px">
              <a-descriptions-item label="股票代码">{{ syncStatus.tsCode || '全部' }}</a-descriptions-item>
              <a-descriptions-item label="最新数据日期">{{ syncStatus.latestTradeDate || '无数据' }}</a-descriptions-item>
              <a-descriptions-item label="缺失天数">
                <a-tag :color="syncStatus.missingDays > 0 ? 'orange' : 'green'">
                  {{ syncStatus.missingDays > 0 ? `${syncStatus.missingDays} 天` : '已是最新' }}
                </a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="状态">
                <a-tag :color="getStatusColor(syncStatus.status)">
                  {{ getStatusText(syncStatus.status) }}
                </a-tag>
              </a-descriptions-item>
            </a-descriptions>

            <a-progress
              v-if="syncingAuto"
              :percent="syncProgress"
              status="active"
              style="margin-top: 16px"
            />

            <a-result
              v-if="autoResult"
              :status="autoResult.status === 'success' ? 'success' : 'error'"
              :title="autoResult.status === 'success' ? '同步成功' : '同步失败'"
              :sub-title="autoResult.status === 'success'
                ? `共同步 ${autoResult.count} 条日线数据`
                : autoResult.message"
            />
          </a-space>
        </a-tab-pane>

        <a-tab-pane key="basic" tab="股票基础信息">
          <a-space direction="vertical" style="width: 100%">
            <a-alert
              type="info"
              show-icon
              description="从Tushare同步所有上市股票的基础信息（代码、名称、行业、地区等）"
            />
            <a-button type="primary" :loading="syncingBasic" @click="handleSyncBasic">
              <template #icon><CloudUploadOutlined /></template>
              同步股票基础信息
            </a-button>
            <a-result
              v-if="basicResult"
              :status="basicResult.status === 'success' ? 'success' : 'error'"
              :title="basicResult.status === 'success' ? '同步成功' : '同步失败'"
              :sub-title="basicResult.status === 'success'
                ? `共同步 ${basicResult.count} 条股票基础信息`
                : basicResult.message"
            />
          </a-space>
        </a-tab-pane>

        <a-tab-pane key="date" tab="按日期同步">
          <a-space direction="vertical" style="width: 100%">
            <a-alert
              type="info"
              show-icon
              description="按交易日期同步所有股票的日线行情数据"
            />
            <a-row :gutter="16" align="middle">
              <a-col :span="8">
                <a-date-picker
                  v-model:value="dailyDate"
                  placeholder="选择交易日期"
                  value-format="YYYYMMDD"
                  style="width: 100%"
                />
              </a-col>
              <a-col :span="4">
                <a-button type="primary" :loading="syncingDaily" @click="handleSyncDailyByDate">
                  同步
                </a-button>
              </a-col>
            </a-row>
            <a-result
              v-if="dailyDateResult"
              :status="dailyDateResult.status === 'success' ? 'success' : 'error'"
              :title="dailyDateResult.status === 'success' ? '同步成功' : '同步失败'"
              :sub-title="dailyDateResult.status === 'success'
                ? `共同步 ${dailyDateResult.count} 条日线数据`
                : dailyDateResult.message"
            />
          </a-space>
        </a-tab-pane>
      </a-tabs>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CloudUploadOutlined } from '@ant-design/icons-vue'
import { syncStockBasic, syncDailyByDate, syncDailyByRange, syncAllIncremental, getSyncStatus } from '@/api/stock'

const activeTab = ref('auto')
const syncingBasic = ref(false)
const syncingDaily = ref(false)
const syncingAuto = ref(false)
const checkingStatus = ref(false)

const basicResult = ref<{ status: string; count?: number; message?: string } | null>(null)
const dailyDateResult = ref<{ status: string; count?: number; message?: string } | null>(null)
const autoResult = ref<{ status: string; count?: number; message?: string } | null>(null)
const syncStatus = ref<any>(null)

const dailyDate = ref<string>('')
const tsCode = ref('')
const dateRange = ref<any>(null)
const syncProgress = ref(0)

const handleAutoSync = async () => {
  syncingAuto.value = true
  autoResult.value = null
  syncProgress.value = 0
  try {
    let rangeStart: string | undefined
    let rangeEnd: string | undefined
    if (dateRange.value && dateRange.value.length === 2) {
      rangeStart = typeof dateRange.value[0] === 'string' ? dateRange.value[0] : (dateRange.value[0] as any).format('YYYYMMDD')
      rangeEnd = typeof dateRange.value[1] === 'string' ? dateRange.value[1] : (dateRange.value[1] as any).format('YYYYMMDD')
    }

    if (tsCode.value) {
      const res = await syncDailyByRange(tsCode.value, rangeStart, rangeEnd)
      if (res.code === 200) {
        autoResult.value = { status: 'success', count: res.data }
        ElMessage.success(`同步成功，共 ${res.data} 条`)
      } else {
        autoResult.value = { status: 'error', message: '同步失败' }
        ElMessage.error('同步失败')
      }
    } else {
      const interval = setInterval(() => {
        if (syncProgress.value < 90) {
          syncProgress.value += 10
        }
      }, 500)
      const res = await syncAllIncremental(rangeStart, rangeEnd)
      clearInterval(interval)
      syncProgress.value = 100
      if (res.code === 200) {
        autoResult.value = { status: 'success', count: res.data }
        ElMessage.success(`同步成功，共 ${res.data} 条`)
      } else {
        autoResult.value = { status: 'error', message: '同步失败' }
        ElMessage.error('同步失败')
      }
    }
  } catch (error: any) {
    autoResult.value = { status: 'error', message: error.message || '请求失败' }
    ElMessage.error(error.message || '请求失败')
  } finally {
    syncingAuto.value = false
  }
}

const handleSyncBasic = async () => {
  syncingBasic.value = true
  basicResult.value = null
  try {
    const res = await syncStockBasic()
    if (res.code === 200) {
      basicResult.value = { status: 'success', count: res.data }
      ElMessage.success(`同步成功，共 ${res.data} 条`)
    } else {
      basicResult.value = { status: 'error', message: '同步失败' }
      ElMessage.error('同步失败')
    }
  } catch (error: any) {
    basicResult.value = { status: 'error', message: error.message || '请求失败' }
    ElMessage.error(error.message || '请求失败')
  } finally {
    syncingBasic.value = false
  }
}

const handleSyncDailyByDate = async () => {
  if (!dailyDate.value) {
    ElMessage.warning('请选择交易日期')
    return
  }
  syncingDaily.value = true
  dailyDateResult.value = null
  try {
    const res = await syncDailyByDate(dailyDate.value)
    if (res.code === 200) {
      dailyDateResult.value = { status: 'success', count: res.data }
      ElMessage.success(`同步成功，共 ${res.data} 条`)
    } else {
      dailyDateResult.value = { status: 'error', message: '同步失败' }
      ElMessage.error('同步失败')
    }
  } catch (error: any) {
    dailyDateResult.value = { status: 'error', message: error.message || '请求失败' }
    ElMessage.error(error.message || '请求失败')
  } finally {
    syncingDaily.value = false
  }
}

const handleCheckStatus = async () => {
  if (!tsCode.value) {
    ElMessage.warning('请输入股票代码')
    return
  }
  checkingStatus.value = true
  try {
    const res = await getSyncStatus(tsCode.value)
    if (res.code === 200) {
      syncStatus.value = res.data
    } else {
      ElMessage.error('获取状态失败')
    }
  } catch (error: any) {
    ElMessage.error(error.message || '请求失败')
  } finally {
    checkingStatus.value = false
  }
}

const getStatusColor = (status: string) => {
  switch (status) {
    case 'UP_TO_DATE': return 'green'
    case 'NEEDS_SYNC': return 'orange'
    case 'NO_DATA': return 'red'
    default: return 'default'
  }
}

const getStatusText = (status: string) => {
  switch (status) {
    case 'UP_TO_DATE': return '已是最新'
    case 'NEEDS_SYNC': return '需要同步'
    case 'NO_DATA': return '无数据'
    default: return status
  }
}
</script>

<style scoped>
.sync-container {
  max-width: 900px;
  margin: 0 auto;
}
</style>
