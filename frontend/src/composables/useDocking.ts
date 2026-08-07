/**
 * 轻量 docking 布局状态管理
 * 管理面板的最小化/关闭/激活，以及底部 dock 栏的顺序
 */
import { Component, reactive, computed, readonly, markRaw } from 'vue'

export interface DockPanel {
  id: string
  title: string
  icon?: string
  component: Component
  minimized: boolean
  closed: boolean
  order: number
}

const panels = reactive<Record<string, DockPanel>>({})
const dockOrder = reactive<string[]>([])

export function useDocking() {
  // 当前可见（未关闭、未最小化）的面板，按 dockOrder 逆序（最近激活的在最上面）
  const visiblePanels = computed(() =>
    dockOrder
      .map(id => panels[id])
      .filter(p => p && !p.closed && !p.minimized)
      .reverse()
  )

  // 底部 dock 栏显示的面板（所有已注册，包含已关闭的，用于恢复）
  const allActivePanels = computed(() =>
    dockOrder
      .map(id => panels[id])
      .filter(p => !!p)
  )

  const registerPanel = (id: string, title: string, component: Component, icon?: string) => {
    if (panels[id]) return
    const order = Object.keys(panels).length
    panels[id] = { id, title, icon, component: markRaw(component), minimized: false, closed: false, order }
    dockOrder.push(id)
  }

  const unregisterPanel = (id: string) => {
    delete panels[id]
    const idx = dockOrder.indexOf(id)
    if (idx >= 0) dockOrder.splice(idx, 1)
  }

  const activatePanel = (id: string) => {
    // 打开（如果关闭）+ 还原（如果最小化）+ 提到最前
    const p = panels[id]
    if (!p) return
    if (p.closed) p.closed = false
    if (p.minimized) p.minimized = false
    const idx = dockOrder.indexOf(id)
    if (idx >= 0) {
      dockOrder.splice(idx, 1)
      dockOrder.push(id)
    }
  }

  const minimizePanel = (id: string) => {
    if (panels[id]) panels[id].minimized = true
  }

  const restorePanel = (id: string) => {
    if (panels[id]) {
      panels[id].minimized = false
      const idx = dockOrder.indexOf(id)
      if (idx >= 0) {
        dockOrder.splice(idx, 1)
        dockOrder.push(id)
      }
    }
  }

  const closePanel = (id: string) => {
    if (panels[id]) panels[id].closed = true
  }

  const reopenPanel = (id: string) => {
    if (panels[id]) {
      panels[id].closed = false
      panels[id].minimized = false
      const idx = dockOrder.indexOf(id)
      if (idx >= 0) {
        dockOrder.splice(idx, 1)
        dockOrder.push(id)
      }
    }
  }

  const toggleMinimize = (id: string) => {
    if (!panels[id]) return
    if (panels[id].minimized) restorePanel(id)
    else minimizePanel(id)
  }

  return {
    panels: readonly(panels),
    visiblePanels,
    allActivePanels,
    registerPanel,
    unregisterPanel,
    activatePanel,
    minimizePanel,
    restorePanel,
    closePanel,
    reopenPanel,
    toggleMinimize
  }
}
