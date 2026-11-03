<template>
  <div class="equity-curve">
    <div class="curve-caption"><span><i class="account"></i> 练习权益</span><span><i class="benchmark"></i> 买入持有基准</span><span class="curve-end">截至 {{ points.at(-1)?.date ?? '—' }} · 收盘计值</span></div>
    <svg viewBox="0 0 760 210" role="img" :aria-label="`练习权益与基准曲线，共 ${points.length} 个收盘数据点`" preserveAspectRatio="xMidYMid meet">
      <g v-for="(value, index) in ticks" :key="index">
        <line x1="76" :y1="y(value)" x2="738" :y2="y(value)" stroke="#30363d" stroke-dasharray="3 5" />
        <text x="66" :y="y(value) + 4" text-anchor="end" fill="#8b949e" font-size="11">{{ money(value) }}</text>
      </g>
      <path :d="line('benchmarkEquity')" fill="none" stroke="#d29922" stroke-width="2" stroke-dasharray="5 4" />
      <path :d="line('equity')" fill="none" stroke="#58a6ff" stroke-width="2" />
      <circle v-if="points.length === 1" cx="76" :cy="y(points[0]!.equity)" r="3" fill="#58a6ff" />
      <text x="76" y="201" fill="#8b949e" font-size="11">{{ points[0]?.date }}</text>
      <text x="738" y="201" text-anchor="end" fill="#8b949e" font-size="11">{{ points.length > 1 ? points.at(-1)?.date : '' }}</text>
    </svg>
    <p v-if="points.length <= 1">推进到下一交易日后开始绘制权益变化。</p>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { PracticeEquityPoint } from '@/api/practice'
import { money } from '../practiceData'
const props = defineProps<{ points: PracticeEquityPoint[] }>()
const extent = computed(() => {
  const values = props.points.flatMap(point => [point.equity, point.benchmarkEquity])
  const min = values.length ? Math.min(...values) : 0
  const max = values.length ? Math.max(...values) : 1
  const pad = Math.max((max - min) * .12, max * .005, 1)
  return { min: min - pad, max: max + pad }
})
const ticks = computed(() => [extent.value.min, (extent.value.min + extent.value.max) / 2, extent.value.max])
const y = (value: number) => 174 - (value - extent.value.min) / (extent.value.max - extent.value.min) * 150
const line = (field: 'equity' | 'benchmarkEquity') => props.points.map((point, index) =>
  `${index ? 'L' : 'M'} ${76 + index / Math.max(1, props.points.length - 1) * 662} ${y(point[field])}`).join(' ')
</script>
<style scoped>
.equity-curve { padding: 14px 16px 6px; }
.curve-caption { display: flex; flex-wrap: wrap; gap: 16px; align-items: center; font-size: 11px; color: var(--term-fg-muted); }
.curve-caption i { display: inline-block; width: 14px; height: 2px; vertical-align: middle; margin-right: 6px; }
i.account { background: #58a6ff; } i.benchmark { background: #d29922; }
.curve-end { margin-left: auto; }
svg { width: 100%; max-height: 250px; display: block; margin-top: 8px; font-variant-numeric: tabular-nums; }
p { color: var(--term-fg-muted); font-size: 12px; margin: 0 0 12px 60px; }
</style>
