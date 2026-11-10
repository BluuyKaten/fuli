<template>
  <div class="terminal-root">
    <!-- ======================= 顶部工具栏 ======================= -->
    <header class="terminal-toolbar">
      <div class="toolbar-left">
        <!-- 品牌标识 -->
        <div class="brand">
          <span class="brand-mark" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="17" height="17" fill="none">
              <path d="M4 15.5 9.2 9l3.6 4.2L20 5" stroke="url(#bg1)" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" />
              <path d="M4 20h16" stroke="url(#bg1)" stroke-width="1.4" stroke-linecap="round" opacity=".45" />
              <defs>
                <linearGradient id="bg1" x1="0" y1="0" x2="24" y2="24" gradientUnits="userSpaceOnUse">
                  <stop stop-color="#38bdf8" />
                  <stop offset="1" stop-color="#a78bfa" />
                </linearGradient>
              </defs>
            </svg>
          </span>
          <span class="brand-text">
            <b>复利 <em>TERMINAL</em></b>
          </span>
        </div>

        <span class="toolbar-sep" aria-hidden="true" />

        <!-- 快速跳转 -->
        <div class="quick-jump" :class="{ 'is-open': searchOpen }">
          <span class="qj-icon"><AppIcon name="search" :size="14" /></span>
          <input
            ref="searchRef"
            v-model="query"
            class="qj-input"
            type="text"
            placeholder="跳转页面…"
            spellcheck="false"
            @focus="searchOpen = true"
            @blur="onSearchBlur"
            @keydown.enter.prevent="onSearchEnter"
            @keydown.esc="closeSearch"
          />
          <kbd class="qj-kbd" @click="focusSearch">Ctrl K</kbd>

          <Transition name="qj-fade">
            <div v-if="searchOpen && results.length" class="qj-panel">
              <button
                v-for="(item, idx) in results"
                :key="item.key"
                class="qj-item"
                :class="{ 'is-active': idx === activeIndex }"
                @mousedown.prevent="go(item.key)"
                @mouseenter="activeIndex = idx"
              >
                <AppIcon :name="item.icon" :size="14" />
                <span>{{ item.label }}</span>
                <span class="qj-path">{{ item.path }}</span>
              </button>
            </div>
          </Transition>
        </div>
      </div>

      <div class="toolbar-right">
        <!-- 市场状态 -->
        <span class="status-chip" :class="`is-${marketStatus.tone}`">
          <span class="status-dot" />
          {{ marketStatus.label }}
        </span>

        <!-- 在线状态 -->
        <span class="status-chip is-quiet" :class="{ 'is-offline': !online }" :title="online ? '网络连接正常' : '网络已断开'">
          <span class="status-dot" />
          {{ online ? '在线' : '离线' }}
        </span>

        <span class="toolbar-clock mono">
          <AppIcon name="clock" :size="13" />
          {{ currentTime }}
        </span>

        <a-dropdown trigger="['click']" placement="bottomRight">
          <span class="user-trigger">
            <span class="user-avatar">{{ userInitial }}</span>
            <span class="user-name">{{ userStore.nickname || userStore.username }}</span>
            <AppIcon name="chevronDown" :size="12" class="user-caret" />
          </span>
          <template #overlay>
            <a-menu class="terminal-menu">
              <a-menu-item key="profile" @click="activatePanel('AccountProfile')">
                <AppIcon name="user" :size="14" /> 账户信息
              </a-menu-item>
              <a-menu-item key="password" @click="showPasswordModal = true">
                <AppIcon name="lock" :size="14" /> 修改密码
              </a-menu-item>
              <a-menu-divider />
              <a-menu-item key="logout" @click="handleLogout">
                <AppIcon name="logout" :size="14" /> 退出登录
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </div>
    </header>

    <!-- ======================= 主体 ======================= -->
    <div class="terminal-body">
      <!-- 左侧导航 -->
      <aside ref="sidebarRef" class="terminal-sidebar" :class="{ 'is-collapsed': collapsed }">
        <div class="sidebar-head">
          <span v-if="!collapsed" class="sidebar-label">工作台</span>
          <button class="collapse-btn" :title="collapsed ? '展开侧栏' : '收起侧栏'" @click="toggleSidebar">
            <AppIcon :name="collapsed ? 'chevronRight' : 'chevronLeft'" :size="14" />
          </button>
        </div>

        <nav class="sidebar-list">
          <div
            v-for="item in sideItems"
            :key="item.key"
            class="sidebar-item"
            :class="{ active: activePanelId === item.key }"
            :title="collapsed ? item.label : undefined"
            @click="onSideNavClick(item.key)"
          >
            <span class="sidebar-indicator" aria-hidden="true" />
            <span class="sidebar-icon"><AppIcon :name="item.icon" :size="16" /></span>
            <span class="sidebar-text">{{ item.label }}</span>
          </div>
        </nav>

        <div class="sidebar-foot">
          <span v-if="!collapsed" class="sidebar-version mono">v2.0 · neon</span>
        </div>
      </aside>

      <!-- 中央内容区 -->
      <main class="terminal-main">
        <span class="main-grid" aria-hidden="true" />

        <Transition :css="false" mode="out-in" @enter="onContentEnter" @leave="onContentLeave">
          <!-- K 线图：docking 面板 -->
          <div v-if="isKlineRoute && activePanel" :key="`kline:${activePanel.id}`" class="dock-panels">
            <div class="dock-panel">
              <div class="panel-header">
                <span class="panel-title">
                  <span class="panel-icon"><AppIcon :name="activePanel.icon ?? 'candle'" :size="14" /></span>
                  {{ activePanel.title }}
                </span>
                <div class="panel-actions">
                  <button class="panel-btn" title="最小化" @click="withFlip(() => minimizePanel(activePanel!.id))">
                    <AppIcon name="minus" :size="13" />
                  </button>
                  <button class="panel-btn close-btn" title="关闭" @click="withFlip(() => closePanel(activePanel!.id))">
                    <AppIcon name="close" :size="13" />
                  </button>
                </div>
              </div>
              <div class="panel-body">
                <component :is="activePanel.component" />
              </div>
            </div>
          </div>

          <!-- 其他页面 -->
          <div v-else :key="`page:${route.path}`" class="page-host">
            <router-view />
          </div>
        </Transition>

        <!-- 底部 dock 栏 -->
        <footer v-if="isKlineRoute && allActivePanels.length > 0" ref="dockRef" class="terminal-dock">
          <div
            v-for="panel in allActivePanels"
            :key="panel.id"
            class="dock-item"
            :class="{ minimized: panel.minimized, closed: panel.closed }"
            @click="onDockItemClick(panel)"
          >
            <span v-if="panel.icon" class="dock-icon"><AppIcon :name="panel.icon" :size="13" /></span>
            <span class="dock-title">{{ panel.title }}</span>
            <span v-if="!panel.closed" class="dock-close" title="关闭" @click.stop="withFlip(() => closePanel(panel.id))">
              <AppIcon name="close" :size="10" />
            </span>
          </div>
        </footer>
      </main>
    </div>

    <!-- 修改密码弹窗 -->
    <a-modal v-model:open="showPasswordModal" title="修改密码" :confirm-loading="passwordLoading" @ok="handleChangePassword">
      <a-form layout="vertical">
        <a-form-item label="原密码">
          <a-input-password v-model:value="passwordForm.oldPassword" placeholder="请输入原密码" />
        </a-form-item>
        <a-form-item label="新密码">
          <a-input-password v-model:value="passwordForm.newPassword" placeholder="请输入新密码（至少6位）" />
        </a-form-item>
        <a-form-item label="确认新密码">
          <a-input-password v-model:value="passwordForm.confirmPassword" placeholder="请再次输入新密码" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { useUserStore } from '@/stores/user'
import { changePassword, logoutApi } from '@/api/auth'
import { useDocking } from '@/composables/useDocking'
import AppIcon from '@/components/ui/AppIcon.vue'
import type { IconName } from '@/components/ui/icons'
import { captureLayout, flipLayout, pageEnter, pageLeave } from '@/theme/motion'
import DashboardPage from '@/views/dashboard/DashboardPage.vue'
import KLinePage from '@/views/stock/KLinePage.vue'
import TradeList from '@/views/trade/TradeList.vue'
import StockSync from '@/views/stock/StockSync.vue'
import ProfilePage from '@/views/account/ProfilePage.vue'
import JournalPage from '@/views/journal/JournalPage.vue'
import AlertPage from '@/views/alert/AlertPage.vue'
import PerformancePage from '@/views/analysis/PerformancePage.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

/* ---------------- 导航配置 ---------------- */
interface NavItem {
  key: string
  label: string
  icon: IconName
  path: string
}

const sideItems: NavItem[] = [
  { key: 'Dashboard', label: '仪表盘', icon: 'dashboard', path: '/dashboard' },
  { key: 'KlineChart', label: 'K线图', icon: 'candle', path: '/kline' },
  { key: 'HistoryPractice', label: '历史交易练习', icon: 'target', path: '/practice' },
  { key: 'TradeList', label: '交易记录', icon: 'clipboard', path: '/trade' },
  { key: 'JournalPage', label: '复盘笔记', icon: 'notebook', path: '/journal' },
  { key: 'AlertPage', label: '风控预警', icon: 'shield', path: '/alert' },
  { key: 'PerformancePage', label: '绩效分析', icon: 'trophy', path: '/performance' },
  { key: 'ReportPage', label: '复盘报告', icon: 'activity', path: '/report' },
  { key: 'StockSync', label: '数据同步', icon: 'refresh', path: '/sync' },
  { key: 'OkxSettings', label: 'OKX 账户', icon: 'lock', path: '/okx' },
  { key: 'AccountProfile', label: '账户信息', icon: 'user', path: '/account' }
]

/* ---------------- 侧栏折叠 ---------------- */
const STORAGE_KEY = 'terminal.sidebar.collapsed'
const sidebarRef = ref<HTMLElement | null>(null)
const collapsed = ref(localStorage.getItem(STORAGE_KEY) === '1')

const toggleSidebar = () => {
  collapsed.value = !collapsed.value
  localStorage.setItem(STORAGE_KEY, collapsed.value ? '1' : '0')
}

/* ---------------- 快速跳转 ---------------- */
const searchRef = ref<HTMLInputElement | null>(null)
const query = ref('')
const searchOpen = ref(false)
const activeIndex = ref(0)

const results = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return sideItems
  return sideItems.filter(
    (i) => i.label.toLowerCase().includes(q) || i.key.toLowerCase().includes(q) || i.path.includes(q)
  )
})

const focusSearch = () => {
  searchRef.value?.focus()
  searchOpen.value = true
}

const closeSearch = () => {
  searchOpen.value = false
  query.value = ''
  searchRef.value?.blur()
}

const onSearchBlur = () => {
  // 延迟关闭，保证点击结果项时 mousedown 先触发
  window.setTimeout(() => {
    searchOpen.value = false
  }, 120)
}

const go = (key: string) => {
  closeSearch()
  onSideNavClick(key)
}

const onSearchEnter = () => {
  const target = results.value[activeIndex.value] ?? results.value[0]
  if (target) go(target.key)
}

watch(results, () => {
  activeIndex.value = 0
})

const onGlobalKeydown = (e: KeyboardEvent) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    focusSearch()
  }
}

/* ---------------- 时钟 / 市场状态 / 在线状态 ---------------- */
const currentTime = ref('')
const nowMs = ref(Date.now())
let timer: number | null = null

const updateTime = () => {
  const d = new Date()
  nowMs.value = d.getTime()
  const pad = (n: number) => String(n).padStart(2, '0')
  currentTime.value = `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** A 股交易时段判定 */
const marketStatus = computed(() => {
  const d = new Date(nowMs.value)
  const day = d.getDay()
  const minutes = d.getHours() * 60 + d.getMinutes()
  if (day === 0 || day === 6) return { label: '休市', tone: 'closed' as const }
  if (minutes >= 555 && minutes < 570) return { label: '集合竞价', tone: 'warn' as const }
  if ((minutes >= 570 && minutes < 690) || (minutes >= 780 && minutes < 900)) {
    return { label: '交易中', tone: 'live' as const }
  }
  if (minutes >= 690 && minutes < 780) return { label: '午间休市', tone: 'warn' as const }
  if (minutes < 555) return { label: '未开盘', tone: 'closed' as const }
  return { label: '已收盘', tone: 'closed' as const }
})

const online = ref(navigator.onLine)
const syncOnline = () => {
  online.value = navigator.onLine
}

/* ---------------- 内容区过渡 ---------------- */
const isKlineRoute = computed(() => route.name === 'KlineChart')

const onContentEnter = (el: Element, done: () => void) => {
  // K 线页含 canvas，只做淡入不做位移，避免图表尺寸抖动
  pageEnter(el, done, { fade: isKlineRoute.value })
}

const onContentLeave = (el: Element, done: () => void) => {
  pageLeave(el, done)
}

/* ---------------- docking ---------------- */
const {
  visiblePanels,
  allActivePanels,
  registerPanel,
  activatePanel,
  minimizePanel,
  restorePanel,
  closePanel,
  reopenPanel
} = useDocking()

const activePanelId = ref('Dashboard')
const dockRef = ref<HTMLElement | null>(null)

const activePanel = computed(() => {
  if (!isKlineRoute.value) return null
  const found = visiblePanels.value.find((p) => p.id === activePanelId.value)
  if (found) return found
  return visiblePanels.value.find((p) => p.id === 'KlineChart') ?? visiblePanels.value[0]
})

/** 执行会改变 dock 项数量的操作，并用 Flip 让重排有动画 */
const withFlip = async (fn: () => void) => {
  const items = dockRef.value?.querySelectorAll('.dock-item')
  const state = items && items.length ? captureLayout(items) : null
  fn()
  await nextTick()
  flipLayout(state)
}

watch(
  () => route.name,
  (name) => {
    const panelId = String(name)
    if (sideItems.some((i) => i.key === panelId)) {
      activePanelId.value = panelId
      if (name === 'KlineChart') restorePanel('KlineChart')
    }
  },
  { immediate: true }
)

/* ---------------- 用户 ---------------- */
const userInitial = computed(() => (userStore.nickname || userStore.username || '?')[0]?.toUpperCase() || '?')

const showPasswordModal = ref(false)
const passwordLoading = ref(false)
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const handleLogout = async () => {
  try {
    await logoutApi()
  } catch {
    // 忽略登出接口失败：本地仍清除令牌，令牌黑名单由后端尽力而为
  }
  userStore.clearToken()
  router.push('/login')
}

const handleChangePassword = async () => {
  if (!passwordForm.oldPassword || !passwordForm.newPassword || !passwordForm.confirmPassword) {
    message.warning('请填写完整')
    return
  }
  if (passwordForm.newPassword.length < 6) {
    message.warning('新密码至少6位')
    return
  }
  if (passwordForm.newPassword !== passwordForm.confirmPassword) {
    message.warning('两次输入的新密码不一致')
    return
  }
  passwordLoading.value = true
  try {
    await changePassword(passwordForm.oldPassword, passwordForm.newPassword)
    message.success('密码修改成功，请重新登录')
    showPasswordModal.value = false
    passwordForm.oldPassword = ''
    passwordForm.newPassword = ''
    passwordForm.confirmPassword = ''
    window.setTimeout(() => {
      userStore.clearToken()
      router.push('/login')
    }, 1000)
  } catch (error: any) {
    message.error(error.message || '密码修改失败')
  } finally {
    passwordLoading.value = false
  }
}

/* ---------------- 导航点击 ---------------- */
const onSideNavClick = (key: string) => {
  const item = sideItems.find((i) => i.key === key)
  if (!item) return
  if (key === 'KlineChart') activatePanel(key)
  router.push(item.path)
}

const onDockItemClick = (panel: { id: string; closed: boolean; minimized: boolean }) => {
  withFlip(() => {
    if (panel.closed) {
      reopenPanel(panel.id)
      activePanelId.value = panel.id
    } else if (panel.minimized) {
      restorePanel(panel.id)
      activePanelId.value = panel.id
    } else {
      minimizePanel(panel.id)
    }
  })
}

onMounted(() => {
  updateTime()
  timer = window.setInterval(updateTime, 1000)
  window.addEventListener('keydown', onGlobalKeydown)
  window.addEventListener('online', syncOnline)
  window.addEventListener('offline', syncOnline)

  registerPanel('Dashboard', '仪表盘', DashboardPage, 'dashboard')
  registerPanel('TradeList', '交易记录', TradeList, 'clipboard')
  registerPanel('KlineChart', 'K线图', KLinePage, 'candle')
  registerPanel('JournalPage', '复盘笔记', JournalPage, 'notebook')
  registerPanel('AlertPage', '风控预警', AlertPage, 'shield')
  registerPanel('PerformancePage', '绩效分析', PerformancePage, 'trophy')
  registerPanel('StockSync', '数据同步', StockSync, 'refresh')
  registerPanel('AccountProfile', '账户信息', ProfilePage, 'user')
})

onBeforeUnmount(() => {
  if (timer !== null) clearInterval(timer)
  window.removeEventListener('keydown', onGlobalKeydown)
  window.removeEventListener('online', syncOnline)
  window.removeEventListener('offline', syncOnline)
})
</script>

<style scoped>
.terminal-root {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: transparent;
  color: var(--term-fg);
  font-family: var(--term-font-sans);
}

/* ======================= 工具栏 ======================= */
.terminal-toolbar {
  height: 50px;
  flex: 0 0 50px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 14px;
  background: linear-gradient(180deg, rgba(18, 26, 43, 0.96), rgba(10, 15, 26, 0.92));
  border-bottom: 1px solid var(--term-border);
  backdrop-filter: blur(12px);
  user-select: none;
  position: relative;
  z-index: 20;
}

.terminal-toolbar::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 1px;
  background: var(--term-grad-line);
  opacity: 0.35;
  pointer-events: none;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

/* 品牌 */
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: rgba(56, 189, 248, 0.1);
  border: 1px solid rgba(56, 189, 248, 0.28);
  box-shadow: 0 0 16px rgba(56, 189, 248, 0.16);
}

.brand-text b {
  font-size: 14px;
  font-weight: 650;
  letter-spacing: 0.6px;
  color: var(--term-fg);
  white-space: nowrap;
}

.brand-text em {
  font-family: var(--term-font-mono);
  font-style: normal;
  font-size: 11px;
  font-weight: 500;
  letter-spacing: 1.4px;
  color: var(--term-accent);
  opacity: 0.85;
}

.toolbar-sep {
  width: 1px;
  height: 18px;
  background: var(--term-border);
}

/* 快速跳转 */
.quick-jump {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 8px 0 9px;
  background: rgba(4, 6, 12, 0.7);
  border: 1px solid var(--term-border);
  border-radius: var(--term-radius-sm);
  width: 230px;
  transition: border-color var(--term-dur-fast), box-shadow var(--term-dur-fast), width var(--term-dur);
}

.quick-jump.is-open {
  border-color: rgba(56, 189, 248, 0.5);
  box-shadow: 0 0 0 3px rgba(56, 189, 248, 0.1);
  width: 280px;
}

.qj-icon {
  color: var(--term-fg-dim);
  display: inline-flex;
}

.qj-input {
  flex: 1;
  min-width: 0;
  background: transparent;
  border: none;
  outline: none;
  color: var(--term-fg);
  font-family: var(--term-font-sans);
  font-size: 12.5px;
}

.qj-input::placeholder {
  color: var(--term-fg-dim);
}

.qj-kbd {
  font-family: var(--term-font-mono);
  font-size: 9.5px;
  letter-spacing: 0.5px;
  padding: 1px 5px;
  border-radius: 3px;
  color: var(--term-fg-dim);
  border: 1px solid var(--term-border);
  background: rgba(255, 255, 255, 0.03);
  cursor: pointer;
  white-space: nowrap;
}

.qj-panel {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  right: 0;
  padding: 4px;
  background: var(--term-bg-elevated);
  border: 1px solid var(--term-border-strong);
  border-radius: var(--term-radius);
  box-shadow: var(--term-shadow);
  z-index: 40;
  max-height: 320px;
  overflow-y: auto;
}

.qj-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 9px;
  border: none;
  border-radius: var(--term-radius-sm);
  background: transparent;
  color: var(--term-fg-muted);
  font-family: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
  transition: background var(--term-dur-fast), color var(--term-dur-fast);
}

.qj-item.is-active {
  background: var(--term-active);
  color: var(--term-accent);
}

.qj-path {
  margin-left: auto;
  font-family: var(--term-font-mono);
  font-size: 10px;
  color: var(--term-fg-dim);
}

.qj-fade-enter-active,
.qj-fade-leave-active {
  transition: opacity var(--term-dur-fast), transform var(--term-dur-fast);
}

.qj-fade-enter-from,
.qj-fade-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}

/* 状态指示 */
.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 22px;
  padding: 0 8px;
  border-radius: 999px;
  font-size: 11px;
  letter-spacing: 0.3px;
  color: var(--term-fg-muted);
  border: 1px solid var(--term-border);
  background: rgba(255, 255, 255, 0.02);
  white-space: nowrap;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--term-fg-dim);
  animation: neon-breathe 2.4s var(--term-ease) infinite;
}

.is-live {
  color: var(--term-down);
  border-color: rgba(0, 229, 160, 0.32);
  background: rgba(0, 229, 160, 0.07);
}

.is-live .status-dot {
  background: var(--term-down);
  box-shadow: 0 0 8px rgba(0, 229, 160, 0.7);
}

.is-warn {
  color: var(--term-warning);
  border-color: rgba(251, 191, 36, 0.3);
  background: rgba(251, 191, 36, 0.06);
}

.is-warn .status-dot {
  background: var(--term-warning);
  box-shadow: 0 0 8px rgba(251, 191, 36, 0.6);
}

.is-closed {
  color: var(--term-fg-muted);
}

.is-closed .status-dot {
  background: var(--term-fg-dim);
  animation: none;
  opacity: 0.7;
}

.is-quiet .status-dot {
  background: var(--term-success);
}

.status-chip.is-offline {
  color: var(--term-danger);
  border-color: rgba(244, 63, 94, 0.35);
}

.status-chip.is-offline .status-dot {
  background: var(--term-danger);
  animation: neon-breathe 1s infinite;
}

.toolbar-clock {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--term-fg-muted);
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.4px;
}

/* 用户 */
.user-trigger {
  display: flex;
  align-items: center;
  gap: 7px;
  cursor: pointer;
  padding: 3px 8px 3px 4px;
  border-radius: 999px;
  border: 1px solid transparent;
  transition: background var(--term-dur-fast), border-color var(--term-dur-fast);
}

.user-trigger:hover {
  background: var(--term-hover);
  border-color: var(--term-border);
}

.user-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--term-grad);
  color: #05101f;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  box-shadow: 0 0 12px rgba(56, 189, 248, 0.3);
}

.user-name {
  font-size: 12.5px;
  color: var(--term-fg);
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-caret {
  color: var(--term-fg-dim);
}

/* ======================= 主体 ======================= */
.terminal-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

/* 侧栏 */
.terminal-sidebar {
  width: 186px;
  flex: 0 0 186px;
  display: flex;
  flex-direction: column;
  background: linear-gradient(180deg, rgba(9, 13, 22, 0.9), rgba(4, 6, 12, 0.92));
  border-right: 1px solid var(--term-border);
  overflow: hidden;
  transition: width 0.28s var(--term-ease), flex-basis 0.28s var(--term-ease);
}

.terminal-sidebar.is-collapsed {
  width: 58px;
  flex-basis: 58px;
}

.sidebar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 40px;
  padding: 0 10px 0 14px;
  flex: 0 0 40px;
}

.sidebar-label {
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 1.6px;
  text-transform: uppercase;
  color: var(--term-fg-dim);
  white-space: nowrap;
}

.collapse-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border: 1px solid transparent;
  border-radius: var(--term-radius-sm);
  background: transparent;
  color: var(--term-fg-dim);
  cursor: pointer;
  transition: all var(--term-dur-fast);
  flex: 0 0 auto;
}

.collapse-btn:hover {
  background: var(--term-hover);
  border-color: var(--term-border);
  color: var(--term-accent);
}

.is-collapsed .sidebar-head {
  padding: 0;
  justify-content: center;
}

.sidebar-list {
  flex: 1;
  padding: 4px 8px;
  overflow-y: auto;
  overflow-x: hidden;
}

.sidebar-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 36px;
  padding: 0 10px;
  margin-bottom: 2px;
  font-size: 12.5px;
  border-radius: var(--term-radius-sm);
  cursor: pointer;
  color: var(--term-fg-muted);
  transition: background var(--term-dur-fast), color var(--term-dur-fast);
  white-space: nowrap;
}

.sidebar-item:hover {
  background: var(--term-hover);
  color: var(--term-fg);
}

.sidebar-item:hover .sidebar-icon {
  color: var(--term-accent);
}

/* 激活态：左侧竖条发光 + 极淡底 */
.sidebar-indicator {
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%) scaleY(0);
  width: 2px;
  height: 16px;
  border-radius: 2px;
  background: var(--term-grad);
  box-shadow: 0 0 10px var(--term-glow);
  transition: transform var(--term-dur) var(--term-ease);
}

.sidebar-item.active {
  background: var(--term-active);
  color: var(--term-accent);
  font-weight: 600;
}

.sidebar-item.active .sidebar-indicator {
  transform: translateY(-50%) scaleY(1);
}

.sidebar-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  flex: 0 0 18px;
  color: var(--term-fg-muted);
  transition: color var(--term-dur-fast);
}

.sidebar-item.active .sidebar-icon {
  color: var(--term-accent);
  filter: drop-shadow(0 0 6px var(--term-glow));
}

.sidebar-text {
  overflow: hidden;
  opacity: 1;
  transition: opacity 0.18s var(--term-ease);
}

.is-collapsed .sidebar-item {
  justify-content: center;
  padding: 0;
  gap: 0;
}

.is-collapsed .sidebar-text {
  opacity: 0;
  width: 0;
  pointer-events: none;
}

.is-collapsed .sidebar-indicator {
  left: 2px;
}

.sidebar-foot {
  flex: 0 0 auto;
  padding: 10px 14px;
  border-top: 1px solid var(--term-border);
  min-height: 34px;
}

.sidebar-version {
  font-size: 10px;
  letter-spacing: 0.8px;
  color: var(--term-fg-dim);
  opacity: 0.75;
}

/* 内容区 */
.terminal-main {
  position: relative;
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: transparent;
}

/* 网格底：极淡，跟着视口固定，营造 HUD 质感 */
.main-grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  z-index: 0;
  background-image:
    linear-gradient(var(--term-grid-line) 1px, transparent 1px),
    linear-gradient(90deg, var(--term-grid-line) 1px, transparent 1px);
  background-size: 34px 34px;
  mask-image: radial-gradient(120% 90% at 50% 0%, #000 20%, transparent 78%);
  -webkit-mask-image: radial-gradient(120% 90% at 50% 0%, #000 20%, transparent 78%);
}

.page-host {
  position: relative;
  z-index: 1;
  flex: 1;
  min-height: 0;
  overflow: auto;
}

/* ======================= docking 面板 ======================= */
.dock-panels {
  position: relative;
  z-index: 1;
  flex: 1;
  overflow: auto;
  padding: 12px;
  min-height: 0;
}

.dock-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 400px;
  background: var(--term-panel-bg);
  border: 1px solid var(--term-border);
  border-radius: var(--term-radius);
  overflow: hidden;
  box-shadow: 0 0 0 1px rgba(56, 189, 248, 0.06), var(--term-shadow-sm);
}

.panel-header {
  height: 36px;
  flex: 0 0 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 8px 0 12px;
  background: var(--term-panel-header);
  border-bottom: 1px solid var(--term-border);
  user-select: none;
  position: relative;
}

.panel-header::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 1px;
  background: var(--term-grad-line);
  opacity: 0.45;
}

.panel-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--term-fg);
}

.panel-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 4px;
  background: var(--term-active);
  color: var(--term-accent);
}

.panel-actions {
  display: flex;
  gap: 2px;
}

.panel-btn {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--term-fg-muted);
  cursor: pointer;
  transition: all var(--term-dur-fast);
}

.panel-btn:hover {
  background: var(--term-hover);
  color: var(--term-fg);
}

.close-btn:hover {
  background: var(--term-danger);
  color: #fff;
}

.panel-body {
  flex: 1;
  overflow: auto;
  padding: 12px;
  min-height: 0;
}

/* ======================= 底部 dock ======================= */
.terminal-dock {
  position: relative;
  z-index: 1;
  height: 38px;
  flex: 0 0 38px;
  display: flex;
  align-items: center;
  gap: 3px;
  padding: 0 8px;
  background: rgba(4, 6, 12, 0.85);
  border-top: 1px solid var(--term-border);
  overflow-x: auto;
}

.dock-item {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 26px;
  padding: 0 8px 0 10px;
  background: var(--term-panel-bg);
  border: 1px solid var(--term-border);
  border-radius: var(--term-radius-sm);
  cursor: pointer;
  font-size: 11.5px;
  color: var(--term-fg-muted);
  white-space: nowrap;
  transition: all var(--term-dur-fast);
  position: relative;
  overflow: hidden;
}

.dock-item::before {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: 0;
  width: 2px;
  background: transparent;
}

.dock-item:hover {
  background: var(--term-hover);
  color: var(--term-fg);
  border-color: var(--term-border-strong);
}

.dock-item.minimized {
  color: var(--term-fg);
  border-color: rgba(56, 189, 248, 0.35);
  box-shadow: 0 0 14px rgba(56, 189, 248, 0.1);
}

.dock-item.minimized::before {
  background: var(--term-grad);
  box-shadow: 0 0 8px var(--term-glow);
}

.dock-item.closed {
  opacity: 0.4;
  border-style: dashed;
  background: transparent;
}

.dock-item.closed .dock-title {
  text-decoration: line-through;
}

.dock-item.closed:hover {
  opacity: 0.85;
  border-style: solid;
}

.dock-item.closed:hover .dock-title {
  text-decoration: none;
}

.dock-icon {
  display: inline-flex;
  color: var(--term-accent);
}

.dock-item.closed .dock-icon {
  color: var(--term-fg-dim);
}

.dock-title {
  max-width: 96px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dock-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 15px;
  height: 15px;
  border-radius: 3px;
  opacity: 0;
  transition: all var(--term-dur-fast);
  margin-left: 2px;
}

.dock-item:hover .dock-close {
  opacity: 1;
}

.dock-close:hover {
  background: var(--term-danger);
  color: #fff;
}

/* 窄屏收敛 */
@media (max-width: 900px) {
  .quick-jump {
    width: 150px;
  }

  .quick-jump.is-open {
    width: 180px;
  }

  .toolbar-clock,
  .status-chip.is-quiet {
    display: none;
  }
}
</style>
