<template>
  <div class="journal-page">
    <!-- 顶部工具栏 -->
    <div class="journal-header">
      <div class="header-left">
        <h2 class="page-title"><AppIcon name="notebook" :size="18" />复盘笔记</h2>
        <span class="page-subtitle">总结交易逻辑 · 记录情绪波动 · 沉淀经验得失</span>
      </div>
      <div class="header-right">
        <a-button type="primary" @click="openCreateModal">
          <template #icon><AppIcon name="notebook" :size="14" /></template>
          写复盘笔记
        </a-button>
      </div>
    </div>

    <!-- 筛选过滤条 -->
    <a-card class="filter-card">
      <div class="filter-row">
        <div class="filter-group">
          <span class="filter-label">关键词：</span>
          <a-input
            v-model:value="queryParams.keyword"
            placeholder="搜索标题、内容、股票或反思"
            style="width: 200px"
            allow-clear
            @pressEnter="handleSearch"
          />
        </div>

        <div class="filter-group">
          <span class="filter-label">股票代码：</span>
          <a-input
            v-model:value="queryParams.stockCode"
            placeholder="如 600519.SH"
            style="width: 140px"
            allow-clear
            @pressEnter="handleSearch"
          />
        </div>

        <div class="filter-group">
          <span class="filter-label">标签：</span>
          <a-input
            v-model:value="queryParams.tag"
            placeholder="如 止损 / 追高"
            style="width: 120px"
            allow-clear
            @pressEnter="handleSearch"
          />
        </div>

        <div class="filter-group">
          <span class="filter-label">日期范围：</span>
          <a-range-picker
            v-model:value="dateRange"
            value-format="YYYY-MM-DD"
            style="width: 240px"
            @change="handleDateChange"
          />
        </div>

        <div class="filter-actions">
          <a-button type="primary" @click="handleSearch">查询</a-button>
          <a-button @click="resetQuery">重置</a-button>
        </div>
      </div>
    </a-card>

    <!-- 月度复盘日历速览 -->
    <div v-if="calendarItems.length > 0" class="calendar-strip">
 <span class="strip-label">本月复盘足迹：</span>
      <div class="strip-items">
        <div
          v-for="item in calendarItems"
          :key="item.date"
          class="strip-item"
          :title="`${item.date} 共 ${item.count} 篇笔记\n${item.titles.join('\n')}`"
          @click="filterByDate(item.date)"
        >
          <span class="item-date">{{ item.date.slice(5) }}</span>
          <span
            class="item-emotion"
            :class="`lv-${emotionLevel(item.avgEmotion)}`"
            :title="`平均情绪 ${emotionText(item.avgEmotion)} / 5`"
          >{{ emotionText(item.avgEmotion) }}</span>
          <span class="item-count">{{ item.count }}篇</span>
        </div>
      </div>
    </div>

    <!-- 笔记列表 -->
    <a-spin :spinning="loading">
      <div v-if="journalList.length > 0" class="journal-grid">
        <a-card
          v-for="item in journalList"
          :key="item.id"
          class="journal-card"
          hoverable
        >
          <!-- 笔记头部 -->
          <div class="card-top">
            <div class="card-meta">
              <span class="journal-date">{{ item.journalDate }}</span>
              <a-tag v-if="item.stockCode" color="blue">
                {{ item.stockName || item.stockCode }} ({{ item.stockCode }})
              </a-tag>
              <a-tag :color="getEmotionTagColor(item.emotionScore)">
                {{ getEmotionLabel(item.emotionScore) }}
              </a-tag>
              <a-tag v-if="item.marketSentiment" color="purple">{{ item.marketSentiment }}</a-tag>
              <a-tag v-if="item.strategyType" color="cyan">{{ item.strategyType }}</a-tag>
            </div>
            <div class="card-actions">
              <a-button type="link" size="small" @click="openViewModal(item)">查看</a-button>
              <a-button type="link" size="small" @click="openEditModal(item)">编辑</a-button>
              <a-popconfirm title="确定要删除这篇复盘笔记吗？" @confirm="handleDelete(item.id)">
                <a-button type="link" danger size="small">删除</a-button>
              </a-popconfirm>
            </div>
          </div>

          <!-- 笔记标题 -->
          <h3 class="card-title" @click="openViewModal(item)">{{ item.title }}</h3>

          <!-- 复盘正文摘要 -->
          <p class="card-content-preview">{{ item.content }}</p>

          <!-- 得失反思高亮 -->
          <div v-if="item.reflection" class="card-reflection">
 <span class="reflection-label">反思心得：</span>
            <span class="reflection-text">{{ item.reflection }}</span>
          </div>

          <!-- 标签栏 -->
          <div v-if="item.tags" class="card-tags">
            <a-tag
              v-for="tag in item.tags.split(',').filter(Boolean)"
              :key="tag"
              class="tag-item"
              @click.stop="filterByTag(tag.trim())"
            >
              # {{ tag.trim() }}
            </a-tag>
          </div>
        </a-card>
      </div>

      <a-empty v-else description="暂无复盘笔记，快去写下第一篇交易反思吧！" style="margin-top: 48px">
        <a-button type="primary" @click="openCreateModal">立即记录</a-button>
      </a-empty>

      <!-- 分页条 -->
      <div v-if="total > 0" class="pagination-bar">
        <a-pagination
          v-model:current="queryParams.pageNum"
          v-model:pageSize="queryParams.pageSize"
          :total="total"
          show-size-changer
          show-quick-jumper
          :show-total="(t: number) => `共 ${t} 篇笔记`"
          @change="loadJournals"
        />
      </div>
    </a-spin>

    <!-- 新增 / 编辑弹窗 -->
    <a-modal
      v-model:open="showModal"
      :title="isEdit ? '编辑复盘笔记' : '新增复盘笔记'"
      width="680px"
      :confirm-loading="submitLoading"
      @ok="handleModalSubmit"
    >
      <a-form layout="vertical">
        <a-row :gutter="16">
          <a-col :span="16">
            <a-form-item label="笔记标题" required>
              <a-input v-model:value="form.title" placeholder="如：突破买入顺风车 / 抄底被套教训" />
            </a-form-item>
          </a-col>
          <a-col :span="8">
            <a-form-item label="复盘日期" required>
              <a-date-picker v-model:value="form.journalDate" value-format="YYYY-MM-DD" style="width: 100%" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="关联股票代码（可选）">
              <a-input v-model:value="form.stockCode" placeholder="如 600519.SH" @blur="fetchStockName" />
            </a-form-item>
          </a-col>
          <a-col :span="12">
            <a-form-item label="股票名称">
              <a-input v-model:value="form.stockName" placeholder="如 贵州茅台" />
            </a-form-item>
          </a-col>
        </a-row>

        <a-row :gutter="16">
          <a-col :span="12">
            <a-form-item label="交易心理状态 (情绪打分)">
              <a-select v-model:value="form.emotionScore" style="width: 100%">
                <a-select-option :value="1">1 - 恐慌焦虑 (严重受情绪驱使)</a-select-option>
                <a-select-option :value="2">2 - 谨慎保守 (观望防守为主)</a-select-option>
                <a-select-option :value="3">3 - 理性平和 (严格按计划交易)</a-select-option>
                <a-select-option :value="4">4 - 积极自信 (顺应趋势把握大)</a-select-option>
                <a-select-option :value="5">5 - 亢奋冲动 (盲目贪婪风险高)</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="市场环境评估">
              <a-select v-model:value="form.marketSentiment" placeholder="请选择" allow-clear>
                <a-select-option value="普涨大涨">普涨大涨</a-select-option>
                <a-select-option value="结构分化">结构分化</a-select-option>
                <a-select-option value="震荡磨底">震荡磨底</a-select-option>
                <a-select-option value="弱势调整">弱势调整</a-select-option>
                <a-select-option value="极度冰点">极度冰点</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="交易策略分类">
              <a-select v-model:value="form.strategyType" placeholder="请选择" allow-clear>
                <a-select-option value="趋势突破">趋势突破</a-select-option>
                <a-select-option value="超跌反弹">超跌反弹</a-select-option>
                <a-select-option value="逢低定投">逢低定投</a-select-option>
                <a-select-option value="波段做T">波段做T</a-select-option>
                <a-select-option value="打板追强">打板追强</a-select-option>
                <a-select-option value="防守止损">防守止损</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
        </a-row>

        <a-form-item label="复盘与交易逻辑详情" required>
          <a-textarea
            v-model:value="form.content"
            :rows="4"
            placeholder="详细记录入场/出场理由、板块强弱、大盘配合度、支撑阻力位判断等..."
          />
        </a-form-item>

        <a-form-item label="经验得失与反思总结">
          <a-textarea
            v-model:value="form.reflection"
            :rows="2"
            placeholder="本次交易做对了什么？做错了什么？有哪些后续需要坚守的纪律？"
          />
        </a-form-item>

        <a-form-item label="标签（英文逗号隔开）">
          <a-input v-model:value="form.tags" placeholder="例如：严格止损, 突破买点, 仓位过重, 追高" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 笔记详情抽屉/查看弹窗 -->
    <a-modal
      v-model:open="showViewModal"
      :title="currentJournal?.title"
      width="640px"
      :footer="null"
    >
      <div v-if="currentJournal" class="view-detail">
        <div class="view-meta-bar">
 <span class="detail-label">日期：</span>
          <span class="detail-val">{{ currentJournal.journalDate }}</span>
          <a-tag v-if="currentJournal.stockCode" color="blue" style="margin-left: 12px">
            {{ currentJournal.stockName }} ({{ currentJournal.stockCode }})
          </a-tag>
          <a-tag :color="getEmotionTagColor(currentJournal.emotionScore)">
            {{ getEmotionLabel(currentJournal.emotionScore) }}
          </a-tag>
          <a-tag v-if="currentJournal.marketSentiment" color="purple">{{ currentJournal.marketSentiment }}</a-tag>
          <a-tag v-if="currentJournal.strategyType" color="cyan">{{ currentJournal.strategyType }}</a-tag>
        </div>

        <div class="detail-section">
 <h4 class="section-title">复盘分析</h4>
          <div class="section-body content-box">{{ currentJournal.content }}</div>
        </div>

        <div v-if="currentJournal.reflection" class="detail-section">
 <h4 class="section-title">经验反思</h4>
          <div class="section-body reflection-box">{{ currentJournal.reflection }}</div>
        </div>

        <div v-if="currentJournal.tags" class="detail-section">
 <h4 class="section-title">标签</h4>
          <div class="tag-row">
            <a-tag v-for="tag in currentJournal.tags.split(',').filter(Boolean)" :key="tag">
              # {{ tag.trim() }}
            </a-tag>
          </div>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import AppIcon from '@/components/ui/AppIcon.vue'
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import {
  createJournal,
  updateJournal,
  deleteJournal,
  getJournalPage,
  getJournalCalendar,
  type JournalQueryParams
} from '@/api/journal'
import { getStockInfo } from '@/api/stock'
import type { TradeJournal, JournalCalendarItem } from '@/types'

const loading = ref(false)
const submitLoading = ref(false)
const journalList = ref<TradeJournal[]>([])
const calendarItems = ref<JournalCalendarItem[]>([])
const total = ref(0)
const dateRange = ref<[string, string] | null>(null)

const queryParams = reactive<JournalQueryParams>({
  keyword: '',
  stockCode: '',
  tag: '',
  startDate: '',
  endDate: '',
  pageNum: 1,
  pageSize: 9
})

const showModal = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)

const form = reactive({
  title: '',
  journalDate: new Date().toISOString().slice(0, 10),
  stockCode: '',
  stockName: '',
  emotionScore: 3,
  marketSentiment: undefined as string | undefined,
  strategyType: undefined as string | undefined,
  content: '',
  reflection: '',
  tags: ''
})

const showViewModal = ref(false)
const currentJournal = ref<TradeJournal | null>(null)

/** 情绪分值的安全格式化：后端可能返回空值，避免 toFixed 抛错 */
const emotionText = (value?: number | null) => {
  const n = Number(value)
  return Number.isFinite(n) && n > 0 ? n.toFixed(1) : '—'
}

/** 情绪分档 1~5，用于徽标配色 */
const emotionLevel = (value?: number | null) => {
  const n = Number(value)
  if (!Number.isFinite(n) || n <= 0) return 3
  return Math.min(5, Math.max(1, Math.round(n)))
}

const getEmotionLabel = (score: number) => {
  switch (score) {
    case 1: return '恐慌焦虑'
    case 2: return '谨慎防守'
    case 3: return '理性平和'
    case 4: return '积极乐观'
    case 5: return '亢奋冲动'
    default: return '理性平和'
  }
}

const getEmotionTagColor = (score: number) => {
  switch (score) {
    case 1: return 'red'
    case 2: return 'orange'
    case 3: return 'blue'
    case 4: return 'cyan'
    case 5: return 'purple'
    default: return 'default'
  }
}

const loadJournals = async () => {
  loading.value = true
  try {
    const res = await getJournalPage(queryParams)
    if (res.data) {
      journalList.value = res.data.records || []
      total.value = res.data.total || 0
    }
  } catch (err: any) {
    message.error(err.message || '加载复盘笔记失败')
  } finally {
    loading.value = false
  }
}

const loadCalendar = async () => {
  try {
    const res = await getJournalCalendar()
    if (res.data) {
      calendarItems.value = res.data || []
    }
  } catch (err) {
    // optional calendar display
  }
}

const handleSearch = () => {
  queryParams.pageNum = 1
  loadJournals()
}

const resetQuery = () => {
  queryParams.keyword = ''
  queryParams.stockCode = ''
  queryParams.tag = ''
  queryParams.startDate = ''
  queryParams.endDate = ''
  queryParams.pageNum = 1
  dateRange.value = null
  loadJournals()
}

const handleDateChange = (dates: [string, string] | null) => {
  if (dates && dates.length === 2) {
    queryParams.startDate = dates[0]
    queryParams.endDate = dates[1]
  } else {
    queryParams.startDate = ''
    queryParams.endDate = ''
  }
  handleSearch()
}

const filterByDate = (date: string) => {
  queryParams.startDate = date
  queryParams.endDate = date
  dateRange.value = [date, date]
  handleSearch()
}

const filterByTag = (tag: string) => {
  queryParams.tag = tag
  handleSearch()
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
  form.title = ''
  form.journalDate = new Date().toISOString().slice(0, 10)
  form.stockCode = ''
  form.stockName = ''
  form.emotionScore = 3
  form.marketSentiment = undefined
  form.strategyType = undefined
  form.content = ''
  form.reflection = ''
  form.tags = ''
  showModal.value = true
}

const openEditModal = (item: TradeJournal) => {
  isEdit.value = true
  editId.value = item.id
  form.title = item.title
  form.journalDate = item.journalDate
  form.stockCode = item.stockCode || ''
  form.stockName = item.stockName || ''
  form.emotionScore = item.emotionScore || 3
  form.marketSentiment = item.marketSentiment
  form.strategyType = item.strategyType
  form.content = item.content
  form.reflection = item.reflection || ''
  form.tags = item.tags || ''
  showModal.value = true
}

const openViewModal = (item: TradeJournal) => {
  currentJournal.value = item
  showViewModal.value = true
}

const handleModalSubmit = async () => {
  if (!form.title.trim()) {
    message.warning('请输入复盘标题')
    return
  }
  if (!form.content.trim()) {
    message.warning('请输入复盘详情')
    return
  }

  submitLoading.value = true
  try {
    if (isEdit.value && editId.value) {
      await updateJournal(editId.value, form)
      message.success('更新复盘笔记成功')
    } else {
      await createJournal(form)
      message.success('创建复盘笔记成功')
    }
    showModal.value = false
    loadJournals()
    loadCalendar()
  } catch (err: any) {
    message.error(err.message || '保存笔记失败')
  } finally {
    submitLoading.value = false
  }
}

const handleDelete = async (id: number) => {
  try {
    await deleteJournal(id)
    message.success('已删除笔记')
    loadJournals()
    loadCalendar()
  } catch (err: any) {
    message.error(err.message || '删除失败')
  }
}

onMounted(() => {
  loadJournals()
  loadCalendar()
})
</script>

<style scoped>
.journal-page {
  padding: 16px;
  height: 100%;
  overflow-y: auto;
  box-sizing: border-box;
}

.journal-header {
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

.filter-card {
  margin-bottom: 16px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 16px;
}

.filter-group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.filter-label {
  font-size: 13px;
  color: var(--term-fg-muted);
  white-space: nowrap;
}

.filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

/* 顶部日历打卡条 */
.calendar-strip {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 12px;
  margin-bottom: 16px;
  background: var(--term-panel-header);
  border: 1px solid var(--term-border);
  border-radius: 4px;
  overflow-x: auto;
}

.strip-label {
  font-size: 12px;
  color: var(--term-fg-muted);
  white-space: nowrap;
}

.strip-items {
  display: flex;
  align-items: center;
  gap: 8px;
}

.strip-item {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  background: var(--term-bg);
  border: 1px solid var(--term-border);
  border-radius: 4px;
  cursor: pointer;
  font-size: 11px;
  white-space: nowrap;
  transition: all 0.2s;
}

.strip-item:hover {
  border-color: var(--term-accent);
  background: var(--term-hover);
}

.item-date {
  color: var(--term-fg-muted);
}

.item-count {
  color: var(--term-accent);
  font-weight: 600;
}

/* 情绪分值徽标：1~5 色阶，绿色不在其中（绿色在本站专指「跌」） */
.item-emotion {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 24px;
  padding: 0 4px;
  font-family: var(--term-font-mono);
  font-size: 10.5px;
  font-weight: 600;
  line-height: 15px;
  border-radius: 999px;
  border: 1px solid transparent;
}

.item-emotion.lv-1 {
  color: #f43f5e;
  background: rgba(244, 63, 94, 0.12);
  border-color: rgba(244, 63, 94, 0.3);
}

.item-emotion.lv-2 {
  color: #fb923c;
  background: rgba(251, 146, 60, 0.12);
  border-color: rgba(251, 146, 60, 0.3);
}

.item-emotion.lv-3 {
  color: var(--term-accent);
  background: var(--term-active);
  border-color: rgba(56, 189, 248, 0.3);
}

.item-emotion.lv-4 {
  color: #22d3ee;
  background: rgba(34, 211, 238, 0.1);
  border-color: rgba(34, 211, 238, 0.28);
}

.item-emotion.lv-5 {
  color: var(--term-accent-2);
  background: rgba(167, 139, 250, 0.12);
  border-color: rgba(167, 139, 250, 0.3);
}

/* 笔记列表网格 */
.journal-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
  margin-bottom: 16px;
}

.journal-card {
  border: 1px solid var(--term-border);
  display: flex;
  flex-direction: column;
}

.card-top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 8px;
}

.card-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.journal-date {
  font-size: 12px;
  color: var(--term-fg-muted);
  font-variant-numeric: tabular-nums;
  margin-right: 4px;
}

.card-title {
  margin: 6px 0 10px;
  font-size: 16px;
  font-weight: 600;
  color: var(--term-fg);
  cursor: pointer;
  transition: color 0.15s;
}

.card-title:hover {
  color: var(--term-accent);
}

.card-content-preview {
  font-size: 13px;
  color: var(--term-fg-muted);
  line-height: 1.6;
  margin-bottom: 10px;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-reflection {
  padding: 8px 10px;
  background: var(--term-dock-bg);
  border-left: 3px solid var(--term-accent);
  border-radius: 0 4px 4px 0;
  font-size: 12px;
  margin-bottom: 10px;
  color: var(--term-fg);
}

.reflection-label {
  color: var(--term-accent);
  font-weight: 600;
}

.card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.tag-item {
  cursor: pointer;
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: 16px 0;
}

/* 详情抽屉样式 */
.view-detail {
  padding: 8px 0;
}

.view-meta-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--term-border);
}

.detail-label {
  color: var(--term-fg-muted);
  font-size: 13px;
}

.detail-val {
  color: var(--term-fg);
  font-size: 13px;
  font-weight: 600;
}

.detail-section {
  margin-bottom: 16px;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--term-fg);
  margin-bottom: 8px;
}

.section-body {
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  padding: 12px;
  border-radius: 4px;
}

.content-box {
  background: var(--term-dock-bg);
  border: 1px solid var(--term-border);
  color: var(--term-fg);
}

.reflection-box {
  background: #1f6feb15;
  border: 1px solid #1f6feb40;
  color: var(--term-fg);
}

.tag-row {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
