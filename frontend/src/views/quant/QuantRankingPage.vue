<template>
  <div class="quant-page">
    <header class="quant-header">
      <div>
        <div class="eyebrow">大智慧 / 量化看板</div>
        <h1>量化排行</h1>
        <p>按人气{wyz}数值从高到低生成每日排行，追踪持续出现的标的。</p>
      </div>
      <div class="collection-controls">
        <button class="primary-button" type="button" :disabled="collectionBusy || !automationStatus.supported" @click="collectRanking">
          {{ collectionBusy ? '采集中…' : '从大智慧采集' }}
        </button>
        <div class="header-state" role="status">
          <span class="state-dot" :class="{ 'is-working': loading || collectionBusy }" />
          <span>{{ collecting ? '正在从大智慧采集人气排行' : automationStatus.message }}</span>
        </div>
      </div>
    </header>
    <p v-if="collectionMessage" class="collection-feedback" :class="{ error: collectionError }" role="status">{{ collectionMessage }}</p>

    <nav class="quant-tabs" aria-label="量化排行模块">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="quant-tab"
        :class="{ active: activeTab === tab.key }"
        type="button"
        @click="activeTab = tab.key"
      >
        <span class="tab-index">{{ tab.index }}</span>
        {{ tab.label }}
      </button>
    </nav>

    <section v-if="activeTab === 'summary'" class="quant-section">
      <div class="section-heading">
        <div>
          <span class="section-kicker">历史排行 / 前 20</span>
          <h2>总表排行</h2>
        </div>
        <button class="ghost-button" type="button" @click="loadSummary">刷新总表</button>
      </div>

      <div class="metric-strip">
        <div class="metric-card">
          <span>累计标的</span>
          <strong>{{ summaryTotal }}</strong>
          <small>按历史出现次数去重</small>
        </div>
        <div class="metric-card metric-card-accent">
          <span>最新快照</span>
          <strong>{{ latestSnapshotDate || '暂无' }}</strong>
          <small>最近一次成功导入</small>
        </div>
        <div class="metric-card">
          <span>本页显示</span>
          <strong>{{ summaryRows.length }}</strong>
          <small>默认展示前 20 名</small>
        </div>
      </div>

      <div class="data-panel">
        <div class="panel-caption">
          <span>历史表现</span>
          <span class="caption-muted">统计每天人气前 20 的上榜次数，同日重复只计一次</span>
        </div>
        <div class="table-scroll">
          <table class="quant-table">
            <thead>
              <tr>
                <th>排名</th>
                <th>标的</th>
                <th>出现次数</th>
                <th>平均排名</th>
                <th>最佳排名</th>
                <th>最近排名</th>
                <th>最近日期</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in summaryRows" :key="`${row.code}-${row.lastDate}`">
                <td><span class="rank-badge">{{ row.rank }}</span></td>
                <td>
                  <div class="symbol-cell">
                    <strong>{{ row.name }}</strong>
                    <span>{{ row.code }}</span>
                  </div>
                </td>
                <td class="mono">{{ row.appearanceCount }}</td>
                <td class="mono">{{ formatNumber(row.averageRank) }}</td>
                <td class="mono accent-number">{{ row.bestRank }}</td>
                <td class="mono">{{ row.latestRank }}</td>
                <td class="muted mono">{{ row.lastDate }}</td>
              </tr>
              <tr v-if="!summaryRows.length">
                <td colspan="7" class="empty-cell">暂无总表数据，请先导入一份排行快照。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <section v-else-if="activeTab === 'daily'" class="quant-section">
      <div class="section-heading">
        <div>
          <span class="section-kicker">快照 / 交易日</span>
          <h2>每日快照</h2>
        </div>
        <div class="inline-controls">
          <input v-model="dailyDate" class="date-input" type="date" aria-label="快照日期" @change="loadDaily" />
          <button class="ghost-button" type="button" @click="loadDaily">查询快照</button>
        </div>
      </div>

      <div class="data-panel">
        <div class="panel-caption">
          <span>{{ dailyData.date || dailyDate || '未选择日期' }}</span>
          <span class="caption-muted">{{ dailyRows.length ? `共 ${dailyRows.length} 条` : '暂无快照' }}</span>
        </div>
        <div class="table-scroll">
          <table class="quant-table">
            <thead>
              <tr>
                <th>排名</th>
                <th>代码</th>
                <th>名称</th>
                <th>人气指标</th>
                <th>人气值</th>
                <th v-for="column in dailyIndicatorColumns" :key="column">{{ column }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in dailyRows" :key="`${row.code}-${row.rank}`">
                <td><span class="rank-badge">{{ row.rank }}</span></td>
                <td class="mono">{{ row.code }}</td>
                <td>{{ row.name }}</td>
                <td>{{ row.metric || '人气{wyz}' }}</td>
                <td class="mono">{{ row.metricValue ?? '—' }}</td>
                <td v-for="column in dailyIndicatorColumns" :key="`${row.code}-${column}`" class="indicator-cell mono">
                  {{ formatIndicatorValue(row.rawFields, column) }}
                </td>
              </tr>
              <tr v-if="!dailyRows.length">
                <td :colspan="5 + dailyIndicatorColumns.length" class="empty-cell">当天还没有排行数据。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <section v-else-if="activeTab === 'logs'" class="quant-section">
      <div class="section-heading">
        <div>
          <span class="section-kicker">采集 / 运行日志</span>
          <h2>采集日志</h2>
        </div>
        <button class="ghost-button" type="button" @click="loadLogs">刷新日志</button>
      </div>

      <div class="data-panel">
        <div class="table-scroll">
          <table class="quant-table">
            <thead>
              <tr>
                <th>状态</th>
                <th>交易日</th>
                <th>来源</th>
                <th>导入数量</th>
                <th>说明</th>
                <th>时间</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(log, index) in logs" :key="log.id ?? index">
                <td><span class="status-tag" :class="statusClass(log.status)">{{ log.status }}</span></td>
                <td class="mono">{{ log.date || '—' }}</td>
                <td>{{ log.source || '手动导入' }}</td>
                <td class="mono">{{ log.importedCount ?? '—' }}</td>
                <td>{{ log.message || '—' }}</td>
                <td class="muted mono">{{ log.createdAt || '—' }}</td>
              </tr>
              <tr v-if="!logs.length">
                <td colspan="6" class="empty-cell">暂无采集记录。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </section>

    <section v-else class="quant-section">
      <div class="section-heading">
        <div>
          <span class="section-kicker">自动采集 / 通知 / 导入</span>
          <h2>采集与通知设置</h2>
        </div>
      </div>

      <div class="settings-grid">
        <div class="data-panel settings-panel automation-panel">
          <div class="panel-caption">
            <span>大智慧自动采集</span>
            <span class="caption-muted">{{ automationStatus.supported ? '本机采集' : '当前环境不支持' }}</span>
          </div>
          <label class="setting-row setting-switch-row">
            <span>
              <strong>启用定时采集</strong>
              <small>交易日 15:10 自动采集人气{wyz}排行</small>
            </span>
            <input v-model="automation.enabled" type="checkbox" class="switch-input" aria-label="启用定时采集" />
          </label>
          <label class="setting-row setting-switch-row">
            <span>
              <strong>Java启动后补跑</strong>
              <small>启用后，在当天 15:10 之后启动 Java 服务时补采缺失快照</small>
            </span>
            <input v-model="automation.startupCatchUp" type="checkbox" class="switch-input" aria-label="Java启动后补跑" />
          </label>
          <label class="setting-field">
            <span>大智慧程序路径</span>
            <input v-model="automation.executablePath" type="text" placeholder="留空时查找正在运行的大智慧进程" aria-label="大智慧程序路径" />
          </label>
          <p class="setting-help">可运行条件：Windows 已登录且桌面未锁定，Java 服务保持运行，大智慧已登录并打开含人气{wyz}列的行情表。请避免采集时操作大智慧；程序路径留空时查找当前大智慧进程。</p>
          <div class="automation-state" role="status">
            <span>{{ automationStatus.message }}</span>
            <span v-if="automationStatus.nextRunAt">下次采集：{{ formatDateTime(automationStatus.nextRunAt) }}</span>
            <span v-if="automationStatus.lastRun">最近结果：{{ schedulerStatusLabel(automationStatus.lastRun.status) }} · {{ automationStatus.lastRun.tradingDate }} · {{ automationStatus.lastRun.importedRows }} 条</span>
          </div>
          <div class="setting-actions">
            <button class="primary-button" type="button" :disabled="savingAutomation" @click="saveAutomation">
              {{ savingAutomation ? '保存中…' : '保存采集设置' }}
            </button>
            <button class="ghost-button" type="button" @click="loadAutomationStatus()">刷新采集状态</button>
          </div>
          <p v-if="automationMessage" class="inline-feedback" :class="{ error: automationError }" role="status">{{ automationMessage }}</p>
        </div>

        <div class="data-panel settings-panel">
          <div class="panel-caption">
            <span>企业微信机器人</span>
            <span class="caption-muted">{{ notificationConfigured ? '已配置 · 不回显密钥' : '仅保存在本机配置' }}</span>
          </div>
          <label class="setting-row setting-switch-row">
            <span>
              <strong>启用每日推送</strong>
              <small>工作日采集完成后推送当天前 10 与总表前 20</small>
            </span>
            <input v-model="notification.enabled" type="checkbox" class="switch-input" />
          </label>
          <label class="setting-field">
            <span>机器人 Webhook 地址</span>
            <input v-model="notification.webhookUrl" type="url" placeholder="https://qyapi.weixin.qq.com/cgi-bin/webhook/send?..." />
          </label>
          <div class="setting-actions">
            <button class="primary-button" type="button" :disabled="savingNotification" @click="saveNotification">
              {{ savingNotification ? '保存中…' : '保存设置' }}
            </button>
            <button class="ghost-button" type="button" :disabled="testingNotification" @click="testNotification">
              {{ testingNotification ? '发送中…' : '测试发送' }}
            </button>
          </div>
          <p v-if="notificationMessage" class="inline-feedback" :class="{ error: notificationError }">{{ notificationMessage }}</p>
        </div>

        <div class="data-panel import-panel">
          <div class="panel-caption"><span>导入排行</span><span class="caption-muted">支持粘贴文本或选择文件</span></div>
          <div class="import-grid">
            <label class="setting-field">
              <span>交易日</span>
              <input v-model="importDate" type="date" />
            </label>
            <label class="file-picker">
              <span>选择 .csv / .tsv / .txt 文件</span>
              <input type="file" accept=".csv,.tsv,.txt,text/csv,text/plain" @change="handleFileChange" />
              <em>{{ selectedFile?.name || '点击选择文件' }}</em>
            </label>
          </div>
          <label class="setting-field">
            <span>粘贴大智慧排行文本</span>
            <textarea
              :value="pasteText"
              rows="7"
              placeholder="首行为列名，使用 Tab 分隔，例如：代码\t名称\t人气{wyz}\t最新\t涨幅"
              @input="handleTextInput"
            />
          </label>
          <p class="setting-help">必须包含代码、名称及人气{wyz}列。按人气值降序生成排名，无需提供现成排名列。</p>
          <div v-if="detectedColumns.length" class="detected-columns">
            <span>已识别列</span>
            <b v-for="column in detectedColumns" :key="column">{{ column }}</b>
          </div>
          <p v-if="importError" class="inline-feedback error">{{ importError }}</p>
          <p v-if="importMessage" class="inline-feedback">{{ importMessage }}</p>
          <button class="primary-button import-button" type="button" :disabled="importing || readingFile" @click="handleImport">
            {{ importing ? '导入中…' : readingFile ? '读取文件中…' : '导入排行' }}
          </button>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import {
  collectQuantRanking,
  getQuantAutomationConfig,
  getQuantAutomationStatus,
  getQuantDaily,
  getQuantLogs,
  getQuantNotificationConfig,
  getQuantSummary,
  importQuantRanking,
  saveQuantAutomationConfig,
  saveQuantNotificationConfig,
  sendQuantNotificationTest
} from '@/api/quantRanking'
import type {
  QuantAutomationConfig,
  QuantAutomationStatus,
  QuantColumnMapping,
  QuantImportResult,
  QuantNotificationConfig,
  QuantNotificationResult,
  QuantRankingDailyData,
  QuantRankingDailyRow,
  QuantRankingLog,
  QuantRankingSummaryRow,
  QuantSchedulerResult
} from '@/types/quantRanking'

type TabKey = 'summary' | 'daily' | 'logs' | 'settings'

const tabs = [
  { key: 'summary' as const, label: '总表排行', index: '01' },
  { key: 'daily' as const, label: '每日快照', index: '02' },
  { key: 'logs' as const, label: '采集日志', index: '03' },
  { key: 'settings' as const, label: '通知设置', index: '04' }
]

const activeTab = ref<TabKey>('summary')
const loading = ref(false)
const summaryRows = ref<QuantRankingSummaryRow[]>([])
const summaryTotal = ref(0)
const dailyDate = ref(today())
const importDate = ref(today())
const dailyData = ref<QuantRankingDailyData>({ date: '', rows: [] })
const logs = ref<QuantRankingLog[]>([])
const notification = reactive<QuantNotificationConfig>({ enabled: false, webhookUrl: '' })
const notificationConfigured = ref(false)
const pasteText = ref('')
const selectedFile = ref<File | null>(null)
const importing = ref(false)
const readingFile = ref(false)
const importError = ref('')
const importMessage = ref('')
const savingNotification = ref(false)
const testingNotification = ref(false)
const notificationMessage = ref('')
const notificationError = ref(false)
const automation = reactive<QuantAutomationConfig>({ enabled: false, executablePath: '', scheduleTime: '15:10', startupCatchUp: true })
const automationStatus = ref<QuantAutomationStatus>({ supported: false, running: false, message: '正在读取采集状态' })
const savingAutomation = ref(false)
const automationMessage = ref('')
const automationError = ref(false)
const collecting = ref(false)
const collectionMessage = ref('')
const collectionError = ref(false)
const collectionBusy = computed(() => collecting.value || automationStatus.value.running)
let statusTimer: ReturnType<typeof setInterval> | undefined
let statusRequestPending = false
let statusLoaded = false

const dailyRows = computed<QuantRankingDailyRow[]>(() => dailyData.value.rows || [])
const indicatorPriority = [
  '最新', '涨幅', '涨跌', '换手率', '量比', '涨速', '总额', '总手', '最高', '最低',
  '行业', 'PE(TTM)', '市盈率', '市净率', '市销率', '流通市值', '总市值'
]
const dailyIndicatorColumns = computed(() => {
  const keys = new Set<string>()
  dailyRows.value.forEach((row) => Object.keys(row.rawFields || {}).filter((key) => !isPopularityColumn(key)).forEach((key) => keys.add(key)))
  const preferred = indicatorPriority.filter((key) => keys.has(key))
  const custom = Array.from(keys).filter((key) => !indicatorPriority.includes(key))
  return [...preferred, ...custom]
})
const latestSnapshotDate = computed(() =>
  (dailyRows.value.length ? dailyData.value.date : '') || logs.value.find((log) => log.status === '成功')?.date || ''
)
const detectedColumns = computed(() => {
  if (!pasteText.value.trim()) return []
  return splitLine(pasteText.value.trim().split(/\r?\n/)[0])
})
const detectedMapping = computed<QuantColumnMapping>(() => {
  const find = (aliases: string[]) => detectedColumns.value.find((column) => aliases.includes(column.trim().toLowerCase()))
  const firstColumn = detectedColumns.value[0]?.trim() || ''
  const inferredRank = find(['排名', 'rank', '序号']) || (/^\d{4}[/.\-]\d{1,2}[/.\-]\d{1,2}$/.test(firstColumn) ? detectedColumns.value[0] : undefined)
  return {
    rank: inferredRank,
    code: find(['代码', '股票代码', '证券代码', 'code']),
    name: find(['名称', '股票名称', 'name']),
    metric: find(['指标', '指标名称', 'metric']),
    metricValue: detectedColumns.value.find(isPopularityColumn)
  }
})
const missingColumns = computed(() => {
  const required: Array<[keyof QuantColumnMapping, string]> = [
    ['code', '代码'],
    ['name', '名称'],
    ['metricValue', '人气{wyz}']
  ]
  return required.filter(([field]) => !detectedMapping.value[field]).map(([, label]) => label)
})

function today() {
  const date = new Date()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

function splitLine(line: string) {
  return line.includes('\t') ? line.split('\t') : line.split(/,|，/).map((column) => column.replace(/^"(.*)"$/, '$1'))
}

function isPopularityColumn(column: string) {
  return /^人气(?:\{wyz\}|\[wyz\]|\(wyz\)|｛wyz｝|［wyz］|（wyz）|【wyz】)$/.test(column.replace(/\s+/g, '').toLowerCase())
}

function formatDateTime(value: string) {
  return value.replace('T', ' ')
}

function schedulerStatusLabel(status: string) {
  const labels: Record<string, string> = {
    SUCCESS: '成功', COLLECT_FAILED: '采集失败', IMPORT_FAILED: '导入失败',
    ALREADY_RUNNING: '正在采集', SKIPPED_NON_TRADING_DAY: '非交易日跳过'
  }
  return labels[status] || status
}

function unwrap<T>(response: any, fallback: T): T {
  if (response?.data !== undefined) return response.data as T
  return (response ?? fallback) as T
}

function formatNumber(value: number) {
  return Number.isFinite(value) ? value.toFixed(2) : '—'
}

function formatIndicatorValue(fields: Record<string, string | number | null> | undefined, column: string) {
  const value = fields?.[column]
  return value === null || value === undefined || value === '' ? '—' : String(value)
}

function normalizeSummaryData(data: any) {
  const sourceRows = Array.isArray(data) ? data : (Array.isArray(data?.rows) ? data.rows : [])
  const rows = sourceRows.map((row: any, index: number) => ({
    rank: Number(row.rank ?? index + 1),
    code: String(row.code ?? row.stockCode ?? ''),
    name: String(row.name ?? row.stockName ?? ''),
    appearanceCount: Number(row.appearanceCount ?? row.count ?? 0),
    averageRank: Number(row.averageRank ?? 0),
    bestRank: Number(row.bestRank ?? 0),
    latestRank: Number(row.latestRank ?? 0),
    lastDate: String(row.lastDate ?? row.lastSeenDate ?? '')
  }))
  return { rows, total: Number(data?.total ?? rows.length) }
}

function normalizeDailyData(data: any) {
  const sourceRows = Array.isArray(data) ? data : (Array.isArray(data?.rows) ? data.rows : [])
  const rows = sourceRows.map((row: any) => {
    const rawFields = row.rawFields || row.raw || {}
    const popularityColumn = Object.keys(rawFields).find(isPopularityColumn)
    return {
      rank: Number(row.rank ?? 0),
      code: String(row.code ?? row.stockCode ?? ''),
      name: String(row.name ?? row.stockName ?? ''),
      metric: '人气{wyz}',
      metricValue: isPopularityColumn(String(row.metric || '')) ? row.metricValue : popularityColumn ? rawFields[popularityColumn] : undefined,
      rawFields
    }
  })
  return { date: String(data?.date ?? data?.tradingDate ?? dailyDate.value), rows }
}

function statusClass(status: string) {
  if (status === '成功') return 'success'
  if (status === '失败') return 'error'
  return 'muted'
}

async function loadSummary() {
  const response = await getQuantSummary(20)
  const data = normalizeSummaryData(unwrap(response, { rows: [], total: 0 }))
  summaryRows.value = data.rows
  summaryTotal.value = data.total
}

async function loadDaily() {
  try {
    const response = await getQuantDaily(dailyDate.value)
    const data = normalizeDailyData(unwrap(response, { date: dailyDate.value, rows: [] }))
    dailyData.value = { date: data.date || dailyDate.value, rows: data.rows }
  } catch {
    dailyData.value = { date: dailyDate.value, rows: [] }
  }
}

async function loadLogs() {
  try {
    const response = await getQuantLogs(30)
    const data: any = unwrap<any>(response, [])
    logs.value = Array.isArray(data) ? data : (Array.isArray(data?.logs) ? data.logs : [])
  } catch {
    logs.value = []
  }
}

async function loadNotification() {
  try {
    const response = await getQuantNotificationConfig()
    const data: any = unwrap<any>(response, { enabled: false, webhookUrl: '' })
    notification.enabled = Boolean(data?.enabled)
    notification.webhookUrl = data?.webhookUrl || ''
    notificationConfigured.value = Boolean(data?.webhookConfigured || notification.webhookUrl)
  } catch {
    notification.enabled = false
    notification.webhookUrl = ''
    notificationConfigured.value = false
  }
}

async function refreshRankingData(date?: string) {
  if (date) dailyDate.value = date
  await Promise.allSettled([loadSummary(), loadDaily(), loadLogs()])
}

async function loadAutomation() {
  try {
    const response = await getQuantAutomationConfig()
    const data = unwrap<QuantAutomationConfig>(response, automation)
    Object.assign(automation, data, { executablePath: data.executablePath || '', scheduleTime: '15:10' })
  } catch (error: any) {
    automationError.value = true
    automationMessage.value = error?.message || '采集设置读取失败，请稍后重试'
  }
}

async function loadAutomationStatus(refreshOnCompletion = false) {
  if (statusRequestPending) return
  statusRequestPending = true
  try {
    const previous = automationStatus.value
    const response = await getQuantAutomationStatus()
    const data = unwrap<QuantAutomationStatus>(response, previous)
    automationStatus.value = data
    const completed = !data.running && (previous.running || JSON.stringify(previous.lastRun) !== JSON.stringify(data.lastRun))
    if (refreshOnCompletion && statusLoaded && !collecting.value && completed) {
      await refreshRankingData(data.lastRun?.status === 'SUCCESS' ? data.lastRun.tradingDate : undefined)
    }
    statusLoaded = true
  } catch (error: any) {
    automationStatus.value = { ...automationStatus.value, message: error?.message || '采集状态读取失败，请检查 Java 服务' }
  } finally {
    statusRequestPending = false
  }
}

async function saveAutomation() {
  savingAutomation.value = true
  automationError.value = false
  automationMessage.value = ''
  try {
    const config: QuantAutomationConfig = {
      enabled: automation.enabled,
      executablePath: automation.executablePath.trim(),
      scheduleTime: '15:10',
      startupCatchUp: automation.startupCatchUp
    }
    await saveQuantAutomationConfig(config)
    Object.assign(automation, config)
    automationMessage.value = '采集设置已保存'
    await loadAutomationStatus()
  } catch (error: any) {
    automationError.value = true
    automationMessage.value = error?.message || '采集设置保存失败，请稍后重试'
  } finally {
    savingAutomation.value = false
  }
}

async function collectRanking() {
  if (collectionBusy.value || !automationStatus.value.supported) return
  collecting.value = true
  collectionMessage.value = ''
  collectionError.value = false
  let collectedDate: string | undefined
  try {
    const response = await collectQuantRanking()
    const result = unwrap<QuantSchedulerResult>(response, { status: 'COLLECT_FAILED', tradingDate: '', importedRows: 0, message: '未返回采集结果' })
    collectionError.value = result.status !== 'SUCCESS'
    if (result.status === 'SUCCESS') {
      collectedDate = result.tradingDate
      collectionMessage.value = `采集成功：${result.tradingDate} 已导入 ${result.importedRows} 条人气排行；${result.message}`
    } else {
      collectionMessage.value = `${schedulerStatusLabel(result.status)}：${result.message || '请检查大智慧窗口和采集运行条件'}`
    }
  } catch (error: any) {
    collectionError.value = true
    collectionMessage.value = error?.response?.data?.message || error?.message || '采集请求失败，请检查 Java 服务和大智慧窗口'
  } finally {
    await Promise.allSettled([refreshRankingData(collectedDate), loadAutomationStatus()])
    collecting.value = false
  }
}

async function loadAll() {
  loading.value = true
  await Promise.allSettled([loadSummary(), loadDaily(), loadLogs(), loadNotification(), loadAutomation(), loadAutomationStatus()])
  loading.value = false
}

function handleTextInput(event: Event) {
  pasteText.value = (event.target as HTMLTextAreaElement).value
  selectedFile.value = null
  readingFile.value = false
  importError.value = ''
  importMessage.value = ''
}

async function handleFileChange(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0] || null
  selectedFile.value = file
  pasteText.value = ''
  importError.value = ''
  importMessage.value = ''
  if (!file) return
  readingFile.value = true
  try {
    const content = await file.arrayBuffer()
    let text: string
    try {
      text = new TextDecoder('utf-8', { fatal: true }).decode(content)
    } catch {
      text = new TextDecoder('gb18030').decode(content)
    }
    if (selectedFile.value === file) pasteText.value = text
  } catch {
    if (selectedFile.value === file) importError.value = '文件读取失败，请重新选择文件或粘贴排行文本'
  } finally {
    if (selectedFile.value === file) readingFile.value = false
  }
}

async function handleImport() {
  if (importing.value || readingFile.value) return
  importError.value = ''
  importMessage.value = ''
  if (!selectedFile.value && !pasteText.value.trim()) {
    importError.value = '请粘贴排行文本或选择 .csv / .tsv / .txt 文件'
    return
  }
  if (missingColumns.value.length) {
    importError.value = `缺少关键列：${missingColumns.value.join('、')}`
    return
  }

  importing.value = true
  try {
    const response = await importQuantRanking({
      text: pasteText.value || undefined,
      file: selectedFile.value || undefined,
      tradingDate: importDate.value,
      mapping: detectedMapping.value
    })
    const result = unwrap<QuantImportResult>(response, {})
    importMessage.value = result.replaced
      ? `导入成功：已替换 ${result.date || result.tradingDate || importDate.value} 的旧快照`
      : `导入成功：${result.imported ?? result.rowCount ?? '排行数据'} 条记录已写入`
    if (result.skippedRows) importMessage.value += `；跳过 ${result.skippedRows} 条未计算人气的数据`
    await refreshRankingData(result.date || result.tradingDate || importDate.value)
  } catch (error: any) {
    importError.value = error?.response?.data?.message || error?.message || '导入失败，请检查文件格式和列名'
  } finally {
    importing.value = false
  }
}

async function saveNotification() {
  savingNotification.value = true
  notificationError.value = false
  try {
    await saveQuantNotificationConfig({ enabled: notification.enabled, webhookUrl: notification.webhookUrl.trim() })
    notificationConfigured.value = notificationConfigured.value || Boolean(notification.webhookUrl.trim())
    notificationMessage.value = '通知设置已保存'
  } catch (error: any) {
    notificationError.value = true
    notificationMessage.value = error?.message || '保存失败，请稍后重试'
  } finally {
    savingNotification.value = false
  }
}

async function testNotification() {
  testingNotification.value = true
  notificationError.value = false
  notificationMessage.value = ''
  try {
    const response = await sendQuantNotificationTest()
    const result = unwrap<QuantNotificationResult | null>(response, null)
    notificationError.value = result?.sent !== true
    notificationMessage.value = result?.message || (result?.sent === true
      ? '测试消息已发送' : '测试发送失败，请检查通知开关和 Webhook 地址')
  } catch (error: any) {
    notificationError.value = true
    notificationMessage.value = error?.message || '测试发送失败，请检查 Webhook 地址'
  } finally {
    testingNotification.value = false
  }
}

onMounted(() => {
  void loadAll()
  statusTimer = setInterval(() => { void loadAutomationStatus(true) }, 5000)
})

onUnmounted(() => {
  if (statusTimer) clearInterval(statusTimer)
})
</script>

<style scoped>
.quant-page {
  min-height: 100%;
  padding: 22px 24px 36px;
  color: var(--term-fg);
  font-family: var(--term-font-sans);
}

.quant-header,
.section-heading,
.setting-switch-row,
.setting-actions,
.inline-controls,
.panel-caption {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.quant-header {
  padding: 4px 2px 18px;
  border-bottom: 1px solid var(--term-border);
}

.eyebrow,
.section-kicker {
  color: var(--term-accent);
  font-family: var(--term-font-mono);
  font-size: 10px;
  letter-spacing: 1.7px;
}

h1,
h2,
p {
  margin: 0;
}

h1 {
  margin-top: 6px;
  font-size: 25px;
  letter-spacing: 0.4px;
}

h2 {
  margin-top: 5px;
  font-size: 17px;
  font-weight: 600;
}

.quant-header p {
  margin-top: 7px;
  color: var(--term-fg-muted);
  font-size: 12px;
}

.header-state {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--term-fg-muted);
  font-family: var(--term-font-mono);
  font-size: 11px;
}

.collection-controls {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 10px;
  max-width: 420px;
}

.collection-feedback {
  margin-top: 12px;
  color: var(--term-success);
  font-size: 12px;
}

.collection-feedback.error {
  color: var(--term-warning);
}

.state-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--term-success);
  box-shadow: 0 0 10px rgba(34, 197, 94, 0.7);
}

.state-dot.is-working {
  background: var(--term-warning);
  box-shadow: 0 0 10px rgba(251, 191, 36, 0.7);
}

.quant-tabs {
  display: flex;
  gap: 4px;
  padding: 14px 0 4px;
  overflow-x: auto;
}

.quant-tab,
.ghost-button,
.primary-button {
  border: 1px solid var(--term-border);
  border-radius: var(--term-radius-sm);
  font-family: inherit;
  cursor: pointer;
  transition: all var(--term-dur-fast) var(--term-ease);
}

.quant-tab {
  min-width: 116px;
  padding: 8px 12px;
  background: transparent;
  color: var(--term-fg-muted);
  font-size: 12px;
  text-align: left;
}

.quant-tab:hover,
.quant-tab.active {
  color: var(--term-accent);
  border-color: rgba(56, 189, 248, 0.45);
  background: rgba(56, 189, 248, 0.09);
}

.tab-index {
  margin-right: 8px;
  color: var(--term-fg-dim);
  font-family: var(--term-font-mono);
  font-size: 10px;
}

.quant-section {
  padding-top: 22px;
}

.section-heading {
  margin-bottom: 14px;
}

.ghost-button,
.primary-button {
  min-height: 31px;
  padding: 0 12px;
  font-size: 11px;
}

.ghost-button {
  background: rgba(255, 255, 255, 0.02);
  color: var(--term-fg-muted);
}

.ghost-button:hover {
  color: var(--term-accent);
  border-color: rgba(56, 189, 248, 0.45);
}

.primary-button {
  color: #06111b;
  background: linear-gradient(135deg, var(--term-accent), var(--term-accent-2));
  border-color: transparent;
  font-weight: 650;
}

.primary-button:hover {
  box-shadow: 0 0 18px rgba(56, 189, 248, 0.25);
  transform: translateY(-1px);
}

.primary-button:disabled,
.ghost-button:disabled {
  cursor: wait;
  opacity: 0.6;
  transform: none;
}

.metric-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
  margin-bottom: 14px;
}

.metric-card,
.data-panel {
  border: 1px solid var(--term-border);
  border-radius: var(--term-radius);
  background: linear-gradient(180deg, rgba(18, 26, 43, 0.74), rgba(10, 15, 26, 0.56));
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.16);
}

.metric-card {
  min-height: 82px;
  padding: 13px 14px;
}

.metric-card span,
.metric-card small {
  display: block;
  color: var(--term-fg-muted);
  font-size: 11px;
}

.metric-card strong {
  display: block;
  margin: 8px 0 5px;
  color: var(--term-fg);
  font-family: var(--term-font-mono);
  font-size: 21px;
  font-weight: 600;
}

.metric-card-accent strong {
  color: var(--term-accent);
  font-size: 17px;
}

.metric-card small {
  color: var(--term-fg-dim);
  font-size: 10px;
}

.data-panel {
  overflow: hidden;
}

.panel-caption {
  min-height: 42px;
  padding: 0 14px;
  border-bottom: 1px solid var(--term-border);
  color: var(--term-fg);
  font-size: 12px;
  font-weight: 600;
}

.caption-muted,
.muted {
  color: var(--term-fg-dim);
  font-size: 10.5px;
  font-weight: 400;
}

.table-scroll {
  overflow-x: auto;
}

.quant-table {
  width: 100%;
  min-width: 680px;
  border-collapse: collapse;
  font-size: 11.5px;
}

.quant-table th,
.quant-table td {
  padding: 11px 12px;
  border-bottom: 1px solid rgba(30, 39, 64, 0.72);
  text-align: left;
  white-space: nowrap;
}

.quant-table th {
  color: var(--term-fg-dim);
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.4px;
}

.quant-table tbody tr:hover {
  background: rgba(56, 189, 248, 0.035);
}

.quant-table tbody tr:last-child td {
  border-bottom: 0;
}

.rank-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 25px;
  height: 22px;
  border: 1px solid rgba(56, 189, 248, 0.3);
  border-radius: 5px;
  color: var(--term-accent);
  background: rgba(56, 189, 248, 0.08);
  font-family: var(--term-font-mono);
  font-size: 10px;
}

.symbol-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.symbol-cell strong {
  color: var(--term-fg);
  font-weight: 600;
}

.symbol-cell span,
.indicator-cell {
  color: var(--term-fg-muted);
  font-family: var(--term-font-mono);
  font-size: 10.5px;
}

.mono {
  font-family: var(--term-font-mono);
}

.accent-number {
  color: var(--term-warning);
}

.empty-cell {
  padding: 40px 12px !important;
  color: var(--term-fg-dim);
  text-align: center !important;
}

.date-input,
.setting-field input,
.setting-field textarea,
.file-picker {
  width: 100%;
  border: 1px solid var(--term-border);
  border-radius: var(--term-radius-sm);
  background: rgba(4, 6, 12, 0.56);
  color: var(--term-fg);
  font: inherit;
  font-size: 11.5px;
  outline: none;
  transition: border-color var(--term-dur-fast), box-shadow var(--term-dur-fast);
}

.date-input {
  width: 145px;
  min-height: 31px;
  padding: 0 9px;
}

.date-input:focus,
.setting-field input:focus,
.setting-field textarea:focus,
.file-picker:focus-within {
  border-color: rgba(56, 189, 248, 0.55);
  box-shadow: 0 0 0 3px rgba(56, 189, 248, 0.1);
}

.settings-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr);
  gap: 14px;
}

.settings-panel,
.import-panel {
  min-width: 0;
  padding-bottom: 14px;
}

.automation-panel {
  grid-column: 1 / -1;
}

.setting-help,
.automation-state {
  margin: 14px 14px 0;
  color: var(--term-fg-muted);
  font-size: 11px;
  line-height: 1.7;
}

.automation-state {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 20px;
  color: var(--term-fg-dim);
}

.setting-row,
.setting-field,
.file-picker {
  display: flex;
  flex-direction: column;
  gap: 7px;
  margin: 15px 14px 0;
}

.setting-switch-row {
  flex-direction: row;
  align-items: center;
}

.setting-row strong,
.setting-field > span,
.file-picker > span {
  color: var(--term-fg);
  font-size: 11.5px;
  font-weight: 600;
}

.setting-row small {
  display: block;
  margin-top: 5px;
  color: var(--term-fg-dim);
  font-size: 10.5px;
}

.switch-input {
  width: 34px;
  height: 19px;
  accent-color: var(--term-accent);
}

.setting-field input,
.setting-field textarea {
  box-sizing: border-box;
  padding: 8px 10px;
}

.setting-field textarea {
  min-height: 128px;
  resize: vertical;
  line-height: 1.6;
}

.setting-field input::placeholder,
.setting-field textarea::placeholder {
  color: var(--term-fg-dim);
}

.setting-actions {
  justify-content: flex-start;
  margin: 15px 14px 0;
}

.file-picker {
  box-sizing: border-box;
  min-height: 72px;
  justify-content: center;
  padding: 10px;
  border-style: dashed;
  cursor: pointer;
}

.file-picker input {
  position: absolute;
  width: 1px;
  height: 1px;
  opacity: 0;
  pointer-events: none;
}

.file-picker em {
  color: var(--term-fg-muted);
  font-size: 10.5px;
  font-style: normal;
}

.import-grid {
  display: grid;
  grid-template-columns: 140px minmax(0, 1fr);
  gap: 10px;
  margin: 15px 14px 0;
}

.import-grid .setting-field,
.import-grid .file-picker {
  margin: 0;
}

.detected-columns {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin: 11px 14px 0;
  color: var(--term-fg-dim);
  font-size: 10.5px;
}

.detected-columns b {
  padding: 3px 6px;
  border: 1px solid rgba(56, 189, 248, 0.22);
  border-radius: 4px;
  color: var(--term-accent);
  background: rgba(56, 189, 248, 0.06);
  font-family: var(--term-font-mono);
  font-size: 10px;
  font-weight: 500;
}

.inline-feedback {
  margin: 11px 14px 0;
  color: var(--term-success);
  font-size: 11px;
}

.inline-feedback.error {
  color: var(--term-warning);
}

.import-button {
  margin: 13px 14px 0;
}

.status-tag {
  display: inline-flex;
  padding: 3px 7px;
  border: 1px solid var(--term-border);
  border-radius: 999px;
  font-size: 10px;
}

.status-tag.success {
  color: var(--term-success);
  border-color: rgba(34, 197, 94, 0.28);
  background: rgba(34, 197, 94, 0.07);
}

.status-tag.error {
  color: var(--term-danger);
  border-color: rgba(244, 63, 94, 0.28);
  background: rgba(244, 63, 94, 0.07);
}

.status-tag.muted {
  color: var(--term-fg-muted);
}

@media (max-width: 900px) {
  .settings-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .quant-page {
    padding: 16px 12px 26px;
  }

  .quant-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .collection-controls {
    align-items: flex-start;
    max-width: 100%;
  }

  .metric-strip {
    grid-template-columns: 1fr;
  }

  .section-heading {
    align-items: flex-start;
    flex-direction: column;
  }

  .inline-controls {
    width: 100%;
  }

  .date-input {
    flex: 1;
  }

  .import-grid {
    grid-template-columns: 1fr;
  }
}
</style>
