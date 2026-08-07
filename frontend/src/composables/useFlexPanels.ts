/**
 * 多面板 flex 布局状态管理（用于 K 线工作区）
 * 支持：拖拽分隔条调整面板大小、面板最小化/恢复
 */
import { reactive, computed, readonly } from 'vue'

export interface FlexPanelConfig {
  id: string
  title: string
  icon?: string
  minRatio: number    // 最小占比（0~1）
  maxRatio: number    // 最大占比（0~1）
  defaultRatio: number // 默认占比
}

export interface FlexPanelState {
  minimized: boolean
  ratio: number       // 当前占比（0~1）
}

export function useFlexPanels() {
  // 上排：左（自选股） / 右（K线）
  const topRow = reactive<FlexPanelState[]>([
    { minimized: false, ratio: 0.25 },
    { minimized: false, ratio: 0.75 }
  ])

  // 下排：左（持仓） / 右（交易）
  const bottomRow = reactive<FlexPanelState[]>([
    { minimized: false, ratio: 0.5 },
    { minimized: false, ratio: 0.5 }
  ])

  // 上排 vs 下排的占比（垂直分隔条）
  const vRatio = reactive({ value: 0.6 })

  // 标准化一排内的比例（使总和为 1）
  const normalize = (panels: FlexPanelState[]) => {
    const visible = panels.filter(p => !p.minimized)
    if (visible.length === 0) return
    const sum = visible.reduce((s, p) => s + p.ratio, 0)
    if (sum <= 0) return
    visible.forEach(p => { p.ratio = p.ratio / sum })
  }

  // 拖拽水平分隔条（调整同一排内两个面板）
  const resizeHorizontal = (
    row: FlexPanelState[],
    leftIdx: number,
    rightIdx: number,
    deltaPx: number,
    containerWidth: number
  ) => {
    if (containerWidth <= 0) return
    const left = row[leftIdx]
    const right = row[rightIdx]
    if (left.minimized || right.minimized) return
    const delta = deltaPx / containerWidth
    let newLeft = left.ratio + delta
    let newRight = right.ratio - delta
    // 限制：各自至少保留 15%
    const min = 0.15
    const max = 0.85
    if (newLeft < min) { newLeft = min; newRight = 1 - min }
    if (newLeft > max) { newLeft = max; newRight = 1 - max }
    left.ratio = newLeft
    right.ratio = newRight
  }

  // 拖拽垂直分隔条（调整上排 vs 下排）
  const resizeVertical = (deltaPx: number, containerHeight: number) => {
    if (containerHeight <= 0) return
    const delta = deltaPx / containerHeight
    const min = 0.3
    const max = 0.8
    vRatio.value = Math.min(max, Math.max(min, vRatio.value + delta))
  }

  const minimize = (panels: FlexPanelState[], idx: number) => {
    const p = panels[idx]
    if (!p) return
    p.minimized = true
    normalize(panels)
  }

  const restore = (panels: FlexPanelState[], idx: number, defaultRatio: number) => {
    const p = panels[idx]
    if (!p) return
    p.minimized = false
    p.ratio = defaultRatio
    normalize(panels)
  }

  const toggleMinimize = (panels: FlexPanelState[], idx: number, defaultRatio: number) => {
    if (panels[idx].minimized) restore(panels, idx, defaultRatio)
    else minimize(panels, idx)
  }

  // 计算后的 CSS flex 值
  const topLeftFlex = computed(() => topRow[0].minimized ? 0 : topRow[0].ratio)
  const topRightFlex = computed(() => topRow[1].minimized ? 0 : topRow[1].ratio)
  const bottomLeftFlex = computed(() => bottomRow[0].minimized ? 0 : bottomRow[0].ratio)
  const bottomRightFlex = computed(() => bottomRow[1].minimized ? 0 : bottomRow[1].ratio)

  return {
    topRow,
    bottomRow,
    vRatio: readonly(vRatio),
    topLeftFlex,
    topRightFlex,
    bottomLeftFlex,
    bottomRightFlex,
    resizeHorizontal,
    resizeVertical,
    minimize,
    restore,
    toggleMinimize
  }
}
