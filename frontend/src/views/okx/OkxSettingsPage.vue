<template>
  <div class="okx-page">
    <a-card :bordered="false" class="okx-card">
      <template #title>
        <div class="card-title">
          <span>OKX 模拟盘账户</span>
          <a-tag color="orange">仅模拟盘</a-tag>
        </div>
      </template>

      <a-alert type="info" show-icon :closable="false" class="hint">
        <template #message>这个系统只接 OKX 模拟盘</template>
        <template #description>
          实盘 Key 不会进入服务端；所有私有请求恒定带 <code>x-simulated-trading: 1</code>，
          提币永不开启。Key 加密后存在数据库里，接口只会返回掩码。
        </template>
      </a-alert>

      <a-spin :spinning="statusLoading">
        <a-descriptions :column="3" size="small" bordered class="status-line">
          <a-descriptions-item label="凭证">
            <a-tag :color="status.credentialConfigured ? 'green' : 'red'">
              {{ status.credentialConfigured ? '已配置' : '未配置' }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="全局交易总闸">
            <a-tag :color="status.enabled ? 'green' : 'default'">
              {{ status.enabled ? '已开启' : '关闭' }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="当前身份">
            <span class="mono">{{ status.principal || '-' }}</span>
          </a-descriptions-item>
        </a-descriptions>
      </a-spin>

      <a-alert
        v-if="status.credentialConfigured && !status.enabled"
        type="warning"
        show-icon
        class="hint"
        message="凭证已保存，但交易总闸未开"
        description="下单/撤单/同步还需要运维打开 okx.trade-enabled，并且你的账号要具备 okx_trade_enabled 权限。"
      />

      <a-divider orientation="left">录入 / 更新我的模拟盘 Key</a-divider>

      <a-form layout="vertical" :model="form" class="key-form">
        <a-row :gutter="16">
          <a-col :span="6">
            <a-form-item label="标签">
              <a-input v-model:value="form.label" placeholder="默认" :maxlength="64" />
            </a-form-item>
          </a-col>
          <a-col :span="9">
            <a-form-item label="API Key" required>
              <a-input v-model:value="form.apiKey" placeholder="OKX 模拟盘 API Key" autocomplete="off" />
            </a-form-item>
          </a-col>
          <a-col :span="9">
            <a-form-item label="Passphrase" required>
              <a-input-password v-model:value="form.passphrase" placeholder="创建 Key 时自设的口令" autocomplete="new-password" />
            </a-form-item>
          </a-col>
        </a-row>
        <a-row :gutter="16">
          <a-col :span="15">
            <a-form-item label="Secret Key" required>
              <a-input-password v-model:value="form.secretKey" placeholder="只提交一次，保存后不再回显" autocomplete="new-password" />
            </a-form-item>
          </a-col>
          <a-col :span="9">
            <a-form-item label="启用">
              <a-switch v-model:checked="form.enabled" checked-children="启用" un-checked-children="停用" />
              <span class="tip">停用只是不再用于交易，密文保留</span>
            </a-form-item>
          </a-col>
        </a-row>
        <a-space>
          <a-button type="primary" :loading="saving" @click="handleSave">保存凭证</a-button>
          <a-button @click="handleReset">清空表单</a-button>
        </a-space>
      </a-form>

      <a-divider orientation="left">已配置的账户</a-divider>

      <a-table
        :data-source="accounts"
        :columns="columns"
        row-key="label"
        size="small"
        :pagination="false"
        :loading="accountsLoading"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'enabled'">
            <a-tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'apiKeyMasked'">
            <span class="mono">{{ record.apiKeyMasked }}</span>
          </template>
          <template v-else-if="column.key === 'actions'">
            <a-button type="link" size="small" @click="fillFrom(record)">更新此账户</a-button>
          </template>
        </template>
      </a-table>

      <a-divider orientation="left">余额快照（WS account 频道）</a-divider>

      <a-spin :spinning="snapshotLoading">
        <a-empty v-if="!snapshots.length" description="还没有余额快照：需要 WS 私有频道登录后才会产生" />
        <template v-else>
          <a-row :gutter="16" class="snap-stats">
            <a-col :span="6">
              <a-statistic title="总权益 (USD)" :value="totalEq" :precision="2" />
            </a-col>
            <a-col :span="6">
              <a-statistic title="USDT 可用" :value="usdtAvail" :precision="2" />
            </a-col>
            <a-col :span="12">
              <div class="snap-meta">
                <div>最近快照：<span class="mono">{{ latestTime }}</span></div>
                <div>WS 状态：<a-tag :color="wsColor">{{ ws.status || 'UNKNOWN' }}</a-tag>
                  <span class="tip">{{ ws.enabled ? ws.url : '未启用（OKX_WS_ENABLED=false）' }}</span>
                </div>
              </div>
            </a-col>
          </a-row>
          <a-table
            :data-source="snapshots"
            :columns="snapshotColumns"
            row-key="ccy"
            size="small"
            :pagination="false"
          >
            <template #bodyCell="{ column, record }">
              <!-- 币种是字符串，不能走下面的数字格式化，否则会显示成 “-” -->
              <template v-if="column.key === 'ccy'">
                <a-tag v-if="record.ccy === 'TOTAL'" color="blue">合计</a-tag>
                <span v-else class="ccy mono">{{ record.ccy }}</span>
              </template>
              <template v-else-if="column.key === 'upl'">
                <span :class="uplClass(record.upl)">{{ fmt(record.upl) }}</span>
              </template>
              <template v-else-if="column.key === 'time'">
                <span class="mono">{{ toTime(record.uTime) }}</span>
              </template>
              <template v-else>{{ fmt(record[column.dataIndex as string]) }}</template>
            </template>
          </a-table>
        </template>
      </a-spin>
    </a-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  getLatestSnapshots,
  getOkxStatus,
  getOkxWsStatus,
  listOkxAccounts,
  saveOkxAccount,
  type OkxAccountView,
  type OkxSnapshot,
  type OkxStatus,
  type OkxWsStatus
} from '@/api/okx'

const status = ref<OkxStatus>({
  enabled: false,
  simulated: true,
  credentialConfigured: false
})
const statusLoading = ref(false)

const accounts = ref<OkxAccountView[]>([])
const accountsLoading = ref(false)

const snapshots = ref<OkxSnapshot[]>([])
const snapshotLoading = ref(false)

const ws = ref<OkxWsStatus>({ enabled: false, status: 'UNKNOWN', url: '', subscriptions: [], droppedFrames: 0 })

const saving = ref(false)
const form = reactive({
  label: '默认',
  apiKey: '',
  secretKey: '',
  passphrase: '',
  enabled: true
})

const columns = [
  { title: '标签', dataIndex: 'label', key: 'label' },
  { title: 'API Key', dataIndex: 'apiKeyMasked', key: 'apiKeyMasked' },
  { title: '状态', dataIndex: 'enabled', key: 'enabled', width: 90 },
  { title: '密钥版本', dataIndex: 'keyVersion', key: 'keyVersion', width: 100 },
  { title: '更新时间', dataIndex: 'updateTime', key: 'updateTime', width: 190 },
  { title: '', key: 'actions', width: 120 }
]

const snapshotColumns = [
  { title: '币种', dataIndex: 'ccy', key: 'ccy', width: 100 },
  { title: '权益', dataIndex: 'equity', key: 'equity' },
  { title: '现金余额', dataIndex: 'cashBal', key: 'cashBal' },
  { title: '可用', dataIndex: 'availBal', key: 'availBal' },
  { title: '冻结', dataIndex: 'frozenBal', key: 'frozenBal' },
  { title: '未实现盈亏', dataIndex: 'upl', key: 'upl' },
  { title: '快照时间', dataIndex: 'uTime', key: 'time', width: 190 }
]

const totalEq = computed(() => {
  const row = snapshots.value.find((s) => s.ccy === 'TOTAL')
  return Number(row?.equity ?? 0)
})
const usdtAvail = computed(() => {
  const row = snapshots.value.find((s) => s.ccy === 'USDT')
  return Number(row?.availBal ?? 0)
})
const latestTime = computed(() => {
  const times = snapshots.value.map((s) => Number(s.uTime || 0)).filter((n) => n > 0)
  return times.length ? toTime(Math.max(...times)) : '-'
})
const wsColor = computed(() => {
  if (!ws.value.enabled) return 'default'
  if (ws.value.status === 'LOGGED_IN') return 'green'
  if (ws.value.status === 'RECONNECTING') return 'orange'
  return 'blue'
})

const fmt = (v: unknown) => {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  return Number.isFinite(n) ? n.toFixed(4) : '-'
}

/** 盈亏颜色：0 保持中性，避免「没盈亏」也显示成红色 */
const uplClass = (v: unknown) => {
  const n = Number(v)
  if (!Number.isFinite(n) || n === 0) return 'flat'
  return n > 0 ? 'up' : 'down'
}
const toTime = (ms: number) => (ms ? new Date(Number(ms)).toLocaleString() : '-')

const handleReset = () => {
  form.label = '默认'
  form.apiKey = ''
  form.secretKey = ''
  form.passphrase = ''
  form.enabled = true
}

const fillFrom = (record: OkxAccountView) => {
  form.label = record.label
  // 掩码无法还原，只带标签；用户必须重新填全三个字段
  form.apiKey = ''
  form.secretKey = ''
  form.passphrase = ''
  form.enabled = record.enabled
  message.info('已选中该账户，请重新填写 Key（更新时 secretKey 与 passphrase 必须一起填）')
}

const loadStatus = async () => {
  statusLoading.value = true
  try {
    const res = await getOkxStatus()
    status.value = res.data
  } finally {
    statusLoading.value = false
  }
}

const loadAccounts = async () => {
  accountsLoading.value = true
  try {
    const res = await listOkxAccounts()
    accounts.value = res.data || []
  } finally {
    accountsLoading.value = false
  }
}

const loadSnapshots = async () => {
  snapshotLoading.value = true
  try {
    const [snap, wsStatus] = await Promise.all([getLatestSnapshots(), getOkxWsStatus()])
    snapshots.value = snap.data || []
    ws.value = wsStatus.data
  } finally {
    snapshotLoading.value = false
  }
}

const handleSave = async () => {
  if (!form.apiKey.trim() || !form.secretKey.trim() || !form.passphrase.trim()) {
    message.warning('apiKey / secretKey / passphrase 三项都要填')
    return
  }
  saving.value = true
  try {
    await saveOkxAccount({
      label: form.label.trim() || '默认',
      apiKey: form.apiKey.trim(),
      secretKey: form.secretKey.trim(),
      passphrase: form.passphrase.trim(),
      enabled: form.enabled
    })
    message.success('已保存（密文存储，接口不会回显 secret）')
    // 立刻清空明文，避免留在内存/DOM 里
    form.apiKey = ''
    form.secretKey = ''
    form.passphrase = ''
    await Promise.all([loadAccounts(), loadStatus()])
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadStatus()
  loadAccounts()
  loadSnapshots()
})
</script>

<style scoped>
.okx-page {
  padding: 4px;
}
.okx-card {
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
.status-line {
  margin-top: 8px;
}
.key-form {
  margin-top: 4px;
}
.tip {
  margin-left: 8px;
  color: var(--text-dim, #8b93a7);
  font-size: 12px;
}
.mono {
  font-family: var(--font-mono, ui-monospace, SFMono-Regular, Menlo, monospace);
}
.snap-stats {
  margin-bottom: 12px;
}
.snap-meta {
  color: var(--text-dim, #8b93a7);
  font-size: 12px;
  line-height: 1.9;
}
.up {
  color: #cf1322;
}
.down {
  color: #3f8600;
}
.flat {
  color: var(--text-dim, #8b93a7);
}
.ccy {
  letter-spacing: 0.5px;
}
</style>
