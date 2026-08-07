<template>
  <div class="kline-workspace" ref="workspaceRef">
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
          ref="chartRef"
          :stock-code="activeStock?.stockCode || ''"
          @price-change="currentPrice = $event"
        />
      </div>
    </div>

    <!-- 水平分隔条 -->
    <div
      class="h-sep"
      @mousedown="startDragV"
      @touchstart.prevent="startDragV"
    />

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
    <div class="minimized-dock">
      <span
        v-for="item in minimizedList"
        :key="item.id"
        class="dock-chip"
        @click="restorePanel(item.id)"
      >
        {{ item.icon }} {{ item.title }}
      </span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useFlexPanels } from '@/composables/useFlexPanels'
import LightweightChart from './LightweightChart.vue'
import WatchlistPanel from './WatchlistPanel.vue'
import PositionPanel from './PositionPanel.vue'
import TradePanel from './TradePanel.vue'
import type { StockInfo } from '@/types'
import { toPureCode } from '@/utils/stockCode'

const {
  topRow,
  bottomRow,
  vRatio,
  resizeHorizontal,
  resizeVertical,
  restore
} = useFlexPanels()

const workspaceRef = ref<HTMLElement | null>(null)
const chartRef = ref<InstanceType<typeof LightweightChart> | null>(null)
const positionRef = ref<InstanceType<typeof PositionPanel> | null>(null)

const activeStock = ref<StockInfo | null>({ stockCode: '600519', stockName: '贵州茅台' })
const currentPrice = ref('')

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
  activeStock.value = { ...stock, stockCode: toPureCode(stock.stockCode) }
  // LightweightChart 通过 watch 自动响应 stockCode 变化
}

const onTradeSuccess = () => {
  positionRef.value?.refresh()
}

// 默认加载第一只自选股
const loadDefaultStock = async () => {
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
      activeStock.value = { stockCode: '600519', stockName: '贵州茅台' }
    }
  } catch {
    activeStock.value = { stockCode: '600519', stockName: '贵州茅台' }
  }
}

// 最小化的面板列表
const minimizedList = computed(() => {
  const list: { id: string; title: string; icon: string }[] = []
  if (topRow[0].minimized) list.push({ id: 'watchlist', title: '自选股', icon: '📋' })
  if (topRow[1].minimized) list.push({ id: 'chart', title: 'K线图', icon: '📈' })
  if (bottomRow[0].minimized) list.push({ id: 'position', title: '持仓', icon: '💼' })
  if (bottomRow[1].minimized) list.push({ id: 'trade', title: '交易', icon: '💱' })
  return list
})

const restorePanel = (id: string) => {
  if (id === 'watchlist') restore(topRow, 0, 0.25)
  else if (id === 'chart') restore(topRow, 1, 0.75)
  else if (id === 'position') restore(bottomRow, 0, 0.5)
  else if (id === 'trade') restore(bottomRow, 1, 0.5)
}

onMounted(() => {
  loadDefaultStock()
})
</script>

<style scoped>
.kline-workspace {
  display: flex;
  flex-direction: column;
  height: 100%;
  max-height: 100%;
  background: var(--term-bg);
  gap: 0;
  user-select: none;
  overflow: hidden;
  box-sizing: border-box;
}

.ws-row {
  display: flex;
  min-height: 0;
  transition: flex 0.15s;
}

.ws-top { flex: 0.6; }
.ws-bottom { flex: 0.4; }

.ws-panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  height: 100%;
  overflow: hidden;
}

.ws-row {
  display: flex;
  min-height: 0;
  transition: flex 0.15s;
}

.ws-panel.minimized {
  flex: 0 !important;
  min-width: 0 !important;
  overflow: hidden;
}

/* 水平分隔条（上下排之间） */
.h-sep {
  height: 5px;
  flex: 0 0 5px;
  background: var(--term-border);
  cursor: row-resize;
  position: relative;
  z-index: 2;
  transition: background 0.15s;
}

.h-sep:hover,
.h-sep:active {
  background: var(--term-accent);
}

.h-sep::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: 50%;
  height: 1px;
  background: var(--term-fg-muted);
  opacity: 0.3;
}

/* 垂直分隔条（同排左右之间） */
.v-sep {
  width: 5px;
  flex: 0 0 5px;
  background: var(--term-border);
  cursor: col-resize;
  position: relative;
  z-index: 2;
  transition: background 0.15s;
}

.v-sep:hover,
.v-sep:active {
  background: var(--term-accent);
}

.v-sep::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 50%;
  width: 1px;
  background: var(--term-fg-muted);
  opacity: 0.3;
}

/* 最小化面板还原栏 */
.minimized-dock {
  position: absolute;
  bottom: 8px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  gap: 6px;
  z-index: 10;
  background: var(--term-panel-bg);
  border: 1px solid var(--term-border);
  border-radius: 6px;
  padding: 4px 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.3);
}

.dock-chip {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 3px 10px;
  background: var(--term-bg);
  border: 1px dashed var(--term-border);
  border-radius: 4px;
  font-size: 11px;
  color: var(--term-fg-muted);
  cursor: pointer;
  transition: all 0.15s;
}

.dock-chip:hover {
  background: var(--term-hover);
  color: var(--term-fg);
  border-style: solid;
  border-color: var(--term-accent);
}
</style>
