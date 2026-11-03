<template>
  <div class="glow-divider" :class="[`is-${variant}`, { 'is-vertical': vertical }]" role="separator">
    <span v-if="label" class="gd-label">{{ label }}</span>
  </div>
</template>

<script setup lang="ts">
/**
 * 发光分隔线：用于区块之间，替代 a-divider 的纯灰线。
 * 两面渐隐、中间带微光，不抢内容注意力。
 */
withDefaults(
  defineProps<{
    label?: string
    variant?: 'accent' | 'violet' | 'muted'
    vertical?: boolean
  }>(),
  { variant: 'accent', vertical: false }
)
</script>

<style scoped>
.glow-divider {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 1px;
  margin: 14px 0;
  background: linear-gradient(
    90deg,
    transparent 0%,
    var(--term-border-strong) 18%,
    var(--term-border-strong) 82%,
    transparent 100%
  );
  flex: 0 0 auto;
}

.glow-divider.is-accent::after,
.glow-divider.is-violet::after {
  content: '';
  position: absolute;
  left: 50%;
  top: -1px;
  width: 90px;
  height: 3px;
  transform: translateX(-50%);
  border-radius: 3px;
  filter: blur(2px);
}

.is-accent::after {
  background: radial-gradient(closest-side, rgba(56, 189, 248, 0.55), transparent);
}

.is-violet::after {
  background: radial-gradient(closest-side, rgba(167, 139, 250, 0.5), transparent);
}

.gd-label {
  position: relative;
  z-index: 1;
  padding: 0 8px;
  margin: 0 auto;
  font-size: 11px;
  letter-spacing: 0.6px;
  color: var(--term-fg-dim);
  background: var(--term-bg);
  white-space: nowrap;
}

.is-vertical {
  width: 1px;
  height: 100%;
  margin: 0 12px;
  background: linear-gradient(
    180deg,
    transparent 0%,
    var(--term-border-strong) 18%,
    var(--term-border-strong) 82%,
    transparent 100%
  );
}

.is-vertical .gd-label {
  writing-mode: vertical-rl;
  margin: auto 0;
  padding: 8px 0;
}
</style>
