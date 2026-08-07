<template>
  <a-config-provider :theme="themeConfig">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import { theme } from 'ant-design-vue'

const themeConfig = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: '#1677ff',
    colorBgBase: '#0d1117',
    colorTextBase: '#c9d1d9',
    borderRadius: 4,
    fontFamily: "'JetBrains Mono', 'Fira Code', 'SF Mono', Menlo, Consolas, -apple-system, BlinkMacSystemFont, monospace"
  },
  components: {
    Layout: {
      headerBg: '#161b22',
      siderBg: '#0d1117',
      bodyBg: '#0d1117'
    },
    Menu: {
      darkItemBg: '#161b22',
      darkSubMenuItemBg: '#0d1117'
    },
    Card: {
      colorBgContainer: '#161b22'
    },
    Table: {
      headerBg: '#161b22',
      rowHoverBg: '#1c2128'
    },
    Modal: {
      contentBg: '#161b22',
      headerBg: '#161b22'
    }
  }
}
</script>

<style>
#app {
  width: 100%;
  height: 100vh;
}

/* ===== 终端设计系统变量 ===== */
:root {
  /* GitHub Dark Dimmed 风格配色 */
  --term-bg: #0d1117;
  --term-fg: #c9d1d9;
  --term-fg-muted: #8b949e;
  --term-accent: #58a6ff;
  --term-border: #30363d;
  --term-hover: #1c2128;
  --term-active: #1f6feb22;

  --term-toolbar-bg: #161b22;
  --term-sidebar-bg: #0d1117;
  --term-dock-bg: #010409;
  --term-panel-bg: #161b22;
  --term-panel-header: #1c2128;

  --term-danger: #f85149;
  --term-success: #3fb950;
  --term-warning: #d29922;

  /* 涨跌配色：中国习惯，红涨绿跌 */
  --term-up: #ef5350;
  --term-down: #26a69a;
}

/* 滚动条美化 */
::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

::-webkit-scrollbar-track {
  background: var(--term-bg);
}

::-webkit-scrollbar-thumb {
  background: var(--term-border);
  border-radius: 4px;
}

::-webkit-scrollbar-thumb:hover {
  background: var(--term-fg-muted);
}

/* antd 组件终端化覆盖 */
.ant-card {
  background: var(--term-panel-bg) !important;
  border-color: var(--term-border) !important;
}

.ant-card-head {
  background: var(--term-panel-header) !important;
  border-bottom-color: var(--term-border) !important;
  color: var(--term-fg) !important;
}

.ant-card-body {
  color: var(--term-fg);
}

.ant-table {
  background: var(--term-panel-bg) !important;
}

.ant-table-thead > tr > th {
  background: var(--term-panel-header) !important;
  color: var(--term-fg-muted) !important;
  border-bottom-color: var(--term-border) !important;
}

.ant-table-tbody > tr > td {
  border-bottom-color: var(--term-border) !important;
  color: var(--term-fg) !important;
}

.ant-table-tbody > tr:hover > td {
  background: var(--term-hover) !important;
}

.ant-btn {
  font-family: inherit;
}

.ant-modal-content,
.ant-modal-header {
  background: var(--term-panel-bg) !important;
}

.ant-modal-title,
.ant-form-item-label > label {
  color: var(--term-fg) !important;
}

.ant-input,
.ant-input-number,
.ant-select-selector,
.ant-picker {
  background: var(--term-bg) !important;
  border-color: var(--term-border) !important;
  color: var(--term-fg) !important;
}

.ant-input::placeholder {
  color: var(--term-fg-muted) !important;
}

.ant-menu {
  font-family: inherit;
}

.terminal-menu {
  background: var(--term-panel-bg) !important;
  border: 1px solid var(--term-border);
}

.terminal-menu .ant-menu-item {
  color: var(--term-fg) !important;
}

.terminal-menu .ant-menu-item:hover {
  background: var(--term-hover) !important;
}

/* ===== 深色主题全覆盖 ===== */
.ant-statistic-title { color: var(--term-fg-muted) !important; }
.ant-statistic-content { color: var(--term-fg) !important; }
.ant-card-meta-title, .ant-card-meta-description { color: var(--term-fg) !important; }
.ant-empty-description { color: var(--term-fg-muted) !important; }
.ant-tabs-tab { color: var(--term-fg-muted) !important; }
.ant-tabs-tab-active .ant-tabs-tab-btn { color: var(--term-accent) !important; }
.ant-tabs-ink-bar { background: var(--term-accent) !important; }
.ant-alert { background: var(--term-panel-header) !important; border-color: var(--term-border) !important; }
.ant-alert-message { color: var(--term-fg) !important; }
.ant-alert-description { color: var(--term-fg-muted) !important; }
.ant-divider { border-color: var(--term-border) !important; }
.ant-spin-dot-item { background: var(--term-accent) !important; }
.ant-spin-text { color: var(--term-fg) !important; }
.ant-message-notice-content { background: var(--term-panel-bg) !important; color: var(--term-fg) !important; border: 1px solid var(--term-border) !important; }
.ant-popover-inner, .ant-popover-title, .ant-popover-inner-content { background: var(--term-panel-bg) !important; color: var(--term-fg) !important; }
.ant-tooltip-inner { background: var(--term-panel-header) !important; color: var(--term-fg) !important; }
.ant-checkbox-wrapper, .ant-radio-wrapper { color: var(--term-fg) !important; }
.ant-input-password-icon, .ant-picker-suffix { color: var(--term-fg-muted) !important; }
.ant-form-item-explain-error { color: var(--term-danger) !important; }
.ant-result-title { color: var(--term-fg) !important; }
.ant-result-subtitle { color: var(--term-fg-muted) !important; }
.ant-progress-text { color: var(--term-fg) !important; }
.ant-tag { background: var(--term-panel-header) !important; border-color: var(--term-border) !important; color: var(--term-fg) !important; }
.ant-select-dropdown { background: var(--term-panel-bg) !important; border: 1px solid var(--term-border) !important; }
.ant-select-item { color: var(--term-fg) !important; }
.ant-select-item-option-active { background: var(--term-hover) !important; }
.ant-select-item-option-selected { background: var(--term-active) !important; }
.ant-picker-panel, .ant-picker-header, .ant-picker-body { background: var(--term-panel-bg) !important; }
.ant-picker-cell { color: var(--term-fg) !important; }
.ant-btn-default { background: var(--term-panel-header) !important; border-color: var(--term-border) !important; color: var(--term-fg) !important; }
.ant-btn-default:hover { background: var(--term-hover) !important; border-color: var(--term-accent) !important; }
.ant-table-placeholder { background: var(--term-panel-bg) !important; }
.ant-table-cell-row-hover { background: var(--term-hover) !important; }
</style>
