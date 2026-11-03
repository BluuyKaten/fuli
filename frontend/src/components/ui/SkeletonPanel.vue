<template>
  <div class="skeleton-panel" :style="{ minHeight: typeof height === 'number' ? `${height}px` : height }">
    <div v-if="title" class="sk-title" :style="{ width: titleWidth }" />
    <div
      v-for="i in rows"
      :key="i"
      class="sk-row"
      :style="{ width: `${rowWidth(i)}%`, animationDelay: `${i * 90}ms` }"
    />
  </div>
</template>

<script setup lang="ts">
/**
 * 骨架屏：替代 a-spin 的圆点加载，让加载态也符合深空霓虹视觉。
 * 行宽做递减处理，看起来像真实内容而不是一排等长条。
 */
withDefaults(
  defineProps<{
    rows?: number
    height?: number | string
    title?: boolean
    titleWidth?: string
  }>(),
  { rows: 4, height: 160, title: true, titleWidth: '34%' }
)

const rowWidth = (i: number): number => {
  const pattern = [100, 92, 97, 84, 95, 88]
  return pattern[(i - 1) % pattern.length]
}
</script>

<style scoped>
.skeleton-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 2px;
  width: 100%;
}

.sk-title,
.sk-row {
  height: 12px;
  border-radius: 4px;
  background: linear-gradient(
    90deg,
    rgba(255, 255, 255, 0.04) 0%,
    rgba(56, 189, 248, 0.14) 45%,
    rgba(255, 255, 255, 0.04) 100%
  );
  background-size: 420px 100%;
  background-repeat: no-repeat;
  animation: neon-shimmer 1.5s var(--term-ease) infinite;
}

.sk-title {
  height: 14px;
  background: linear-gradient(
    90deg,
    rgba(255, 255, 255, 0.06) 0%,
    rgba(167, 139, 250, 0.18) 45%,
    rgba(255, 255, 255, 0.06) 100%
  );
  background-size: 420px 100%;
}

.sk-row {
  height: 10px;
}
</style>
