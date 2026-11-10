<template>
  <div
    class="spark-strip"
    :class="{ 'is-empty': !points.length }"
    :title="title"
    @click="onClick"
    ref="stripRef"
  >
    <svg
      v-if="points.length > 1"
      :viewBox="`0 0 ${W} ${H}`"
      preserveAspectRatio="none"
      class="spark-svg"
    >
      <!-- 当前视窗高亮，先画在底层 -->
      <rect
        v-if="winW > 0"
        :x="winX"
        y="0"
        :width="winW"
        :height="H"
        class="spark-window"
      />
      <path :d="linePath" class="spark-line" />
      <circle
        v-for="m in markerDots"
        :key="m.key"
        :cx="m.x"
        :cy="m.y"
        r="3"
        :fill="m.color"
        class="spark-dot"
      />
    </svg>
    <span v-else class="spark-placeholder">暂无全历史数据</span>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

/**
 * K 线全貌缩略条。
 *
 * 职责：在主图只加载最近 620 根的前提下，保留「全历史长什么样、现在看的是哪一段」。
 * 数据由服务端 LTTB 降采样到约 240 点，这里只做坐标映射，不做任何聚合计算。
 */
const props = defineProps<{
  /** 降采样后的收盘序列，按时间升序 */
  points: { d: number; c: number }[]
  /** 当前视窗在全历史中的起止比例，0~1 */
  windowFrom?: number
  windowTo?: number
  /** 买卖点：d 为 yyyyMMdd 或 yyyy-MM-dd，price 与 points 的 c 同量纲 */
  markers?: { d: string; direction: 'buy' | 'sell'; price: number }[]
}>()

const emit = defineEmits<{
  /** 点击位置对应的时间（yyyyMMdd），供主图跳转 */
  (e: 'seek', date: number): void
}>()

const W = 1000
const H = 40

const stripRef = ref<HTMLElement | null>(null)

const title = computed(() =>
  props.points.length
    ? `全历史 ${props.points.length} 点，点击可跳转到对应时间段`
    : '暂无全历史数据'
)

/** 价格区间。留 4% 上下边距，避免折线贴边被裁。 */
const bounds = computed(() => {
  if (!props.points.length) return null
  let min = Infinity
  let max = -Infinity
  for (const p of props.points) {
    if (p.c < min) min = p.c
    if (p.c > max) max = p.c
  }
  const pad = (max - min) * 0.04 || 1
  return { min: min - pad, max: max + pad, span: (max - min) + pad * 2 || 1 }
})

const linePath = computed(() => {
  const b = bounds.value
  const n = props.points.length
  if (!b || n < 2) return ''
  const parts: string[] = []
  for (let i = 0; i < n; i++) {
    const x = (i / (n - 1)) * W
    const y = H - ((props.points[i].c - b.min) / b.span) * H
    parts.push(`${i === 0 ? 'M' : 'L'}${x.toFixed(1)},${y.toFixed(1)}`)
  }
  return parts.join(' ')
})

const winX = computed(() => Math.max(0, Math.min(1, props.windowFrom ?? 0)) * W)
const winW = computed(() => {
  const from = Math.max(0, Math.min(1, props.windowFrom ?? 0))
  const to = Math.max(0, Math.min(1, props.windowTo ?? 1))
  return Math.max(0, (to - from) * W)
})

/**
 * 买卖点映射到缩略条坐标。
 *
 * 点的日期未必落在降采样后的 points 里（240 个点当然覆盖不了每一根 K 线），
 * 所以用「首末日期线性插值」定位 x，而不是去 points 里找同一个日期。
 */
const markerDots = computed(() => {
  const b = bounds.value
  const pts = props.points
  if (!b || !pts.length || !props.markers?.length) return []

  const first = pts[0].d
  const last = pts[pts.length - 1].d
  const span = last - first || 1

  const out: { key: string; x: number; y: number; color: string }[] = []
  props.markers.forEach((m, i) => {
    const dv = Number(String(m.d).replace(/-/g, ''))
    if (!Number.isFinite(dv) || dv < first || dv > last) return
    const x = ((dv - first) / span) * W
    const y = H - ((m.price - b.min) / b.span) * H
    out.push({
      key: `${m.d}-${m.direction}-${i}`,
      x: Number(x.toFixed(1)),
      y: Number(Math.max(2, Math.min(H - 2, y)).toFixed(1)),
      color: m.direction === 'buy' ? '#ef5350' : '#26a69a'
    })
  })
  return out
})

const onClick = (e: MouseEvent) => {
  const el = stripRef.value
  if (!el || !props.points.length) return
  const rect = el.getBoundingClientRect()
  const ratio = Math.max(0, Math.min(1, (e.clientX - rect.left) / rect.width))
  // 比例 → 降采样点索引 → 真实日期
  const idx = Math.round(ratio * (props.points.length - 1))
  emit('seek', props.points[idx].d)
}
</script>

<style scoped>
.spark-strip {
  position: relative;
  width: 100%;
  height: 42px;
  flex: 0 0 42px;
  cursor: pointer;
  border-radius: 4px;
  overflow: hidden;
  background: var(--term-panel-bg, rgba(255, 255, 255, 0.02));
  border: 1px solid var(--term-border, rgba(255, 255, 255, 0.08));
}

.spark-strip:hover {
  border-color: var(--term-border-strong, rgba(255, 255, 255, 0.18));
}

.spark-svg {
  display: block;
  width: 100%;
  height: 100%;
}

.spark-line {
  fill: none;
  stroke: var(--term-accent, #38bdf8);
  stroke-width: 1.2;
  vector-effect: non-scaling-stroke;
  opacity: 0.85;
}

.spark-window {
  fill: var(--term-accent, #38bdf8);
  opacity: 0.12;
}

.spark-dot {
  /* viewBox 被拉伸，圆点要锁定像素尺寸 */
  vector-effect: non-scaling-stroke;
  stroke: var(--term-panel-bg, #0a0f1a);
  stroke-width: 0.8;
}

.spark-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  font-size: 11px;
  color: var(--term-fg-dim, #6b7280);
}
</style>
