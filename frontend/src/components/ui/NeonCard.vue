<template>
  <section
    class="neon-card"
    :class="[`glow-${glow}`, { 'is-dense': dense, 'is-hoverable': hoverable }]"
  >
    <!-- 顶部渐变光条 -->
    <span class="nc-topline" aria-hidden="true" />

    <header v-if="hasHeader" class="nc-head">
      <slot name="header">
        <div class="nc-title-wrap">
          <span v-if="icon" class="nc-icon"><AppIcon :name="icon" :size="15" /></span>
          <h3 class="nc-title">{{ title }}</h3>
          <span v-if="subtitle" class="nc-sub">{{ subtitle }}</span>
        </div>
      </slot>
      <div v-if="$slots.extra" class="nc-extra">
        <slot name="extra" />
      </div>
    </header>

    <div class="nc-body" :style="bodyStyle">
      <slot />
    </div>

    <footer v-if="$slots.footer" class="nc-foot">
      <slot name="footer" />
    </footer>
  </section>
</template>

<script setup lang="ts">
import { computed, useSlots } from 'vue'
import AppIcon from './AppIcon.vue'
import type { IconName } from './icons'

const props = withDefaults(
  defineProps<{
    title?: string
    subtitle?: string
    icon?: IconName
    /** 光晕倾向：accent 青蓝 / violet 紫罗兰 / none 无 */
    glow?: 'accent' | 'violet' | 'none'
    /** 紧凑模式：缩小内边距 */
    dense?: boolean
    /** 是否启用 hover 抬升 */
    hoverable?: boolean
    bodyStyle?: string | Record<string, string>
  }>(),
  { glow: 'accent', dense: false, hoverable: true }
)

const slots = useSlots()
const hasHeader = computed(() => !!(props.title || slots.header || slots.extra))
</script>

<style scoped>
.neon-card {
  position: relative;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background:
    var(--term-grad-panel),
    var(--term-panel-bg);
  border: 1px solid var(--term-border);
  border-radius: var(--term-radius);
  overflow: hidden;
  transition:
    border-color var(--term-dur) var(--term-ease),
    box-shadow var(--term-dur) var(--term-ease),
    transform var(--term-dur) var(--term-ease);
}

/* 顶部 1px 渐变光条：所有面板统一的识别符号 */
.nc-topline {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 1px;
  background: var(--term-grad-line);
  opacity: 0.5;
  pointer-events: none;
  transition: opacity var(--term-dur);
}

.is-hoverable:hover {
  border-color: var(--term-border-strong);
}

.is-hoverable:hover .nc-topline {
  opacity: 1;
}

.glow-accent.is-hoverable:hover {
  box-shadow: 0 0 0 1px rgba(56, 189, 248, 0.16), 0 12px 32px rgba(0, 0, 0, 0.45),
    0 0 26px rgba(56, 189, 248, 0.08);
  transform: translateY(-2px);
}

.glow-violet.is-hoverable:hover {
  box-shadow: 0 0 0 1px rgba(167, 139, 250, 0.16), 0 12px 32px rgba(0, 0, 0, 0.45),
    0 0 26px rgba(167, 139, 250, 0.08);
  transform: translateY(-2px);
}

/* 头部 */
.nc-head {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 42px;
  padding: 0 14px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.025), rgba(255, 255, 255, 0));
  border-bottom: 1px solid var(--term-border);
  flex: 0 0 auto;
}

.nc-title-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.nc-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: var(--term-radius-sm);
  background: var(--term-active);
  color: var(--term-accent);
}

.nc-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.3px;
  color: var(--term-fg);
  white-space: nowrap;
}

.nc-sub {
  font-size: 11px;
  color: var(--term-fg-dim);
  padding-left: 8px;
  border-left: 1px solid var(--term-border);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.nc-extra {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 0 auto;
}

/* 主体 */
.nc-body {
  flex: 1;
  min-height: 0;
  padding: 14px;
}

.is-dense .nc-body {
  padding: 8px 10px;
}

.nc-foot {
  flex: 0 0 auto;
  padding: 8px 14px;
  border-top: 1px solid var(--term-border);
  background: rgba(0, 0, 0, 0.18);
}
</style>
