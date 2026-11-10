<template>
  <div class="suggest-page">
    <a-card :bordered="false" class="suggest-card">
      <template #title>
        <div class="card-title">
          <span>建议单确认</span>
          <a-tag color="orange">建议 ≠ 成交</a-tag>
        </div>
      </template>
      <template #extra>
        <a-space :size="12">
          <a-switch
            v-model:checked="autoRefresh"
            checked-children="自动"
            un-checked-children="手动"
            size="small"
          />
          <a-button size="small" :loading="loading" @click="load">刷新</a-button>
        </a-space>
      </template>

      <a-alert type="info" show-icon :closable="false" class="hint">
        <template #message>这些是 Agent / 策略给出的建议，系统不会自动下单</template>
        <template #description>
          只有点「确认执行」才会向 OKX 模拟盘提交真实委托，并要过四道风控（单笔金额、日累计、最大回撤、总闸）。
          执行前请自己看一眼现价——信号是最后一根 K 线收盘时的判断，到确认时价格可能已经走远。
        </template>
      </a-alert>

      <a-row :gutter="16" class="stats">
        <a-col :span="6">
          <a-statistic title="待确认" :value="pendingCount" :value-style="pendingCount ? { color: '#fa8c16' } : {}" />
        </a-col>
        <a-col :span="6">
          <a-statistic title="已执行" :value="executedCount" />
        </a-col>
        <a-col :span="12">
          <div class="meta">
            <div>最近刷新：<span class="mono">{{ lastLoadedAt }}</span></div>
            <div>{{ autoRefresh ? '每 30 秒自动刷新' : '已关闭自动刷新' }}</div>
          </div>
        </a-col>
      </a-row>

      <a-radio-group v-model:value="statusFilter" button-style="solid" size="small" class="filter">
        <a-radio-button value="new">待确认</a-radio-button>
        <a-radio-button value="executed">已执行</a-radio-button>
        <a-radio-button value="all">全部</a-radio-button>
      </a-radio-group>

      <a-table
        :data-source="filtered"
        :columns="columns"
        row-key="id"
        size="small"
        :loading="loading"
        :pagination="{ pageSize: 20, hideOnSinglePage: true }"
        :scroll="{ x: 1240 }"
        :locale="{ emptyText: emptyText }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'side'">
            <a-tag :color="record.side === 'buy' ? 'red' : 'green'">
              {{ sideText(record.side) }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'ordType'">
            <span>{{ record.ordType === 'market' ? '市价' : '限价' }}</span>
          </template>
          <template v-else-if="column.key === 'sz'">
            <span class="mono">{{ fmtQty(record.sz) }}</span>
          </template>
          <template v-else-if="column.key === 'px'">
            <span v-if="record.px === null || record.px === undefined" class="dim">市价</span>
            <span v-else class="mono">{{ fmtQty(record.px) }}</span>
          </template>
          <template v-else-if="column.key === 'confidence'">
            <span class="dim">{{ fmtPercent(record.confidence) }}</span>
          </template>
          <template v-else-if="column.key === 'status'">
            <a-tag :color="statusColor(record.status)">
              {{ statusText(record.status) }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'reason'">
            <span class="reason" :title="record.reason || ''">{{ record.reason || '-' }}</span>
          </template>
          <template v-else-if="column.key === 'createTime'">
            <span class="mono dim">{{ fmtTime(record.createTime) }}</span>
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-button
              v-if="record.status === 'new'"
              type="primary"
              size="small"
              danger
              :loading="executingId === record.id"
              @click="openConfirm(record)"
            >
              确认执行
            </a-button>
            <span v-else class="dim">已处理</span>
          </template>
        </template>
      </a-table>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, h, onMounted, onUnmounted, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { executeOkxSuggestion, listOkxSuggestions, type OkxSuggestion } from '@/api/okx'
import { fmtPercent, fmtQty, fmtTime, sideText, statusColor, statusText } from './suggestionFormat'

const REFRESH_MS = 30_000

const rows = ref<OkxSuggestion[]>([])
const loading = ref(false)
const executingId = ref<number | null>(null)
const statusFilter = ref<'new' | 'executed' | 'all'>('new')
const autoRefresh = ref(true)
const lastLoadedAt = ref('-')

let timer: number | undefined

const columns = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 70 },
  { title: '标的', dataIndex: 'instId', key: 'instId', width: 130 },
  { title: '方向', dataIndex: 'side', key: 'side', width: 88 },
  { title: '类型', dataIndex: 'ordType', key: 'ordType', width: 80 },
  { title: '数量', dataIndex: 'sz', key: 'sz', width: 130, align: 'right' as const },
  { title: '价格', dataIndex: 'px', key: 'px', width: 110, align: 'right' as const },
  { title: '置信度', dataIndex: 'confidence', key: 'confidence', width: 88, align: 'right' as const },
  { title: '状态', dataIndex: 'status', key: 'status', width: 96 },
  { title: '理由', dataIndex: 'reason', key: 'reason', ellipsis: true },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime', width: 165 },
  { title: '操作', key: 'actions', width: 110, fixed: 'right' as const }
]

const filtered = computed(() => {
  if (statusFilter.value === 'all') return rows.value
  return rows.value.filter((r) => r.status === statusFilter.value)
})
const pendingCount = computed(() => rows.value.filter((r) => r.status === 'new').length)
const executedCount = computed(() => rows.value.filter((r) => r.status === 'executed').length)
const emptyText = computed(() =>
  statusFilter.value === 'new' ? '暂无待确认的建议单' : '暂无记录'
)

const nowText = () => fmtTime(new Date().toISOString())

const load = async () => {
  loading.value = true
  try {
    const res = await listOkxSuggestions(50)
    rows.value = res.data || []
    lastLoadedAt.value = nowText()
  } finally {
    loading.value = false
  }
}

/* ---------------- 自动刷新 ---------------- */

const stopTimer = () => {
  if (timer !== undefined) {
    window.clearInterval(timer)
    timer = undefined
  }
}

const startTimer = () => {
  stopTimer()
  timer = window.setInterval(() => {
    // 正在执行或正在手动刷新时跳过，避免把刚点下去的状态刷掉
    if (executingId.value === null && !loading.value) load()
  }, REFRESH_MS)
}

watch(autoRefresh, (on) => (on ? startTimer() : stopTimer()))

/* ---------------- 确认执行 ---------------- */

const line = (label: string, value: string) =>
  h('div', { style: 'display:flex;gap:10px;line-height:1.9' }, [
    h('span', { style: 'flex:0 0 48px;color:#8b93a7' }, label),
    h(
      'span',
      {
        style:
          'flex:1;word-break:break-all;font-family:ui-monospace,SFMono-Regular,Menlo,monospace'
      },
      value
    )
  ])

const openConfirm = (record: OkxSuggestion) => {
  Modal.confirm({
    title: '确认执行这条建议单？',
    width: 480,
    content: h('div', {}, [
      h(
        'div',
        { style: 'color:#8b93a7;margin-bottom:10px;line-height:1.7' },
        '将向 OKX 模拟盘提交真实委托，并经过四道风控。执行前请核对现价。'
      ),
      line('标的', record.instId),
      line('方向', sideText(record.side)),
      line('类型', record.ordType === 'market' ? '市价' : '限价'),
      line('数量', fmtQty(record.sz)),
      line('价格', record.px === null || record.px === undefined ? '市价成交' : fmtQty(record.px)),
      line('理由', record.reason || '-')
    ]),
    okText: '确认下单',
    okType: 'danger',
    cancelText: '取消',
    onOk: () => handleExecute(record)
  })
}

const handleExecute = async (record: OkxSuggestion) => {
  executingId.value = record.id
  try {
    const res = await executeOkxSuggestion(record.id)
    const ordId = res.data?.ordId
    message.success(ordId ? `已下单，ordId=${ordId}` : '已下单')
    await load()
  } catch {
    // 失败原因已由 request 拦截器用后端 message 弹出（如「未授予 OKX 交易权限」「风控拦截」），
    // 这里吞掉是因为 onOk 抛出去会变成未捕获的 rejection，控制台留一堆红字更干扰排障。
    // 弹窗随之关闭，列表还在，用户按提示补齐权限后可以再点。
  } finally {
    executingId.value = null
  }
}

onMounted(() => {
  load()
  startTimer()
})

onUnmounted(stopTimer)
</script>

<style scoped>
.suggest-page {
  padding: 4px;
}
.suggest-card {
  background: var(--panel-bg, #14161c);
}
.card-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.hint {
  margin: 12px 0;
}
.stats {
  margin-bottom: 12px;
}
.meta {
  color: var(--text-dim, #8b93a7);
  font-size: 12px;
  line-height: 1.9;
  text-align: right;
}
.filter {
  margin-bottom: 12px;
}
.mono {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}
.dim {
  color: var(--text-dim, #8b93a7);
}
.reason {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
</style>
