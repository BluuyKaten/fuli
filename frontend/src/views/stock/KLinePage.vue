<template>
  <div ref="workspaceRef" class="kline-workspace">
    <!-- 标的条：当前股票与现价 -->
    <div class="ws-statusbar">
      <div class="sb-left">
        <span class="sb-symbol">
          <span class="sb-name">{{ activeStock?.stockName || '未选择标的' }}</span>
          <span class="sb-code mono">{{ activeStock?.stockCode }}</span>
        </span>
        <span class="sb-divider" />
        <span class="sb-hint">拖拽分隔条可调整面板比例 · 双击标题栏可最小化</span>
      </div>
      <div class="sb-right">
        <span class="sb-label">现价</span>
        <span ref="priceRef" class="sb-price mono">{{ currentPrice || '--' }}</span>
      </div>
    </div>

    <!-- 上排 -->
    <div class="ws-row ws-top" :style="{ flex: topRow[0].minimized && topRow[1].minimized ? 0 : vRatio.value }">
      <!-- 左上：自选股 -->
      <div
        class="ws-panel"
        :class="{ minimized: topRow[0].minimized }"
        :style="{ flex: topRow[0].minimized ? 0 : topRow[0].ratio, minWidth: topRow[0].minimized ? 0 : '180px' }"
      >
        <WatchlistPanel :active-code="activeStock?.stockCode" @select-stock="onSelectStock" />
      </div>

      <!-- 垂直分隔条 1 -->
      <div
        v-if="!topRow[0].minimized && !topRow[1].minimized"
        class="v-sep"
        @mousedown="startDragH($event, 0, 1, 'top')"
        @touchstart.prevent="startDragH($event, 0, 1, 'top')"
      />

      <!-- 右上：K线图 -->
      <div
        class="ws-panel"
        :class="{ minimized: topRow[1].minimized }"
        :style="{ flex: topRow[1].minimized ? 0 : topRow[1].ratio, minWidth: topRow[1].minimized ? 0 : '300px' }"
      >
        <LightweightChart
          :key="activeStock?.stockCode"
          ref="chartRef"
          :stock-code="activeStock?.stockCode || ''"
          @price-change="onPriceChange"
        />
      </div>
    </div>

    <!-- 水平分隔条 -->
    <div class="h-sep" @mousedown="startDragV" @touchstart.prevent="startDragV" />

    <!-- 下排 -->
    <div class="ws-row ws-bottom" :style="{ flex: bottomRow[0].minimized && bottomRow[1].minimized ? 0 : 1 - vRatio.value }">
      <!-- 左下：持仓 -->
      <div
        class="ws-panel"
        :class="{ minimized: bottomRow[0].minimized }"
        :style="{ flex: bottomRow[0].minimized ? 0 : bottomRow[0].ratio, minWidth: bottomRow[0].minimized ? 0 : '200px' }"
      >
        <PositionPanel ref="positionRef" :active-code="activeStock?.stockCode" @select-stock="onSelectStock" />
      </div>

      <!-- 垂直分隔条 2 -->
      <div
        v-if="!bottomRow[0].minimized && !bottomRow[1].minimized"
        class="v-sep"
        @mousedown="startDragH($event, 0, 1, 'bottom')"
        @touchstart.prevent="startDragH($event, 0, 1, 'bottom')"
      />

      <!-- 右下：交易 -->
      <div
        class="ws-panel"
        :class="{ minimized: bottomRow[1].minimized }"
        :style="{ flex: bottomRow[1].minimized ? 0 : bottomRow[1].ratio, minWidth: bottomRow[1].minimized ? 0 : '200px' }"
      >
        <TradePanel :stock="activeStock" :price="currentPrice" @trade-success="onTradeSuccess" />
      </div>
    </div>

    <!-- 最小化面板还原 dock -->
    <Transition name="dock-fade">
      <div v-if="minimizedList.length" class="minimized-dock">
        <span
          v-for="item in minimizedList"
          :key="item.id"
          class="dock-chip"
          @click="restorePanel(item.id)"
        >
          <AppIcon :name="item.icon" :size="12" />
          {{ item.title }}
        </span>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useFlexPanels } from '@/composables/useFlexPanels'
import LightweightChart from './LightweightChart.vue'
import WatchlistPanel from './WatchlistPanel.vue'
import PositionPanel from './PositionPanel.vue'
import TradePanel from './TradePanel.vue'
import AppIcon from '@/components/ui/AppIcon.vue'
import type { IconName } from '@/components/ui/icons'
import type { StockInfo } from '@/types'
import { flashTick, revealIn } from '@/theme/motion'

const { topRow, bottomRow, vRatio, resizeHorizontal, resizeVertical, restore } = useFlexPanels()

const workspaceRef = ref<HTMLElement | null>(null)
const chartRef = ref<InstanceType<typeof LightweightChart> | null>(null)
const positionRef = ref<InstanceType<typeof PositionPanel> | null>(null)
const priceRef = ref<HTMLElement | null>(null)

// 默认展示股票（fallback）：用户未自选时展示此股票，便于新用户快速预览
const DEFAULT_STOCK = { stockCode: '600519.SH', stockName: '贵州茅台' }

// 当前选中股票（优先恢复上次查看的，否则默认贵州茅台）
const _restored = (() => {
  try {
    const stored = localStorage.getItem('lastViewedStock')
    if (stored) {
      const stock = JSON.parse(stored)
      if (stock?.stockCode) return stock
    }
  } catch {
    /* 忽略 */
  }
  return null
})()
const activeStock = ref<StockInfo | null>(_restored ?? { ...DEFAULT_STOCK })
const hasRestoredStock = !!_restored
const currentPrice = ref('')

/** 现价跳动方向：只用于闪烁提示，不参与业务判断 */
let lastPrice = 0
const onPriceChange = (value: string) => {
  currentPrice.value = value
  const next = Number(value)
  if (!Number.isFinite(next) || next <= 0) return
  if (lastPrice > 0 && next !== lastPrice) {
    flashTick(priceRef.value, next > lastPrice ? 'up' : 'down')
  }
  lastPrice = next
}

// 拖拽状态
const dragging = ref<null | {
  type: 'h-top' | 'h-bottom' | 'v'
  startX: number
  startY: number
}>(null)

const startDragH = (e: MouseEvent | TouchEvent, _leftIdx: number, _rightIdx: number, row: 'top' | 'bottom') => {
  const pageX = 'touches' in e ? e.touches[0].pageX : e.pageX
  dragging.value = { type: row === 'top' ? 'h-top' : 'h-bottom', startX: pageX, startY: 0 }
  bindDragEvents()
}

const startDragV = (e: MouseEvent | TouchEvent) => {
  const pageY = 'touches' in e ? e.touches[0].pageY : e.pageY
  dragging.value = { type: 'v', startX: 0, startY: pageY }
  bindDragEvents()
}

const bindDragEvents = () => {
  const move = (e: MouseEvent | TouchEvent) => {
    if (!dragging.value || !workspaceRef.value) return
    const rect = workspaceRef.value.getBoundingClientRect()
    if (dragging.value.type === 'v') {
      const pageY = 'touches' in e ? (e as TouchEvent).touches[0].pageY : (e as MouseEvent).pageY
      const delta = pageY - dragging.value.startY
      dragging.value.startY = pageY
      resizeVertical(delta, rect.height)
    } else {
      const pageX = 'touches' in e ? (e as TouchEvent).touches[0].pageX : (e as MouseEvent).pageX
      const delta = pageX - dragging.value.startX
      dragging.value.startX = pageX
      const row = dragging.value.type === 'h-top' ? topRow : bottomRow
      resizeHorizontal(row, 0, 1, delta, rect.width)
    }
  }
  const up = () => {
    dragging.value = null
    window.removeEventListener('mousemove', move)
    window.removeEventListener('mouseup', up)
    window.removeEventListener('touchmove', move)
    window.removeEventListener('touchend', up)
  }
  window.addEventListener('mousemove', move)
  window.addEventListener('mouseup', up)
  window.addEventListener('touchmove', move, { passive: true })
  window.addEventListener('touchend', up)
}

const onSelectStock = (stock: StockInfo) => {
  // 后端原值（完整符号），前端不再剥离后缀——解析只由服务端 StockCodeUtil 负责
  activeStock.value = { ...stock }
  localStorage.setItem('lastViewedStock', JSON.stringify(activeStock.value))
  currentPrice.value = ''
  lastPrice = 0
}

const onTradeSuccess = () => {
  positionRef.value?.refresh()
}

// 默认加载第一只自选股
const loadDefaultStock = async () => {
  if (hasRestoredStock) return
  try {
    const { getWatchlist } = await import('@/api/watchlist')
    const res = await getWatchlist()
    if (res.code === 200 && res.data && res.data.length > 0) {
      const first = res.data[0]
      activeStock.value = {
        stockCode: first.stockCode,
        stockName: first.stockName || first.stockCode
      }
    } else {
      activeStock.value = { ...DEFAULT_STOCK }
    }
  } catch {
    activeStock.value = { ...DEFAULT_STOCK }
  }
}

// 最小化的面板列表
const minimizedList = computed(() => {
  const list: { id: string; title: string; icon: IconName }[] = []
  if (topRow[0].minimized) list.push({ id: 'watchlist', title: '自选股', icon: 'clipboard' })
  if (topRow[1].minimized) list.push({ id: 'chart', title: 'K线图', icon: 'candle' })
  if (bottomRow[0].minimized) list.push({ id: 'position', title: '持仓', icon: 'wallet' })
  if (bottomRow[1].minimized) list.push({ id: 'trade', title: '交易', icon: 'activity' })
  return list
})

const restorePanel = (id: string) => {
  if (id === 'watchlist') restore(topRow, 0, 0.25)
  else if (id === 'chart') restore(topRow, 1, 0.75)
  else if (id === 'position') restore(bottomRow, 0, 0.5)
  else if (id === 'trade') restore(bottomRow, 1, 0.5)
}

// 面板淡入：只动 opacity，避免影响 canvas 尺寸
onMounted(async () => {
  loadDefaultStock()
  await nextTick()
  revealIn(workspaceRef.value, '.ws-panel', { y: 0, stagger: 0.06 })
})

watch(
  () => activeStock.value?.stockCode,
  () => {
    currentPrice.value = ''
    lastPrice = 0
  }
)
</script>

<style scoped>
.kline-workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-height: 100%;
  background: transparent;
  gap: 0;
  user-select: none;
  overflow: hidden;
  box-sizing: border-box;
  position: relative;
  padding: 0 8px 8px;
}

/* ============ 标的条 ============ */
.ws-statusbar {
  flex: 0 0 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 2px;
  overflow: hidden;
}

.sb-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.sb-symbol {
  display: inline-flex;
  align-items: baseline;
  gap: 7px;
  min-width: 0;
}

.sb-name {
  font-size: 14px;
  font-weight: 650;
  letter-spacing: 0.4px;
  color: var(--term-fg);
  white-space: nowrap;
}

.sb-code {
  font-size: 11px;
  color: var(--term-fg-dim);
  letter-spacing: 0.6px;
}

.sb-divider {
  width: 1px;
  height: 12px;
  background: var(--term-border);
}

.sb-hint {
  font-size: 11px;
  color: var(--term-fg-dim);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sb-right {
  display: flex;
  align-items: baseline;
  gap: 6px;
  flex: 0 0 auto;
}

.sb-label {
  font-size: 10.5px;
  letter-spacing: 0.6px;
  color: var(--term-fg-dim);
}

.sb-price {
  display: inline-block;
  font-size: 16px;
  font-weight: 650;
  color: var(--term-fg);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.3px;
}

/* ============ 行与面板 ============ */
.ws-row {
  display: flex;
  min-height: 0;
  transition: flex 0.22s var(--term-ease);
  gap: 0;
}

.ws-top {
  flex: 0.6;
}

.ws-bottom {
  flex: 0.4;
}

.ws-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  background: var(--term-panel-bg);
  border: 1px solid var(--term-border);
  border-radius: var(--term-radius-sm);
  transition: border-color var(--term-dur) var(--term-ease), box-shadow var(--term-dur) var(--term-ease);
}

/* 每个面板顶部一道极细渐变光，和全站面板语言一致 */
.ws-panel::after {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 1px;
  background: var(--term-grad-line);
  opacity: 0.45;
  pointer-events: none;
  z-index: 3;
  transition: opacity var(--term-dur);
}

.ws-panel:hover {
  border-color: var(--term-border-strong);
}

.ws-panel:hover::after {
  opacity: 0.9;
}

.ws-panel.minimized {
  flex: 0 !important;
  min-width: 0 !important;
  overflow: hidden;
}

/* ============ 分隔条 ============ */
.h-sep {
  height: 7px;
  flex: 0 0 7px;
  background: transparent;
  cursor: row-resize;
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
}

.h-sep::after {
  content: '';
  width: 46px;
  height: 2px;
  border-radius: 2px;
  background: var(--term-border-strong);
  transition: all var(--term-dur-fast) var(--term-ease);
}

.h-sep:hover::after,
.h-sep:active::after {
  width: 100%;
  height: 2px;
  background: var(--term-grad-line);
  box-shadow: 0 0 10px var(--term-glow);
}

.v-sep {
  width: 7px;
  flex: 0 0 7px;
  background: transparent;
  cursor: col-resize;
  position: relative;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: center;
}

.v-sep::after {
  content: '';
  width: 2px;
  height: 46px;
  border-radius: 2px;
  background: var(--term-border-strong);
  transition: all var(--term-dur-fast) var(--term-ease);
}

.v-sep:hover::after,
.v-sep:active::after {
  height: 100%;
  background: var(--term-grad-line);
  box-shadow: 0 0 10px var(--term-glow);
}

/* ============ 最小化 dock ============ */
.minimized-dock {
  position: absolute;
  bottom: 14px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 6px;
  z-index: 10;
  padding: 4px 6px;
  background: rgba(10, 15, 26, 0.9);
  border: 1px solid var(--term-border-strong);
  border-radius: 999px;
  box-shadow: var(--term-shadow);
  backdrop-filter: blur(8px);
}

.dock-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 11px;
  background: rgba(255, 255, 255, 0.02);
  border: 1px dashed var(--term-border-strong);
  border-radius: 999px;
  font-size: 11px;
  color: var(--term-fg-muted);
  cursor: pointer;
  transition: all var(--term-dur-fast) var(--term-ease);
}

.dock-chip:hover {
  background: var(--term-active);
  color: var(--term-accent);
  border-style: solid;
  border-color: rgba(56, 189, 248, 0.5);
  box-shadow: 0 0 16px rgba(56, 189, 248, 0.16);
}

.dock-fade-enter-active,
.dock-fade-leave-active {
  transition: opacity var(--term-dur), transform var(--term-dur) var(--term-ease);
}

.dock-fade-enter-from,
.dock-fade-leave-to {
  opacity: 0;
  transform: translate(-50%, 8px);
}

@media (max-width: 900px) {
  .sb-hint {
    display: none;
  }
}
</style>
