<template>
  <div
    class="stat-card"
    :class="[`tone-${tone}`, { 'is-dim': dim }]"
  >
    <span class="sc-topline" aria-hidden="true" />

    <div class="sc-head">
      <span v-if="icon" class="sc-icon"><AppIcon :name="icon" :size="14" /></span>
      <span class="sc-label">{{ label }}</span>
      <span v-if="hint" class="sc-hint">{{ hint }}</span>
    </div>

    <div class="sc-value-row">
      <span v-if="prefix" class="sc-affix">{{ prefix }}</span>
      <span ref="numRef" class="sc-num" :class="{ 'is-flash': flash }" />
      <span v-if="suffix" class="sc-affix sc-suffix">{{ suffix }}</span>
    </div>

    <svg v-if="sparkPath" class="sc-spark" viewBox="0 0 104 30" preserveAspectRatio="none" aria-hidden="true">
      <path :d="sparkArea" class="sc-spark-area" />
      <path :d="sparkPath" class="sc-spark-line" />
    </svg>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AppIcon from './AppIcon.vue'
import type { IconName } from './icons'
import { countTo, prefersReducedMotion } from '@/theme/motion'

const props = withDefaults(
  defineProps<{
    label: string
    value: number
    prefix?: string
    suffix?: string
    precision?: number
    /** 涨跌语义：up 红 / down 绿 / neutral 灰。不传则按 value 正负自动判定 */
    trend?: 'up' | 'down' | 'neutral'
    /** 数值中性显示（如「持仓数」这类无涨跌含义的指标） */
    dim?: boolean
    icon?: IconName
    hint?: string
    /** 迷你趋势线数据 */
    spark?: number[]
    /** 数值变化时是否闪一下 */
    flash?: boolean
  }>(),
  { precision: 2, flash: true }
)

const numRef = ref<HTMLElement | null>(null)
let tween: gsap.core.Tween | null = null
let lastValue = 0

const tone = computed(() => {
  if (props.dim) return 'neutral'
  if (props.trend) return props.trend
  if (props.value > 0) return 'up'
  if (props.value < 0) return 'down'
  return 'neutral'
})

const format = (v: number): string =>
  v.toLocaleString('zh-CN', {
    minimumFractionDigits: props.precision,
    maximumFractionDigits: props.precision
  })

const sparkPath = computed(() => {
  const data = props.spark
  if (!data || data.length < 2) return ''
  const w = 104
  const h = 30
  const min = Math.min(...data)
  const max = Math.max(...data)
  const span = max - min || 1
  const step = w / (data.length - 1)
  return data
    .map((v, i) => {
      const x = (i * step).toFixed(2)
      const y = (h - 3 - ((v - min) / span) * (h - 6)).toFixed(2)
      return `${i === 0 ? 'M' : 'L'}${x} ${y}`
    })
    .join(' ')
})

const sparkArea = computed(() => (sparkPath.value ? `${sparkPath.value} L104 30 L0 30 Z` : ''))

const render = (from: number) => {
  tween?.kill()
  tween = countTo(numRef.value, props.value, format, 0.95, from)
}

onMounted(() => {
  // 先写入初值，避免动画开始前出现空白
  if (numRef.value) numRef.value.textContent = prefersReducedMotion() ? format(props.value) : format(0)
  render(0)
  lastValue = props.value
})

watch(
  () => props.value,
  (next) => {
    render(lastValue)
    lastValue = next
  }
)

onBeforeUnmount(() => {
  tween?.kill()
})
</script>

<style scoped>
.stat-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 96px;
  padding: 14px 14px 12px;
  background: var(--term-grad-panel), var(--term-panel-bg);
  border: 1px solid var(--term-border);
  border-radius: var(--term-radius);
  overflow: hidden;
  transition:
    border-color var(--term-dur) var(--term-ease),
    box-shadow var(--term-dur) var(--term-ease),
    transform var(--term-dur) var(--term-ease);
}

.sc-topline {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 1px;
  opacity: 0.35;
  background: var(--term-grad-line);
  transition: opacity var(--term-dur);
}

.stat-card:hover {
  border-color: var(--term-border-strong);
  transform: translateY(-2px);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.45);
}

.stat-card:hover .sc-topline {
  opacity: 1;
}

/* 头部 */
.sc-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11.5px;
  letter-spacing: 0.4px;
  color: var(--term-fg-muted);
  white-space: nowrap;
}

.sc-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: var(--term-radius-sm);
  background: rgba(255, 255, 255, 0.04);
  color: var(--term-fg-muted);
  transition: color var(--term-dur), background var(--term-dur);
}

.sc-hint {
  margin-left: auto;
  font-size: 10.5px;
  color: var(--term-fg-dim);
  letter-spacing: 0;
}

/* 数值 */
.sc-value-row {
  display: flex;
  align-items: baseline;
  gap: 2px;
  min-width: 0;
}

.sc-num {
  font-family: var(--term-font-mono);
  font-variant-numeric: tabular-nums;
  font-size: 25px;
  font-weight: 600;
  line-height: 1.15;
  letter-spacing: -0.4px;
  color: var(--term-fg);
  white-space: nowrap;
}

.sc-affix {
  font-family: var(--term-font-mono);
  font-size: 14px;
  color: var(--term-fg-dim);
}

.sc-suffix {
  font-size: 12px;
}

/* 语义色：只有「涨」和「强调」发光，跌只用纯色 */
.tone-up .sc-num {
  color: var(--term-up);
  text-shadow: 0 0 18px var(--term-glow-up);
}

.tone-up .sc-icon {
  color: var(--term-up);
  background: rgba(255, 77, 109, 0.1);
}

.tone-down .sc-num {
  color: var(--term-down);
}

.tone-down .sc-icon {
  color: var(--term-down);
  background: rgba(0, 229, 160, 0.08);
}

.tone-neutral .sc-num {
  color: var(--term-fg);
}

.tone-neutral .sc-icon {
  color: var(--term-accent);
  background: var(--term-active);
}

/* 迷你趋势线 */
.sc-spark {
  position: absolute;
  right: 12px;
  bottom: 10px;
  width: 104px;
  height: 30px;
  opacity: 0.85;
  pointer-events: none;
}

.sc-spark-line {
  fill: none;
  stroke: var(--term-accent);
  stroke-width: 1.4;
  stroke-linecap: round;
  stroke-linejoin: round;
  filter: drop-shadow(0 0 4px rgba(56, 189, 248, 0.5));
}

.sc-spark-area {
  fill: rgba(56, 189, 248, 0.1);
  stroke: none;
}

.tone-up .sc-spark-line {
  stroke: var(--term-up);
  filter: drop-shadow(0 0 4px rgba(255, 77, 109, 0.45));
}

.tone-up .sc-spark-area {
  fill: rgba(255, 77, 109, 0.1);
}

.tone-down .sc-spark-line {
  stroke: var(--term-down);
  filter: none;
}

.tone-down .sc-spark-area {
  fill: rgba(0, 229, 160, 0.08);
}
</style>
