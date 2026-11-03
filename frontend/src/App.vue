<template>
  <a-config-provider :theme="antdTheme" :locale="zhCN">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
/**
 * 根组件。
 * 主题配置来自 src/theme/antd.ts（与 tokens.ts 共用色板）；
 * 全局样式见 src/styles/global.css，令牌见 src/styles/tokens.css。
 */
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { antdTheme } from '@/theme/antd'
</script>
