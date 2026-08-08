<template>
  <div class="info-panel" :style="{ background: panelBg, color: theme.layout.textColor }">
    <div class="info-header">{{ stockName }} ({{ stockCode }})</div>
    <div class="info-row">
      <span>O: {{ data.open.toFixed(2) }}</span>
      <span>H: {{ data.high.toFixed(2) }}</span>
      <span>L: {{ data.low.toFixed(2) }}</span>
      <span>C: {{ data.close.toFixed(2) }}</span>
    </div>
    <div class="info-row">
      <span :style="{ color: data.change >= 0 ? theme.candle.upColor : theme.candle.downColor }">
        Chg: {{ data.change >= 0 ? '+' : '' }}{{ data.change.toFixed(2) }}%
      </span>
      <span>Vol: {{ formatVolume(data.volume) }}</span>
    </div>
    <template v-if="data.ma5 !== null">
      <div class="info-row">
        <span v-if="data.ma5 !== null" :style="{ color: theme.indicators.ma5 }">MA5: {{ data.ma5?.toFixed(2) }}</span>
        <span v-if="data.ma10 !== null" :style="{ color: theme.indicators.ma10 }">MA10: {{ data.ma10?.toFixed(2) }}</span>
        <span v-if="data.ma20 !== null" :style="{ color: theme.indicators.ma20 }">MA20: {{ data.ma20?.toFixed(2) }}</span>
        <span v-if="data.ma60 !== null" :style="{ color: theme.indicators.ma60 }">MA60: {{ data.ma60?.toFixed(2) }}</span>
      </div>
    </template>
    <div class="info-row" v-if="data.macdDif !== null">
      <span :style="{ color: theme.indicators.dif }">DIF: {{ data.macdDif?.toFixed(3) }}</span>
      <span :style="{ color: theme.indicators.dea }">DEA: {{ data.macdDea?.toFixed(3) }}</span>
      <span>MACD: {{ data.macdHist?.toFixed(3) }}</span>
    </div>
    <div class="info-row" v-if="data.kdjK !== null">
      <span :style="{ color: theme.indicators.k }">K: {{ data.kdjK }}</span>
      <span :style="{ color: theme.indicators.d }">D: {{ data.kdjD }}</span>
      <span :style="{ color: theme.indicators.j }">J: {{ data.kdjJ }}</span>
    </div>
    <div class="info-row" v-if="data.rsi !== null">
      <span :style="{ color: theme.indicators.rsi }">RSI: {{ data.rsi?.toFixed(2) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { ChartTheme } from '@/types/chart'
import type { IndicatorConfig } from '@/composables/useIndicators'
import type { InfoPanelData } from '@/composables/useInfoPanel'

const props = defineProps<{
  data: InfoPanelData
  indicators: IndicatorConfig
  theme: ChartTheme
  stockName?: string
  stockCode?: string
}>()

const panelBg = computed(() =>
  props.theme.name === 'dark' ? 'rgba(19, 23, 34, 0.85)' : 'rgba(255, 255, 255, 0.85)'
)

const formatVolume = (v: number) => {
  if (v >= 1e8) return (v / 1e8).toFixed(1) + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(0) + '万'
  return v.toString()
}
</script>

<style scoped>
.info-panel {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 11px;
  font-family: monospace;
  line-height: 1.6;
  pointer-events: none;
  z-index: 10;
  backdrop-filter: blur(4px);
}
.info-header { font-weight: 600; margin-bottom: 4px; }
.info-row { display: flex; gap: 12px; white-space: nowrap; }
</style>
