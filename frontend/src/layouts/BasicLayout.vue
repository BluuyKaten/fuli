<template>
  <div class="terminal-root">
    <!-- 顶部终端工具栏 -->
    <header class="terminal-toolbar">
      <div class="toolbar-left">
        <span class="terminal-logo">📈 股票终端</span>
        <span class="toolbar-time">{{ currentTime }}</span>
      </div>
      <div class="toolbar-right">
        <a-dropdown trigger="['click']">
          <span class="user-trigger">
            <span class="user-avatar">{{ userInitial }}</span>
            <span class="user-name">{{ userStore.nickname || userStore.username }}</span>
          </span>
          <template #overlay>
            <a-menu class="terminal-menu">
              <a-menu-item key="profile" @click="activatePanel('AccountProfile')">
                <UserOutlined /> 账户信息
              </a-menu-item>
              <a-menu-item key="password" @click="showPasswordModal = true">
                <LockOutlined /> 修改密码
              </a-menu-item>
              <a-menu-divider />
              <a-menu-item key="logout" @click="handleLogout">
                <LogoutOutlined /> 退出登录
              </a-menu-item>
            </a-menu>
          </template>
        </a-dropdown>
      </div>
    </header>

    <!-- 主体三栏布局 -->
    <div class="terminal-body">
      <!-- 左侧：导航面板 -->
      <aside class="terminal-sidebar">
        <div class="sidebar-header">导航</div>
        <div class="sidebar-list">
          <div
            v-for="item in sideItems"
            :key="item.key"
            class="sidebar-item"
            :class="{ active: activePanelId === item.key }"
            @click="onSideNavClick(item.key)"
          >
            <span class="sidebar-icon">{{ item.icon }}</span>
            <span>{{ item.label }}</span>
          </div>
        </div>
      </aside>

      <!-- 中央：内容区 -->
      <main class="terminal-main">
        <!-- K 线图页面：使用 docking 面板 -->
        <div v-if="isKlineRoute" class="dock-panels">
          <div
            v-for="panel in visiblePanels"
            :key="panel.id"
            class="dock-panel"
          >
            <div class="panel-header">
              <span class="panel-title">
                <span v-if="panel.icon" class="panel-icon">{{ panel.icon }}</span>
                {{ panel.title }}
              </span>
              <div class="panel-actions">
                <span class="panel-btn" title="最小化" @click="minimizePanel(panel.id)">─</span>
                <span class="panel-btn close-btn" title="关闭" @click="closePanel(panel.id)">✕</span>
              </div>
            </div>
            <div class="panel-body">
              <component :is="panel.component" />
            </div>
          </div>
          <!-- 兜底：没有可见面板时占位 -->
          <div v-if="visiblePanels.length === 0" class="empty-main">
            <div>所有面板已最小化或关闭</div>
            <div class="empty-hint">从底部 dock 栏或左侧导航恢复</div>
          </div>
        </div>

        <!-- 其他页面：使用 router-view -->
        <router-view v-else />

        <!-- 底部 dock 栏（仅 K 线图显示） -->
        <footer v-if="isKlineRoute && allActivePanels.length > 0" class="terminal-dock">
          <div
            v-for="panel in allActivePanels"
            :key="panel.id"
            class="dock-item"
            :class="{ minimized: panel.minimized, closed: panel.closed }"
            @click="onDockItemClick(panel)"
          >
            <span v-if="panel.icon" class="dock-icon">{{ panel.icon }}</span>
            <span class="dock-title">{{ panel.title }}</span>
            <span
              v-if="!panel.closed"
              class="dock-close"
              title="关闭"
              @click.stop="closePanel(panel.id)"
            >✕</span>
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
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LockOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons-vue'
import { useUserStore } from '@/stores/user'
import { changePassword } from '@/api/auth'
import { useDocking } from '@/composables/useDocking'
import { message } from 'ant-design-vue'
import DashboardPage from '@/views/dashboard/DashboardPage.vue'
import KLinePage from '@/views/stock/KLinePage.vue'
import TradeList from '@/views/trade/TradeList.vue'
import StockSync from '@/views/stock/StockSync.vue'
import ProfilePage from '@/views/account/ProfilePage.vue'

const router = useRouter()
const route = useRoute()

// 仅在 K 线图路由显示 docking 面板
const isKlineRoute = computed(() => route.name === 'KlineChart')
const userStore = useUserStore()
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

const currentTime = ref('')
let timer: number | null = null

// 当前激活的面板（高亮用）
const activePanelId = ref('Dashboard')

const sideItems = [
  { key: 'Dashboard', label: '仪表盘', icon: '📊' },
  { key: 'KlineChart', label: 'K线图', icon: '📈' },
  { key: 'TradeList', label: '交易记录', icon: '📋' },
  { key: 'StockSync', label: '数据同步', icon: '🔄' },
  { key: 'AccountProfile', label: '账户信息', icon: '👤' }
]

const userInitial = computed(() => (userStore.nickname || userStore.username || '?')[0]?.toUpperCase() || '?')

const showPasswordModal = ref(false)
const passwordLoading = ref(false)
const passwordForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const updateTime = () => {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, '0')
  currentTime.value = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

const handleLogout = () => {
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
    setTimeout(() => {
      userStore.clearToken()
      router.push('/login')
    }, 1000)
  } catch (error: any) {
    message.error(error.message || '密码修改失败')
  } finally {
    passwordLoading.value = false
  }
}

// 侧边栏导航点击：K 线图用 docking，其他用路由
const onSideNavClick = (key: string) => {
  if (key === 'KlineChart') {
    activatePanel(key)
    router.push('/kline')
  } else if (key === 'Dashboard') {
    router.push('/dashboard')
  } else if (key === 'TradeList') {
    router.push('/trade')
  } else if (key === 'StockSync') {
    router.push('/sync')
  } else if (key === 'AccountProfile') {
    router.push('/account')
  }
}

// dock 栏点击：已关闭则恢复，已最小化则还原，否则最小化
const onDockItemClick = (panel: { id: string; closed: boolean; minimized: boolean }) => {
  if (panel.closed) {
    reopenPanel(panel.id)
    activePanelId.value = panel.id
  } else if (panel.minimized) {
    restorePanel(panel.id)
    activePanelId.value = panel.id
  } else {
    minimizePanel(panel.id)
  }
}

onMounted(() => {
  updateTime()
  timer = window.setInterval(updateTime, 1000)
  // 注册所有面板，每个面板绑定自己的组件
  registerPanel('Dashboard', '仪表盘', DashboardPage, '📊')
  registerPanel('TradeList', '交易记录', TradeList, '📋')
  registerPanel('KlineChart', 'K线图', KLinePage, '📈')
  registerPanel('StockSync', '数据同步', StockSync, '🔄')
  registerPanel('AccountProfile', '账户信息', ProfilePage, '👤')
})

onBeforeUnmount(() => {
  if (timer !== null) clearInterval(timer)
})
</script>

<style scoped>
/* ... existing styles ... */
.terminal-root {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: var(--term-bg);
  color: var(--term-fg);
  font-family: 'JetBrains Mono', 'Fira Code', 'SF Mono', Menlo, Consolas, monospace;
}

/* ========== 顶部工具栏 ========== */
.terminal-toolbar {
  height: 44px;
  flex: 0 0 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 16px;
  background: var(--term-toolbar-bg);
  border-bottom: 1px solid var(--term-border);
  user-select: none;
}

.toolbar-left, .toolbar-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.terminal-logo {
  font-weight: bold;
  font-size: 15px;
  color: var(--term-accent);
  letter-spacing: 0.5px;
  margin-right: 16px;
}

.toolbar-time {
  font-size: 12px;
  color: var(--term-fg-muted);
  font-variant-numeric: tabular-nums;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 4px;
  transition: background 0.15s;
}

.user-trigger:hover {
  background: var(--term-hover);
}

.user-avatar {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--term-accent);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: bold;
}

.user-name {
  font-size: 13px;
  color: var(--term-fg);
}

/* ========== 三栏主体 ========== */
.terminal-body {
  flex: 1;
  display: flex;
  min-height: 0;
}

.terminal-sidebar {
  width: 180px;
  flex: 0 0 180px;
  background: var(--term-sidebar-bg);
  border-right: 1px solid var(--term-border);
  display: flex;
  flex-direction: column;
}

.sidebar-header {
  padding: 12px 16px 8px;
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: var(--term-fg-muted);
}

.sidebar-list {
  flex: 1;
  padding: 4px 8px;
  overflow-y: auto;
}

.sidebar-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  font-size: 13px;
  border-radius: 4px;
  cursor: pointer;
  color: var(--term-fg-muted);
  transition: all 0.15s;
  margin-bottom: 2px;
}

.sidebar-item:hover {
  background: var(--term-hover);
  color: var(--term-fg);
}

.sidebar-item.active {
  background: var(--term-active);
  color: var(--term-accent);
  font-weight: 600;
}

.sidebar-icon {
  font-size: 14px;
  width: 18px;
  text-align: center;
}

.terminal-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
  background: var(--term-bg);
}

/* ========== docking 面板 ========== */
.dock-panels {
  flex: 1;
  overflow: auto;
  padding: 12px;
  min-height: 0;
}

.dock-panel {
  background: var(--term-panel-bg);
  border: 1px solid var(--term-border);
  border-radius: 6px;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 400px;
}

.panel-header {
  height: 34px;
  flex: 0 0 34px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  background: var(--term-panel-header);
  border-bottom: 1px solid var(--term-border);
  border-radius: 6px 6px 0 0;
  user-select: none;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--term-fg);
  display: flex;
  align-items: center;
  gap: 8px;
}

.panel-icon {
  font-size: 14px;
}

.panel-actions {
  display: flex;
  gap: 4px;
}

.panel-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 3px;
  cursor: pointer;
  font-size: 12px;
  color: var(--term-fg-muted);
  transition: all 0.15s;
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
  padding: 16px;
  min-height: 0;
}

.empty-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--term-fg-muted);
  font-size: 15px;
}

.empty-hint {
  font-size: 12px;
  opacity: 0.6;
}

/* ========== 底部 dock 栏 ========== */
.terminal-dock {
  height: 36px;
  flex: 0 0 36px;
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 0 8px;
  background: var(--term-dock-bg);
  border-top: 1px solid var(--term-border);
  overflow-x: auto;
}

.dock-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  background: var(--term-panel-bg);
  border: 1px solid var(--term-border);
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: var(--term-fg-muted);
  white-space: nowrap;
  transition: all 0.15s;
  position: relative;
}

.dock-item:hover {
  background: var(--term-hover);
  color: var(--term-fg);
}

.dock-item.minimized {
  border-bottom: 2px solid var(--term-accent);
  color: var(--term-fg);
}

.dock-item.closed {
  opacity: 0.45;
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
  font-size: 12px;
}

.dock-title {
  max-width: 100px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.dock-close {
  width: 16px;
  height: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 2px;
  font-size: 10px;
  opacity: 0;
  transition: all 0.15s;
  margin-left: 4px;
}

.dock-item:hover .dock-close {
  opacity: 1;
}

.dock-close:hover {
  background: var(--term-danger);
  color: #fff;
}
</style>
