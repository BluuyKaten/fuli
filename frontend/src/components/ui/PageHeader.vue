<template>
  <header class="page-header">
    <div class="ph-main">
      <div v-if="icon || $slots.icon" class="ph-icon">
        <slot name="icon"><AppIcon :name="icon!" :size="18" /></slot>
      </div>
      <div class="ph-text">
        <h1 class="ph-title">
          {{ title }}
          <span v-if="badge" class="ph-badge">{{ badge }}</span>
        </h1>
        <p v-if="subtitle" class="ph-sub">{{ subtitle }}</p>
      </div>
    </div>
    <div v-if="$slots.actions" class="ph-actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup lang="ts">
import AppIcon from './AppIcon.vue'
import type { IconName } from './icons'

withDefaults(
  defineProps<{
    title: string
    subtitle?: string
    icon?: IconName
    /** 标题右侧的小标签，例如「实时」 */
    badge?: string
  }>(),
  { badge: '' }
)
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 2px 0 14px;
  margin-bottom: 4px;
  border-bottom: 1px solid var(--term-border);
  position: relative;
  flex-wrap: wrap;
}

/* 标题下的一小段渐变高亮，替代整条边框 */
.page-header::after {
  content: '';
  position: absolute;
  left: 0;
  bottom: -1px;
  width: 120px;
  height: 1px;
  background: var(--term-grad);
  box-shadow: 0 0 12px var(--term-glow);
}

.ph-main {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.ph-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: var(--term-radius);
  color: var(--term-accent);
  background: var(--term-active);
  border: 1px solid rgba(56, 189, 248, 0.22);
}

.ph-text {
  min-width: 0;
}

.ph-title {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  letter-spacing: 0.4px;
  color: var(--term-fg);
  display: flex;
  align-items: center;
  gap: 8px;
}

.ph-badge {
  font-family: var(--term-font-mono);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.6px;
  padding: 1px 6px;
  border-radius: 999px;
  color: var(--term-accent);
  background: var(--term-active);
  border: 1px solid rgba(56, 189, 248, 0.28);
}

.ph-sub {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--term-fg-muted);
}

.ph-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
</style>
