<template>
  <div class="practice-page">
    <header class="page-heading">
      <div><span class="eyebrow">历史 · 日线 · 独立账户</span><h1>交易盲练 <span>把决定留在揭晓之前</span></h1></div>
      <a-button type="primary" :disabled="busy || loading" @click="showCreate = true"><PlusOutlined /> 新建练习</a-button>
    </header>
    <div class="practice-workspace">
      <aside class="session-panel">
        <div class="section-label"><h2>我的练习</h2><a-button type="text" size="small" :loading="listLoading" :disabled="busy" aria-label="刷新练习列表" @click="reloadList"><ReloadOutlined /></a-button></div>
        <a-alert v-if="listError" type="error" :message="listError" show-icon class="list-error" />
        <div v-if="listLoading && !sessions.length" class="session-empty"><a-spin size="small" /> 正在读取练习</div>
        <div v-else-if="!sessions.length" class="session-empty">还没有练习。<br />选择已同步的历史区间，开始逐日决策。</div>
        <div class="session-list">
          <button v-for="session in sessions" :key="session.id" class="session-item" :class="{ active: selectedId === session.id }" :disabled="busy || loading" @click="selectSession(session.id)">
            <span class="session-top"><strong>{{ session.stockCode }}</strong><span :class="['status-dot', { finished: session.status === 'FINISHED' }]">{{ session.status === 'ACTIVE' ? '进行中' : '已结束' }}</span></span>
            <span class="session-dates">{{ session.startDate }} → {{ session.endDate }}</span>
            <span class="session-bottom"><span>揭晓至 {{ session.currentDate }}</span><b :class="rateClass(session.returnRate)">{{ percent(session.returnRate) }}</b></span>
          </button>
        </div>
        <div class="session-isolation"><SafetyCertificateOutlined /><span>资金、委托与标注仅属于本次练习，独立于正式账户。</span></div>
      </aside>
      <main class="practice-main" :aria-busy="busy || loading">
        <a-alert v-if="error" type="warning" show-icon class="error-banner">
          <template #message>{{ error }}</template>
          <template #action><a-button v-if="selectedId" size="small" :disabled="busy || loading" @click="load(selectedId)">重新载入</a-button></template>
        </a-alert>
        <div v-if="loading" class="state-panel"><a-spin tip="正在恢复练习进度…" /></div>
        <template v-else-if="view">
          <section class="decision-header panel">
            <div class="decision-title"><span :class="['state-badge', { finished: isFinished }]">{{ isFinished ? '复盘' : '盲练中' }}</span><h2>{{ view.stockCode }}</h2><span class="range">{{ view.startDate }} → {{ view.endDate }}</span></div>
            <div class="decision-clock"><div><span>{{ isFinished ? '实际结束日' : '当前决策日' }}</span><strong>{{ view.currentDate }}</strong></div><div class="progress-details"><span>已揭晓 {{ view.revealedSteps }} / {{ view.totalSteps }} 根</span><div class="progress-track"><i :style="{ width: `${progress}%` }"></i></div><small>另有 {{ view.backgroundCount }} 根背景，不计入收益</small></div></div>
            <p v-if="view.revealedSteps === 0 && view.currentDate !== view.startDate" class="date-warning"><WarningOutlined /> 起始日期无记录，实际起点为 {{ view.currentDate }}。无记录日期无法区分停牌与数据缺失。</p>
            <p v-if="isFinished" class="review-note">结果已冻结。{{ view.revealedSteps < view.totalSteps ? '本次为提前结束；后续行情仅供复盘。' : '已到达所选区间终点。' }} 剩余持仓按结束日收盘估值，未强制卖出。</p>
          </section>
          <section class="metrics panel" aria-label="练习账户指标">
            <div class="metric primary"><span>总权益 / 元</span><strong>{{ money(view.equity) }}</strong><small>初始 {{ money(view.initialCash) }}</small></div>
            <div class="metric"><span>账户收益</span><strong :class="rateClass(view.returnRate)">{{ percent(view.returnRate) }}</strong><small>超额 <b :class="rateClass(view.excessReturnRate)">{{ percent(view.excessReturnRate) }}</b></small></div>
            <div class="metric"><span>买入持有收益</span><strong :class="rateClass(view.benchmarkReturnRate)">{{ percent(view.benchmarkReturnRate) }}</strong><small>基准权益 {{ money(view.benchmarkEquity) }}</small></div>
            <div class="metric"><span>最大回撤</span><strong>{{ (view.maxDrawdown * 100).toFixed(2) }}%</strong><small>按已揭晓日收盘权益</small></div>
            <div class="metric"><span>累计费用 / 元</span><strong>{{ money(view.fees) }}</strong><small>佣金与卖出印花税</small></div>
          </section>
          <div class="trading-grid">
            <div class="market-panel panel">
              <PracticeChart :key="view.id" :bars="visibleChartBars" :fills="view.fills" :annotations="view.annotations" :current-date="view.currentDate" :readonly="isFinished" :disabled="!canMutate || showFinish" @add-annotation="addAnnotation" />
            </div>
            <section class="order-panel panel">
              <div class="section-label"><h2>{{ isFinished ? '结束时账户' : '下一日委托' }}</h2><span class="unit">模拟账户</span></div>
              <dl class="account-balance"><div><dt>现金</dt><dd>¥ {{ money(view.cash) }}</dd></div><div><dt>持仓</dt><dd>{{ quantityText(view.quantity) }} 股</dd></div><div><dt>当前可卖</dt><dd>{{ quantityText(view.availableQuantity) }} 股</dd></div></dl>
              <template v-if="!isFinished">
                <p class="order-hint">在 {{ view.currentDate }} 收盘后作决定，下一根日线开盘尝试成交。</p>
                <a-alert v-if="latestRejected" class="rejection-alert" type="warning" show-icon :message="`${sideText(latestRejected.side)} ${quantityText(latestRejected.quantity)} 股未成交`" :description="latestRejected.reason || '该次委托已被拒绝，不会自动追单。'" />
                <div v-if="view.pendingOrder" class="pending-order" aria-live="polite"><span>待成交 · {{ view.pendingOrder.submittedDate }} 提交</span><strong :class="view.pendingOrder.side === 'BUY' ? 'up' : 'down'">{{ sideText(view.pendingOrder.side) }} {{ quantityText(view.pendingOrder.quantity) }} 股</strong><small>价格待下一日开盘揭晓</small><a-button size="small" :disabled="!canMutate || showFinish" @click="cancelOrder">撤销委托</a-button></div>
                <a-form layout="vertical" class="order-form" :model="{ side: orderSide, quantity: orderQuantity }" @finish="placeOrder">
                  <a-form-item label="方向"><a-radio-group v-model:value="orderSide" button-style="solid" :disabled="!canMutate || showFinish"><a-radio-button value="BUY">买入</a-radio-button><a-radio-button value="SELL">卖出</a-radio-button></a-radio-group></a-form-item>
                  <a-form-item label="固定股数" :validate-status="quantityError ? 'error' : undefined" :help="quantityError || '100 股整数倍；不按现金自动缩量'"><a-input-number v-model:value="orderQuantity" :min="100" :max="10000000" :step="100" :precision="0" :disabled="!canMutate || showFinish" aria-label="委托固定股数" style="width: 100%" /></a-form-item>
                  <a-button html-type="submit" block :loading="busy" :disabled="!canMutate || showFinish">{{ view.pendingOrder ? '替换待成交委托' : '提交下一日委托' }}</a-button>
                </a-form>
                <p class="execution-hint">T+1：今日买入可于下一日卖出。成交时按开盘价格、资金与可卖股数重新校验；不足则整单拒绝，不部分成交、不追单。</p>
                <div class="advance-controls"><a-button type="primary" size="large" block :loading="busy" :disabled="!canMutate || showFinish" @click="advance">{{ view.pendingOrder ? '尝试成交并揭晓下一日' : '揭晓下一交易日' }} <StepForwardOutlined /></a-button><a-button type="text" block :disabled="!canMutate || showFinish" @click="showFinish = true">提前结束并揭晓完整走势</a-button></div>
              </template>
              <div v-else class="finished-account"><CheckCircleOutlined /><strong>委托与推进已关闭</strong><p>收益、基准和回撤均截至 {{ view.currentDate }}。可在完整图表上复盘逐笔成交。</p><a-button block :disabled="busy" @click="repeatPractice">按相同区间新建练习</a-button></div>
            </section>
          </div>
          <section class="panel detail-panel">
            <a-tabs v-model:active-key="detailTab" class="detail-tabs">
              <a-tab-pane key="equity" tab="权益曲线"><PracticeEquityCurve :points="view.equityCurve" /></a-tab-pane>
              <a-tab-pane key="orders" :tab="`委托记录 · ${view.orders.length}`"><div class="table-wrap"><table><thead><tr><th>提交日</th><th>方向</th><th>股数</th><th>状态</th><th>执行日</th><th>说明</th></tr></thead><tbody><tr v-for="order in [...view.orders].reverse()" :key="order.id"><td>{{ order.submittedDate }}</td><td :class="order.side === 'BUY' ? 'up' : 'down'">{{ sideText(order.side) }}</td><td>{{ quantityText(order.quantity) }}</td><td>{{ orderStatus[order.status] }}</td><td>{{ order.executedDate || '—' }}</td><td class="reason">{{ order.reason || (order.status === 'PENDING' ? '下一根日线开盘尝试成交' : '—') }}</td></tr><tr v-if="!view.orders.length"><td colspan="6" class="empty-row">还没有委托；也可以不下单，直接揭晓下一日。</td></tr></tbody></table></div></a-tab-pane>
              <a-tab-pane key="fills" :tab="`逐笔成交 · ${view.fills.length}`"><div class="table-wrap"><table><thead><tr><th>成交日</th><th>方向</th><th>股数</th><th>成交价</th><th>金额</th><th>佣金</th><th>印花税</th><th>总费用</th></tr></thead><tbody><tr v-for="fill in [...view.fills].reverse()" :key="fill.id"><td>{{ fill.date }}</td><td :class="fill.side === 'BUY' ? 'up' : 'down'">{{ sideText(fill.side) }}</td><td>{{ quantityText(fill.quantity) }}</td><td>{{ money(fill.price) }}</td><td>{{ money(fill.amount) }}</td><td>{{ money(fill.commission) }}</td><td>{{ money(fill.tax) }}</td><td>{{ money(fill.fee) }}</td></tr><tr v-if="!view.fills.length"><td colspan="8" class="empty-row">尚无模拟成交。图上的手工买卖点只作标注。</td></tr></tbody></table></div></a-tab-pane>
              <a-tab-pane key="annotations" :tab="`练习标注 · ${view.annotations.length}`"><div class="annotation-notes"><p>标注只保存在本次练习。画线、手工买点、手工卖点互斥，切换保留已保存标注，Esc 退出工具。{{ isFinished ? '结束后只读。' : '点击图表工具添加。' }}</p><div v-for="annotation in view.annotations" :key="annotation.id" class="annotation-row"><span>{{ annotation.type === 'LINE' ? '画线' : annotation.type === 'BUY' ? '手工买点' : '手工卖点' }}</span><span>{{ annotation.points.map(point => `${point.date} @${point.price.toFixed(2)}`).join(' → ') }}</span><a-button v-if="!isFinished" type="text" size="small" :disabled="!canMutate || showFinish" danger @click="removeAnnotation(annotation.id)">删除</a-button></div><div v-if="!view.annotations.length" class="empty-row">还没有标注。标注不会产生交易。</div></div></a-tab-pane>
            </a-tabs>
          </section>
          <details class="rules-panel"><summary>固定训练规则与数据边界</summary><PracticeRules /></details>
        </template>
        <div v-else-if="!loading" class="welcome panel">
          <span class="welcome-mark"><ExperimentOutlined /></span><span class="eyebrow">历史交易练习</span><h2>先看已知，再作决定。</h2><p>从指定日期开始，一次揭晓一根日线。委托等到下一日开盘才尝试成交，结束后在完整走势上复盘自己的选择。</p><a-button type="primary" size="large" :disabled="busy" @click="showCreate = true">选择股票与历史区间</a-button><PracticeRules /></div>
      </main>
    </div>
    <a-modal v-model:open="showCreate" title="新建历史交易练习" ok-text="创建并进入练习" cancel-text="取消" :confirm-loading="busy" :cancel-button-props="{ disabled: busy }" :closable="!busy" :mask-closable="!busy" :keyboard="!busy" @ok="createPractice">
      <a-form layout="vertical" class="create-form">
        <a-form-item label="A 股代码"><a-input v-model:value="createForm.stockCode" :disabled="busy" placeholder="600519 或 600519.SH" :maxlength="12" @press-enter="createPractice" /><small>仅使用已同步的未复权日线，不自动获取其他数据源。</small></a-form-item>
        <div class="date-fields"><a-form-item label="起始日期"><a-date-picker v-model:value="createForm.startDate" :disabled="busy" value-format="YYYY-MM-DD" placeholder="最初可见截止日" style="width: 100%" /></a-form-item><a-form-item label="结束日期"><a-date-picker v-model:value="createForm.endDate" :disabled="busy" value-format="YYYY-MM-DD" placeholder="练习区间终点" style="width: 100%" /></a-form-item></div>
        <a-form-item label="初始资金 / 元"><a-input-number v-model:value="createForm.initialCash" :disabled="busy" :min="100" :max="1000000000" :precision="2" style="width: 100%" /></a-form-item>
      </a-form>
      <a-alert v-if="createError" type="error" :message="createError" show-icon class="create-error" />
      <a-alert type="info" show-icon message="起点含最多 120 根背景日线，收益从起点后计算。" description="非交易日取不晚于起始日的最后已存记录。无记录日期不补线，无法区分停牌与数据缺失；数据未同步或区间校验失败时，请调整区间或先在数据同步页同步。" />
      <p class="create-disclosure">固定训练费用：双向佣金万三、每笔最低 5 元；卖出印花税万五。使用未复权价，不模拟分红送转；费率不代表历史费率。完整规则可在练习中随时查看。</p>
    </a-modal>
    <a-modal v-model:open="showFinish" title="提前结束并揭晓完整走势？" ok-text="结束并进入复盘" cancel-text="继续盲练" :confirm-loading="busy" :cancel-button-props="{ disabled: busy }" :closable="!busy" :mask-closable="false" :keyboard="!busy" @ok="finish">
      <p class="finish-copy">结束会立即揭晓所选区间的后续行情，无法回到本次盲练。未成交委托会撤销；持仓按 <strong>{{ view?.currentDate }}</strong> 收盘价估值，不强制卖出。</p><p class="finish-copy">收益、基准、费用和最大回撤冻结在实际结束日，之后揭晓的走势只供复盘。</p>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { CheckCircleOutlined, ExperimentOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined, StepForwardOutlined, WarningOutlined } from '@ant-design/icons-vue'
import { practiceApi, type PracticeAnnotation, type PracticeSide } from '@/api/practice'
import PracticeChart from './components/PracticeChart.vue'
import PracticeEquityCurve from './components/PracticeEquityCurve.vue'
import PracticeRules from './components/PracticeRules.vue'
import { annotationAtVisiblePoint, chartBars, money, normalizePracticeCode, orderStatus, percent, quantityText, sideText, validQuantity, validateCreation } from './practiceData'
import { usePracticeSession } from './usePracticeSession'

const route = useRoute()
const router = useRouter()
const { sessions, view, listLoading, loading, busy, error, listError, canMutate, reloadList, load, create, mutate } = usePracticeSession()
const selectedId = computed(() => typeof route.query.session === 'string' ? route.query.session : '')
const isFinished = computed(() => view.value?.status === 'FINISHED')
const visibleChartBars = computed(() => view.value ? chartBars(view.value) : [])
const progress = computed(() => view.value?.totalSteps ? Math.min(100, view.value.revealedSteps / view.value.totalSteps * 100) : 0)
const latestRejected = computed(() => {
  const latest = view.value?.orders.at(-1)
  return latest?.status === 'REJECTED' ? latest : null
})
const showCreate = ref(false)
const showFinish = ref(false)
const createError = ref('')
const quantityError = ref('')
const detailTab = ref('equity')
const orderSide = ref<PracticeSide>('BUY')
const orderQuantity = ref<number | null>(100)
const createForm = reactive({ stockCode: '', startDate: '', endDate: '', initialCash: 100000 as number | null })
const rateClass = (value: number) => value > 0 ? 'up' : value < 0 ? 'down' : ''
const selectSession = (id: string) => { if (!busy.value && !loading.value) router.replace({ path: '/practice', query: { session: id } }) }
watch(selectedId, id => { if (id && id !== view.value?.id) load(id) }, { immediate: true })
watch(() => view.value?.id, () => {
  showFinish.value = false
  quantityError.value = ''
  detailTab.value = 'equity'
  orderSide.value = view.value?.pendingOrder?.side ?? 'BUY'
  orderQuantity.value = view.value?.pendingOrder?.quantity ?? 100
})
watch(orderQuantity, () => { quantityError.value = '' })
watch(showCreate, value => { if (value) createError.value = '' })
onMounted(reloadList)
async function createPractice() {
  if (busy.value) return
  const data = { ...createForm, stockCode: normalizePracticeCode(createForm.stockCode), initialCash: createForm.initialCash ?? 0 }
  createError.value = validateCreation(data) ?? ''
  if (createError.value) return
  const result = await create(data)
  if (result) { showCreate.value = false; selectSession(result.id) }
  else createError.value = error.value
}
function repeatPractice() {
  if (!view.value) return
  Object.assign(createForm, { stockCode: view.value.stockCode, startDate: view.value.startDate, endDate: view.value.endDate, initialCash: view.value.initialCash })
  showCreate.value = true
}
async function placeOrder() {
  if (!validQuantity(orderQuantity.value)) { quantityError.value = '请输入 100 至 10,000,000 股，且为 100 股整数倍'; return }
  const quantity = orderQuantity.value
  await mutate((id, version) => practiceApi.order(id, version, orderSide.value, quantity))
}
async function cancelOrder() { await mutate(practiceApi.cancel) }
async function advance() { await mutate(practiceApi.advance) }
async function finish() { await mutate(practiceApi.finish); showFinish.value = false }
async function addAnnotation(annotation: PracticeAnnotation) {
  if (!view.value || view.value.annotations.length >= 200 || !annotationAtVisiblePoint(annotation, view.value.bars, view.value.currentDate)) return
  const annotations = [...view.value.annotations, annotation]
  await mutate((id, version) => practiceApi.annotations(id, version, annotations))
}
async function removeAnnotation(idToRemove: string) {
  if (!view.value) return
  const annotations = view.value.annotations.filter(annotation => annotation.id !== idToRemove)
  await mutate((id, version) => practiceApi.annotations(id, version, annotations))
}
</script>

<style scoped>
.practice-page { flex: 1; min-height: 0; overflow-y: auto; padding: 22px 24px 28px; color: var(--term-fg); }
.page-heading { display: flex; justify-content: space-between; gap: 20px; align-items: center; margin-bottom: 20px; }
.eyebrow { font-size: 10px; letter-spacing: 1.5px; color: var(--term-fg-muted); }
h1 { margin: 5px 0 0; color: var(--term-fg); font-size: 23px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif; font-weight: 600; }
h1 span { font-size: 12px; font-weight: 400; color: var(--term-fg-muted); margin-left: 14px; }
.practice-workspace { display: grid; grid-template-columns: 236px minmax(0, 1fr); gap: 18px; align-items: start; }
.panel, .session-panel { background: var(--term-panel-bg); border: 1px solid var(--term-border); border-radius: 6px; overflow: hidden; }
.session-panel { position: sticky; top: 0; }
.section-label { display: flex; align-items: center; justify-content: space-between; min-height: 44px; padding: 10px 14px; border-bottom: 1px solid var(--term-border); }
h2 { font-size: 13px; font-weight: 600; margin: 0; color: var(--term-fg); }
.session-list { max-height: 560px; overflow-y: auto; }
.session-item { display: flex; flex-direction: column; text-align: left; width: 100%; gap: 10px; padding: 15px 13px; border: 0; border-bottom: 1px solid var(--term-border); border-left: 2px solid transparent; font: inherit; color: var(--term-fg); background: transparent; cursor: pointer; }
.session-item:hover { background: var(--term-hover); }
.session-item.active { background: #1f6feb12; border-left-color: var(--term-accent); }
.session-item:focus-visible { outline: 2px solid var(--term-accent); outline-offset: -3px; }
.session-item:disabled { cursor: wait; opacity: .6; }
.session-top, .session-bottom { display: flex; justify-content: space-between; align-items: center; gap: 6px; }
.session-top strong { font-size: 13px; letter-spacing: .3px; }
.status-dot { color: #e3b341; font-size: 10px; }
.status-dot::before { content: ''; display: inline-block; width: 5px; height: 5px; background: currentColor; border-radius: 50%; margin-right: 4px; }
.status-dot.finished { color: var(--term-fg-muted); }
.session-dates { font-size: 10px; color: var(--term-fg-muted); }
.session-bottom { font-size: 10px; color: var(--term-fg-muted); }
.session-bottom b { font-size: 11px; font-weight: 500; }
.session-isolation { display: flex; gap: 8px; padding: 15px 13px; color: var(--term-fg-muted); font-size: 11px; line-height: 1.8; }
.session-isolation :deep(.anticon) { margin-top: 4px; color: var(--term-accent); }
.session-empty { color: var(--term-fg-muted); padding: 26px 16px; font-size: 12px; line-height: 1.9; }
.list-error { margin: 10px; }
.practice-main { min-width: 0; display: flex; flex-direction: column; gap: 14px; }
.error-banner { margin: 0; }
.state-panel { min-height: 400px; display: grid; place-items: center; }
.decision-header { padding: 16px 20px; border-top: 2px solid #d29922; }
.decision-title { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.decision-title h2 { font-size: 17px; letter-spacing: .5px; }
.state-badge { background: #d2992217; color: #e3b341; border: 1px solid #d2992244; padding: 2px 6px; border-radius: 3px; font-size: 10px; }
.state-badge.finished { color: var(--term-accent); background: #58a6ff12; border-color: #58a6ff44; }
.range { margin-left: auto; font-size: 11px; color: var(--term-fg-muted); }
.decision-clock { display: flex; align-items: flex-end; justify-content: space-between; flex-wrap: wrap; gap: 18px; margin-top: 19px; }
.decision-clock > div:first-child { display: flex; flex-direction: column; gap: 4px; }
.decision-clock span { font-size: 11px; color: var(--term-fg-muted); }
.decision-clock strong { font-size: 30px; font-weight: 500; letter-spacing: 1px; line-height: 1.3; color: #e6edf3; }
.progress-details { width: 250px; padding-bottom: 2px; }
.progress-track { height: 3px; background: var(--term-border); margin: 9px 0 7px; border-radius: 3px; overflow: hidden; }
.progress-track i { display: block; height: 100%; background: #d29922; }
.progress-details small { font-size: 10px; color: var(--term-fg-muted); }
.date-warning { margin: 14px 0 0; font-size: 11px; line-height: 1.8; color: #e3b341; }
.review-note { color: var(--term-fg-muted); margin: 12px 0 0; font-size: 11px; line-height: 1.8; }
.metrics { display: grid; grid-template-columns: 1.25fr repeat(4, 1fr); }
.metric { padding: 16px 18px; display: flex; flex-direction: column; gap: 7px; border-right: 1px solid var(--term-border); min-width: 0; }
.metric:last-child { border-right: 0; }
.metric > span { color: var(--term-fg-muted); font-size: 11px; }
.metric strong { font-size: 21px; font-weight: 500; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.metric.primary strong { color: #e6edf3; }
.metric small { color: var(--term-fg-muted); font-size: 10px; line-height: 1.6; }
.metric small b { font-weight: 400; }
.trading-grid { display: grid; grid-template-columns: minmax(0, 1fr) 264px; gap: 14px; align-items: stretch; }
.market-panel { min-width: 0; align-self: start; }
.order-panel { padding-bottom: 12px; }
.unit { color: var(--term-fg-muted); font-size: 10px; }
.account-balance { margin: 0; padding: 13px 16px 11px; border-bottom: 1px solid var(--term-border); }
.account-balance div { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 5px 0; }
.account-balance dt { color: var(--term-fg-muted); font-size: 11px; }
.account-balance dd { margin: 0; font-size: 12px; }
.order-hint { color: var(--term-fg-muted); font-size: 11px; line-height: 1.9; margin: 13px 16px 10px; }
.rejection-alert { margin: 0 16px 12px; }.rejection-alert :deep(.ant-alert-message), .rejection-alert :deep(.ant-alert-description) { font-size: 11px; }
.pending-order { display: flex; flex-direction: column; gap: 5px; margin: 0 16px 12px; padding: 11px; background: #d2992209; border: 1px solid #d2992240; border-radius: 4px; }
.pending-order > span { color: #e3b341; font-size: 10px; }.pending-order strong { font-size: 16px; font-weight: 500; }
.pending-order small { font-size: 10px; color: var(--term-fg-muted); }
.pending-order :deep(.ant-btn) { margin-top: 5px; align-self: flex-start; }
.order-form { padding: 0 16px; }
.order-form :deep(.ant-form-item) { margin-bottom: 12px; }
.order-form :deep(.ant-form-item-label) { padding-bottom: 5px; }
.order-form :deep(.ant-form-item-label > label), .order-form :deep(.ant-form-item-explain) { font-size: 11px; }
.order-form :deep(.ant-radio-group) { display: flex; width: 100%; }.order-form :deep(.ant-radio-button-wrapper) { flex: 1; text-align: center; }
.execution-hint { font-size: 10px; color: var(--term-fg-muted); line-height: 1.9; margin: 12px 16px; }
.advance-controls { border-top: 1px solid var(--term-border); padding: 14px 16px 0; display: flex; flex-direction: column; gap: 5px; }
.advance-controls :deep(.ant-btn-primary) { font-size: 12px; }.advance-controls :deep(.ant-btn-text) { color: var(--term-fg-muted); font-size: 10px; }
.finished-account { padding: 25px 16px; display: flex; flex-direction: column; gap: 12px; }.finished-account > :deep(.anticon) { color: var(--term-accent); font-size: 25px; align-self: flex-start; }.finished-account strong { font-size: 13px; }.finished-account p { color: var(--term-fg-muted); font-size: 11px; line-height: 1.9; margin: 0 0 6px; }
.detail-tabs :deep(.ant-tabs-nav) { padding: 0 16px; margin: 0; }.detail-tabs :deep(.ant-tabs-tab) { padding: 13px 0; font-size: 12px; }.detail-tabs :deep(.ant-tabs-content-holder) { min-height: 130px; }
.table-wrap { overflow-x: auto; max-height: 350px; }table { width: 100%; border-collapse: collapse; font-size: 11px; white-space: nowrap; }th { color: var(--term-fg-muted); background: #1c2128; position: sticky; top: 0; text-align: left; font-weight: 400; }td, th { padding: 11px 16px; border-bottom: 1px solid var(--term-border); }td.reason { white-space: normal; min-width: 190px; color: var(--term-fg-muted); }.empty-row { padding: 30px 20px; text-align: center; color: var(--term-fg-muted); font-size: 11px; }
.annotation-notes { padding: 14px 16px; }.annotation-notes > p { font-size: 11px; color: var(--term-fg-muted); line-height: 1.9; }.annotation-row { display: flex; gap: 16px; align-items: center; padding: 9px 0; border-top: 1px solid var(--term-border); font-size: 11px; }.annotation-row > span:nth-child(2) { flex: 1; color: var(--term-fg-muted); }.annotation-row :deep(.ant-btn) { font-size: 11px; }
.rules-panel { border: 1px solid var(--term-border); border-radius: 5px; padding: 12px 16px; }.rules-panel summary { cursor: pointer; color: var(--term-fg-muted); font-size: 11px; }.rules-panel summary:focus-visible { outline: 2px solid var(--term-accent); outline-offset: 4px; }
.welcome { padding: 55px 48px 30px; min-height: 620px; display: flex; align-items: flex-start; flex-direction: column; }.welcome-mark { color: #d29922; font-size: 40px; margin-bottom: 26px; }.welcome h2 { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Microsoft YaHei', sans-serif; font-size: 32px; font-weight: 500; margin: 14px 0; }.welcome > p { color: var(--term-fg-muted); font-size: 13px; line-height: 2.1; max-width: 570px; margin: 0 0 26px; }.welcome > :deep(.practice-rules) { border-top: 1px solid var(--term-border); margin-top: 38px; padding-top: 20px; }
.up { color: var(--term-up); }.down { color: var(--term-down); }
.create-form { margin-top: 20px; }.create-form small { display: block; margin-top: 7px; color: var(--term-fg-muted); font-size: 11px; }.date-fields { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }.create-disclosure { font-size: 11px; color: var(--term-fg-muted); line-height: 1.9; margin: 16px 0 0; }.create-error { margin-bottom: 12px; }.finish-copy { font-size: 13px; line-height: 1.9; color: var(--term-fg); margin: 16px 0; }
@media (min-width: 1700px) { .practice-page { padding: 26px 32px; }.practice-workspace { grid-template-columns: 252px minmax(0, 1fr); }.trading-grid { grid-template-columns: minmax(0, 1fr) 285px; } }
@media (max-width: 1350px) { .practice-workspace { grid-template-columns: 200px minmax(0, 1fr); gap: 12px; }.practice-page { padding: 18px 16px; }.trading-grid { grid-template-columns: minmax(0, 1fr); }.order-panel { display: grid; grid-template-columns: 1fr 1fr; gap: 0 16px; padding: 0 16px 14px; }.order-panel .section-label { grid-column: 1 / -1; margin: 0 -16px; }.account-balance { grid-column: 1; grid-row: 2; padding: 14px 0; }.order-hint { grid-column: 1; margin: 12px 0; }.pending-order { grid-column: 1; margin: 0 0 12px; }.order-form { grid-column: 2; grid-row: 2 / span 3; padding: 14px 0 0; }.execution-hint { grid-column: 1; margin: 0; }.advance-controls { grid-column: 2; grid-row: 5; border-top: 0; padding: 10px 0 0; }.finished-account { grid-column: 2; grid-row: 2 / span 2; padding: 18px 0; }.metrics { grid-template-columns: repeat(3, 1fr); }.metric:nth-child(3) { border-right: 0; }.metric:nth-child(n+4) { border-top: 1px solid var(--term-border); }.metric { padding: 13px; }.metric strong { font-size: 19px; } }
@media (max-width: 1000px) { .practice-workspace { grid-template-columns: minmax(0, 1fr); }.session-panel { position: static; }.session-list { display: flex; overflow-x: auto; max-height: none; }.session-item { min-width: 220px; width: 220px; border-bottom: 0; border-right: 1px solid var(--term-border); }.session-isolation { display: none; }.session-panel .section-label { min-height: 38px; padding: 6px 12px; }.session-empty { padding: 12px; }.page-heading { margin-bottom: 14px; }h1 span { display: none; }.welcome { padding: 35px 25px; min-height: 500px; } }
@media (max-width: 600px) { .practice-page { padding: 14px 10px; }.page-heading { align-items: flex-end; gap: 10px; }h1 { font-size: 20px; }.eyebrow { font-size: 9px; }.decision-header { padding: 14px; }.range { margin-left: 0; width: 100%; }.decision-clock strong { font-size: 25px; }.progress-details { width: 100%; }.metrics { grid-template-columns: 1fr 1fr; }.metric:nth-child(3) { border-right: 1px solid var(--term-border); border-top: 1px solid var(--term-border); }.metric:nth-child(even) { border-right: 0; }.metric { padding: 12px; }.metric strong { font-size: 17px; }.metric small { font-size: 9px; }.order-panel { display: block; padding: 0 14px 14px; }.order-panel .section-label { margin: 0 -14px; }.order-form { padding-top: 6px; }.advance-controls { border-top: 1px solid var(--term-border); margin-top: 12px; padding-top: 12px; }.annotation-row { flex-wrap: wrap; gap: 5px; }.annotation-row > span:nth-child(2) { flex-basis: 100%; }.date-fields { grid-template-columns: 1fr; gap: 0; }.welcome h2 { font-size: 26px; }.welcome { padding: 28px 20px; } }
</style>
