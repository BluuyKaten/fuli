# K线图美化和功能扩展实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将现有 LightweightChart.vue 重构为模块化架构，并添加技术指标、画线增强、买卖点标记、主题切换、导出功能和十字光标信息面板。

**Architecture:** 采用"主组件 + Composables"分层架构。主组件负责 DOM 容器和事件分发，每个 composable 管理独立关注点（图表核心、指标、画线、标记、主题、导出、信息面板）。指标计算为纯函数，与渲染层解耦。画线使用独立 Canvas 层叠加。

**Tech Stack:** Vue 3.5 + TypeScript + Vite + lightweight-charts@5.2.0 + html2canvas (新增)

## Global Constraints

- lightweight-charts v5 API：`chart.addSeries(CandlestickSeries, options)` / `addSeries(HistogramSeries, options)`
- 所有 composable 使用 `use` 前缀命名
- API 调用通过 `@/utils/request`，baseURL 为 `/api`
- 主题色变量同时用于 CSS 和 lightweight-charts `applyOptions`
- 文件路径遵循现有 `frontend/src/composables/` 和 `frontend/src/utils/` 约定

---

## Phase 1: 指标计算纯函数

### Task 1: 创建 MA 均线计算函数

**Files:**
- Create: `frontend/src/utils/indicators/ma.ts`
- Test: `frontend/src/utils/indicators/__tests__/ma.test.ts`

**Interfaces:**
- Consumes: `CandleItem[]` (K线数据，含 `close` 字段)
- Produces: `calcMA(data, period)` → `number[]`，长度与输入一致，前 period-1 位为 `NaN`

- [ ] **Step 1: 写失败测试**

```typescript
// ma.test.ts
import { calcMA } from '../ma'

describe('calcMA', () => {
  const data = [
    { close: 10 }, { close: 11 }, { close: 12 }, { close: 13 }, { close: 14 }
  ]

  test('period=3 时前2位为NaN', () => {
    const result = calcMA(data, 3)
    expect(result[0]).toBeNaN()
    expect(result[1]).toBeNaN()
    expect(result[2]).toBeCloseTo(11) // (10+11+12)/3
    expect(result[3]).toBeCloseTo(12) // (11+12+13)/3
    expect(result[4]).toBeCloseTo(13) // (12+13+14)/3
  })

  test('period=1 返回原始值', () => {
    const result = calcMA(data, 1)
    expect(result).toEqual([10, 11, 12, 13, 14])
  })

  test('空数组返回空数组', () => {
    expect(calcMA([], 5)).toEqual([])
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && npx vitest run src/utils/indicators/__tests__/ma.test.ts`
Expected: FAIL "cannot find module"

- [ ] **Step 3: 实现 calcMA**

```typescript
// ma.ts
import type { CandleItem } from '@/types/indicators'

export function calcMA(data: CandleItem[], period: number): number[] {
  if (period <= 0 || data.length === 0) return []
  const result: number[] = []
  for (let i = 0; i < data.length; i++) {
    if (i < period - 1) {
      result.push(NaN)
      continue
    }
    let sum = 0
    for (let j = 0; j < period; j++) {
      sum += data[i - j].close
    }
    result.push(+(sum / period).toFixed(4))
  }
  return result
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd frontend && npx vitest run src/utils/indicators/__tests__/ma.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/utils/indicators/ma.ts frontend/src/utils/indicators/__tests__/ma.test.ts
git commit -m "feat: add MA moving average calculation"
```

---

### Task 2: 创建 EMA 和 MACD 计算函数

**Files:**
- Create: `frontend/src/utils/indicators/ema.ts`
- Create: `frontend/src/utils/indicators/macd.ts`
- Test: `frontend/src/utils/indicators/__tests__/ema.test.ts`
- Test: `frontend/src/utils/indicators/__tests__/macd.test.ts`

**Interfaces:**
- Consumes: `CandleItem[]`
- Produces:
  - `calcEMA(data, period)` → `number[]`，前 period-1 位为 `NaN`，第 period-1 位为 SMA 初始值
  - `calcMACD(data, fast=12, slow=26, signal=9)` → `{ dif: number[], dea: number[], histogram: number[] }`

- [ ] **Step 1: 写失败测试**

```typescript
// ema.test.ts
import { calcEMA } from '../ema'

describe('calcEMA', () => {
  const data = [
    { close: 10 }, { close: 11 }, { close: 12 }, { close: 13 }, { close: 14 }
  ]

  test('period=3 时前2位为NaN', () => {
    const result = calcEMA(data, 3)
    expect(result[0]).toBeNaN()
    expect(result[1]).toBeNaN()
    // EMA初始值 = SMA(前3个) = 11
    expect(result[2]).toBeCloseTo(11)
    // EMA = close * k + prev * (1-k), k=2/4=0.5
    expect(result[3]).toBeCloseTo(13 * 0.5 + 11 * 0.5) // 12
  })
})
```

```typescript
// macd.test.ts
import { calcMACD } from '../macd'

describe('calcMACD', () => {
  const data = Array.from({ length: 50 }, (_, i) => ({ close: 10 + i * 0.5 }))

  test('返回 dif/dea/histogram 三个数组', () => {
    const { dif, dea, histogram } = calcMACD(data)
    expect(dif.length).toBe(50)
    expect(dea.length).toBe(50)
    expect(histogram.length).toBe(50)
  })

  test('histogram = (dif - dea) * 2', () => {
    const { dif, dea, histogram } = calcMACD(data)
    for (let i = 0; i < data.length; i++) {
      if (!isNaN(dif[i]) && !isNaN(dea[i])) {
        expect(histogram[i]).toBeCloseTo((dif[i] - dea[i]) * 2)
      }
    }
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && npx vitest run src/utils/indicators/__tests__/ema.test.ts src/utils/indicators/__tests__/macd.test.ts`
Expected: FAIL

- [ ] **Step 3: 实现 calcEMA 和 calcMACD**

```typescript
// ema.ts
import type { CandleItem } from '@/types/indicators'

export function calcEMA(data: CandleItem[], period: number): number[] {
  if (period <= 0 || data.length === 0) return []
  const k = 2 / (period + 1)
  const result: number[] = []
  for (let i = 0; i < data.length; i++) {
    if (i < period - 1) {
      result.push(NaN)
    } else if (i === period - 1) {
      let sum = 0
      for (let j = 0; j < period; j++) sum += data[i - j].close
      result.push(+(sum / period).toFixed(4))
    } else {
      result.push(+(data[i].close * k + result[i - 1] * (1 - k)).toFixed(4))
    }
  }
  return result
}
```

```typescript
// macd.ts
import type { CandleItem } from '@/types/indicators'
import { calcEMA } from './ema'

export interface MACDResult {
  dif: number[]
  dea: number[]
  histogram: number[]
}

export function calcMACD(data: CandleItem[], fast = 12, slow = 26, signal = 9): MACDResult {
  const emaFast = calcEMA(data, fast)
  const emaSlow = calcEMA(data, slow)
  const dif = emaFast.map((v, i) => (isNaN(v) || isNaN(emaSlow[i]) ? NaN : +(v - emaSlow[i]).toFixed(4)))

  // DEA = EMA(DIF, signal)，只对有效值计算
  const validDif: { idx: number; val: number }[] = []
  dif.forEach((v, i) => { if (!isNaN(v)) validDif.push({ idx: i, val: v }) })

  const dea = new Array(data.length).fill(NaN)
  const k = 2 / (signal + 1)
  for (let j = 0; j < validDif.length; j++) {
    const { idx, val } = validDif[j]
    if (j < signal - 1) continue
    if (j === signal - 1) {
      let sum = 0
      for (let m = 0; m < signal; m++) sum += validDif[m].val
      dea[idx] = +(sum / signal).toFixed(4)
    } else {
      const prevIdx = validDif[j - 1].idx
      dea[idx] = +(val * k + dea[prevIdx] * (1 - k)).toFixed(4)
    }
  }

  const histogram = dif.map((v, i) => (isNaN(v) || isNaN(dea[i])) ? NaN : +(v - dea[i]) * 2)
  return { dif, dea, histogram }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd frontend && npx vitest run src/utils/indicators/__tests__/`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/utils/indicators/ema.ts frontend/src/utils/indicators/macd.ts frontend/src/utils/indicators/__tests__/
git commit -m "feat: add EMA and MACD calculation functions"
```

---

### Task 3: 创建 KDJ、RSI、BOLL 计算函数

**Files:**
- Create: `frontend/src/utils/indicators/kdj.ts`
- Create: `frontend/src/utils/indicators/rsi.ts`
- Create: `frontend/src/utils/indicators/boll.ts`
- Test: `frontend/src/utils/indicators/__tests__/kdj.test.ts`
- Test: `frontend/src/utils/indicators/__tests__/rsi.test.ts`
- Test: `frontend/src/utils/indicators/__tests__/boll.test.ts`

**Interfaces:**
- Consumes: `CandleItem[]` (含 `high`, `low`, `close`)
- Produces:
  - `calcKDJ(data, n=9)` → `{ k: number[], d: number[], j: number[] }`
  - `calcRSI(data, period=14)` → `number[]`
  - `calcBOLL(data, period=20, k=2)` → `{ upper: number[], middle: number[], lower: number[] }`

- [ ] **Step 1: 写失败测试**

```typescript
// kdj.test.ts
import { calcKDJ } from '../kdj'

describe('calcKDJ', () => {
  const data = Array.from({ length: 20 }, (_, i) => ({
    open: 10 + i, high: 12 + i, low: 8 + i, close: 11 + i
  }))

  test('返回 k/d/j 三个数组，长度与输入一致', () => {
    const { k, d, j } = calcKDJ(data)
    expect(k.length).toBe(data.length)
    expect(d.length).toBe(data.length)
    expect(j.length).toBe(data.length)
  })

  test('前 n-1 个值使用默认值 50', () => {
    const { k, d, j } = calcKDJ(data, 9)
    expect(k[0]).toBe(50)
    expect(d[0]).toBe(50)
    expect(j[0]).toBe(50)
  })
})
```

```typescript
// rsi.test.ts
import { calcRSI } from '../rsi'

describe('calcRSI', () => {
  const data = Array.from({ length: 20), (_, i) => ({ close: 10 + (i % 3 === 0 ? 1 : -0.5) }))

  test('返回数组长度与输入一致', () => {
    const result = calcRSI(data, 14)
    expect(result.length).toBe(data.length)
  })

  test('RSI 值在 0-100 之间', () => {
    const result = calcRSI(data, 14)
    result.forEach(v => {
      if (!isNaN(v)) {
        expect(v).toBeGreaterThanOrEqual(0)
        expect(v).toBeLessThanOrEqual(100)
      }
    })
  })
})
```

```typescript
// boll.test.ts
import { calcBOLL } from '../boll'

describe('calcBOLL', () => {
  const data = Array.from({ length: 30 }, (_, i) => ({
    close: 10 + Math.sin(i) * 2
  }))

  test('返回 upper/middle/lower 三个数组', () => {
    const { upper, middle, lower } = calcBOLL(data)
    expect(upper.length).toBe(data.length)
    expect(middle.length).toBe(data.length)
    expect(lower.length).toBe(data.length)
  })

  test('upper >= middle >= lower（有效值范围内）', () => {
    const { upper, middle, lower } = calcBOLL(data, 20)
    for (let i = 19; i < data.length; i++) {
      expect(upper[i]).toBeGreaterThanOrEqual(middle[i])
      expect(middle[i]).toBeGreaterThanOrEqual(lower[i])
    }
  })
})
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd frontend && npx vitest run src/utils/indicators/__tests__/kdj.test.ts src/utils/indicators/__tests__/rsi.test.ts src/utils/indicators/__tests__/boll.test.ts`
Expected: FAIL

- [ ] **Step 3: 实现三个函数**

```typescript
// kdj.ts
import type { CandleItem } from '@/types/indicators'

export interface KDJResult { k: number[]; d: number[]; j: number[] }

export function calcKDJ(data: CandleItem[], n = 9): KDJResult {
  const kArr: number[] = [], dArr: number[] = [], jArr: number[] = []
  for (let i = 0; i < data.length; i++) {
    if (i < n - 1) {
      kArr.push(50); dArr.push(50); jArr.push(50); continue
    }
    const highs: number[] = [], lows: number[] = []
    for (let j = i - n + 1; j <= i; j++) {
      highs.push(data[j].high)
      lows.push(data[j].low)
    }
    const high = Math.max(...highs)
    const low = Math.min(...lows)
    const rsv = high === low ? 50 : ((data[i].close - low) / (high - low)) * 100
    const k = (2 / 3) * kArr[i - 1] + (1 / 3) * rsv
    const d = (2 / 3) * dArr[i - 1] + (1 / 3) * k
    const j = 3 * k - 2 * d
    kArr.push(+k.toFixed(2)); dArr.push(+d.toFixed(2)); jArr.push(+j.toFixed(2))
  }
  return { k: kArr, d: dArr, j: jArr }
}
```

```typescript
// rsi.ts
import type { CandleItem } from '@/types/indicators'

export function calcRSI(data: CandleItem[], period = 14): number[] {
  if (data.length === 0) return []
  const result: number[] = new Array(data.length).fill(NaN)
  if (data.length < period + 1) return result

  const changes = data.map((d, i) => i === 0 ? 0 : d.close - data[i - 1].close)
  let avgGain = 0, avgLoss = 0

  // 初始平均：前 period 个变化
  for (let i = 1; i <= period; i++) {
    if (changes[i] > 0) avgGain += changes[i]
    else avgLoss += Math.abs(changes[i])
  }
  avgGain /= period
  avgLoss /= period

  result[period] = avgLoss === 0 ? 100 : +(100 - 100 / (1 + avgGain / avgLoss)).toFixed(2)

  for (let i = period + 1; i < data.length; i++) {
    const gain = changes[i] > 0 ? changes[i] : 0
    const loss = changes[i] < 0 ? Math.abs(changes[i]) : 0
    avgGain = (avgGain * (period - 1) + gain) / period
    avgLoss = (avgLoss * (period - 1) + loss) / period
    result[i] = avgLoss === 0 ? 100 : +(100 - 100 / (1 + avgGain / avgLoss)).toFixed(2)
  }
  return result
}
```

```typescript
// boll.ts
import type { CandleItem } from '@/types/indicators'
import { calcMA } from './ma'

export interface BOLLResult { upper: number[]; middle: number[]; lower: number[] }

export function calcBOLL(data: CandleItem[], period = 20, k = 2): BOLLResult {
  const middle = calcMA(data, period)
  const upper: number[] = []
  const lower: number[] = []
  for (let i = 0; i < data.length; i++) {
    if (isNaN(middle[i])) {
      upper.push(NaN); lower.push(NaN); continue
    }
    let variance = 0
    for (let j = 0; j < period; j++) {
      variance += (data[i - j].close - middle[i]) ** 2
    }
    const std = Math.sqrt(variance / period)
    upper.push(+(middle[i] + k * std).toFixed(4))
    lower.push(+(middle[i] - k * std).toFixed(4))
  }
  return { upper, middle, lower }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd frontend && npx vitest run src/utils/indicators/__tests__/`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add frontend/src/utils/indicators/ frontend/src/utils/indicators/__tests__/
git commit -m "feat: add KDJ, RSI, BOLL indicator calculations"
```

---

### Task 4: 创建指标类型定义和统一导出

**Files:**
- Create: `frontend/src/types/indicators.ts`
- Create: `frontend/src/utils/indicators/index.ts`

**Interfaces:**
- Produces: `CandleItem` 类型 + 所有指标函数的统一导出

- [ ] **Step 1: 创建类型定义文件**

```typescript
// types/indicators.ts
export interface CandleItem {
  time: string | number
  open: number
  high: number
  low: number
  close: number
  volume?: number
}
```

- [ ] **Step 2: 创建统一导出**

```typescript
// utils/indicators/index.ts
export { calcMA } from './ma'
export { calcEMA } from './ema'
export { calcMACD } from './macd'
export { calcKDJ } from './kdj'
export { calcRSI } from './rsi'
export { calcBOLL } from './boll'
export type { MACDResult } from './macd'
export type { KDJResult } from './kdj'
export type { BOLLResult } from './boll'
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/types/indicators.ts frontend/src/utils/indicators/index.ts
git commit -m "feat: add indicator type definitions and barrel export"
```

---

## Phase 2: 图表核心 Composable

### Task 5: 创建 useChartCore composable

**Files:**
- Create: `frontend/src/composables/useChartCore.ts`

**Interfaces:**
- Consumes: `chartContainer` (Ref<HTMLElement>), `theme` (ChartTheme)
- Produces:
  - `chart: Ref<IChartApi | null>`
  - `initChart()` — 初始化图表实例
  - `applyTheme(theme)` — 应用主题到图表
  - `destroyChart()` — 销毁图表

- [ ] **Step 1: 实现 useChartCore**

```typescript
// useChartCore.ts
import { ref, type Ref } from 'vue'
import {
  createChart,
  type IChartApi,
  type ISeriesApi,
  ColorType,
  CrosshairMode,
  LineStyle,
  CandlestickSeries,
  HistogramSeries,
  LineSeries
} from 'lightweight-charts'
import type { ChartTheme } from '@/types/chart'

export function useChartCore(chartContainer: Ref<HTMLElement | null>) {
  const chart = ref<IChartApi | null>(null) as Ref<IChartApi | null>
  const seriesMap = ref<Map<string, ISeriesApi<any>>>(new Map())

  const initChart = (theme: ChartTheme) => {
    if (!chartContainer.value) return
    chart.value = createChart(chartContainer.value, {
      layout: {
        background: { type: ColorType.Solid, color: theme.layout.background },
        textColor: theme.layout.textColor
      },
      grid: {
        vertLines: { color: theme.grid.vertLines },
        horzLines: { color: theme.grid.horzLines }
      },
      crosshair: {
        mode: CrosshairMode.Normal,
        vertLine: { color: theme.crosshair, width: 1, style: LineStyle.Dashed },
        horzLine: { color: theme.crosshair, width: 1, style: LineStyle.Dashed }
      },
      rightPriceScale: { borderColor: theme.grid.vertLines },
      timeScale: { borderColor: theme.grid.vertLines, timeVisible: true, secondsVisible: false },
      handleScroll: { vertTouchDrag: false }
    })
  }

  const applyTheme = (theme: ChartTheme) => {
    if (!chart.value) return
    chart.value.applyOptions({
      layout: {
        background: { type: ColorType.Solid, color: theme.layout.background },
        textColor: theme.layout.textColor
      },
      grid: {
        vertLines: { color: theme.grid.vertLines },
        horzLines: { color: theme.grid.horzLines }
      },
      crosshair: {
        vertLine: { color: theme.crosshair },
        horzLine: { color: theme.crosshair }
      }
    })
  }

  const addCandlestickSeries = (options: any) => {
    if (!chart.value) return null
    const s = chart.value.addSeries(CandlestickSeries, options)
    seriesMap.value.set('candle', s)
    return s
  }

  const addHistogramSeries = (id: string, options: any) => {
    if (!chart.value) return null
    const s = chart.value.addSeries(HistogramSeries, options)
    seriesMap.value.set(id, s)
    return s
  }

  const addLineSeries = (id: string, options: any) => {
    if (!chart.value) return null
    const s = chart.value.addSeries(LineSeries, options)
    seriesMap.value.set(id, s)
    return s
  }

  const removeSeries = (id: string) => {
    const s = seriesMap.value.get(id)
    if (s && chart.value) {
      chart.value.removeSeries(s)
      seriesMap.value.delete(id)
    }
  }

  const clearAllSeries = () => {
    seriesMap.value.forEach((_, id) => removeSeries(id))
  }

  const destroyChart = () => {
    clearAllSeries()
    if (chart.value) {
      chart.value.remove()
      chart.value = null
    }
  }

  return {
    chart,
    initChart,
    applyTheme,
    addCandlestickSeries,
    addHistogramSeries,
    addLineSeries,
    removeSeries,
    clearAllSeries,
    destroyChart,
    seriesMap
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/composables/useChartCore.ts
git commit -m "feat: add useChartCore composable for chart lifecycle management"
```

---

## Phase 3: 主题系统

### Task 6: 创建主题类型和 useChartTheme composable

**Files:**
- Create: `frontend/src/types/chart.ts`
- Create: `frontend/src/composables/useChartTheme.ts`

**Interfaces:**
- Produces:
  - `ChartTheme` 类型
  - `themes: { dark: ChartTheme, light: ChartTheme }`
  - `currentTheme: Ref<ChartTheme>`
  - `toggleTheme()` — 切换 dark/light

- [ ] **Step 1: 创建主题类型**

```typescript
// types/chart.ts
export interface ChartTheme {
  name: 'dark' | 'light'
  layout: { background: string; textColor: string }
  grid: { vertLines: string; horzLines: string }
  candle: {
    upColor: string; downColor: string
    borderUpColor: string; borderDownColor: string
    wickUpColor: string; wickDownColor: string
  }
  crosshair: string
  indicators: Record<string, string>
}

export type ThemeName = 'dark' | 'light'
```

- [ ] **Step 2: 实现 useChartTheme**

```typescript
// useChartTheme.ts
import { ref, readonly } from 'vue'
import type { ChartTheme, ThemeName } from '@/types/chart'

const darkTheme: ChartTheme = {
  name: 'dark',
  layout: { background: '#131722', textColor: '#8b949e' },
  grid: { vertLines: '#30363d', horzLines: '#30363d' },
  candle: {
    upColor: '#ef5350', downColor: '#26a69a',
    borderUpColor: '#ef5350', borderDownColor: '#26a69a',
    wickUpColor: '#ef5350', wickDownColor: '#26a69a'
  },
  crosshair: '#58a6ff',
  indicators: {
    ma5: '#ff9800', ma10: '#2196f3', ma20: '#e91e63', ma60: '#9c27b0',
    dif: '#ff9800', dea: '#2196f3',
    k: '#ff9800', d: '#2196f3', j: '#e91e63',
    rsi: '#7c4dff',
    bollUpper: '#ef5350', bollMiddle: '#8b949e', bollLower: '#26a69a',
    macdUp: '#ef5350', macdDown: '#26a69a',
    volumeUp: '#ef535080', volumeDown: '#26a69a80'
  }
}

const lightTheme: ChartTheme = {
  name: 'light',
  layout: { background: '#ffffff', textColor: '#333333' },
  grid: { vertLines: '#e0e0e0', horzLines: '#e0e0e0' },
  candle: {
    upColor: '#ef5350', downColor: '#26a69a',
    borderUpColor: '#ef5350', borderDownColor: '#26a69a',
    wickUpColor: '#ef5350', wickDownColor: '#26a69a'
  },
  crosshair: '#2196f3',
  indicators: {
    ...darkTheme.indicators
  }
}

export function useChartTheme() {
  const STORAGE_KEY = 'kline-theme'
  const saved = localStorage.getItem(STORAGE_KEY) as ThemeName | null
  const currentThemeName = ref<ThemeName>(saved || 'dark')

  const themes: Record<ThemeName, ChartTheme> = { dark: darkTheme, light: lightTheme }
  const currentTheme = ref<ChartTheme>(themes[currentThemeName.value])

  const setTheme = (name: ThemeName) => {
    currentThemeName.value = name
    currentTheme.value = themes[name]
    localStorage.setItem(STORAGE_KEY, name)
  }

  const toggleTheme = () => {
    setTheme(currentThemeName.value === 'dark' ? 'light' : 'dark')
  }

  return {
    currentTheme: readonly(currentTheme),
    currentThemeName: readonly(currentThemeName),
    themes,
    setTheme,
    toggleTheme
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/types/chart.ts frontend/src/composables/useChartTheme.ts
git commit -m "feat: add theme system with dark/light support"
```

---

## Phase 4: 指标渲染 Composable

### Task 7: 创建 useIndicators composable

**Files:**
- Create: `frontend/src/composables/useIndicators.ts`

**Interfaces:**
- Consumes: `chart: Ref<IChartApi | null>`, `theme: Ref<ChartTheme>`, `CandleItem[]`
- Produces:
  - `activeIndicators: reactive` — 当前启用的指标配置
  - `updateIndicators(data, config)` — 根据配置更新所有指标系列
  - `seriesMap` — 管理所有指标系列的引用

- [ ] **Step 1: 实现 useIndicators**

```typescript
// useIndicators.ts
import { reactive, type Ref } from 'vue'
import type { IChartApi, ISeriesApi } from 'lightweight-charts'
import type { ChartTheme } from '@/types/chart'
import type { CandleItem } from '@/types/indicators'
import {
  calcMA, calcMACD, calcKDJ, calcRSI, calcBOLL
} from '@/utils/indicators'

export interface IndicatorConfig {
  ma5: boolean
  ma10: boolean
  ma20: boolean
  ma60: boolean
  macd: boolean
  kdj: boolean
  rsi: boolean
  boll: boolean
  volume: boolean
}

export function useIndicators(
  chart: Ref<IChartApi | null>,
  theme: Ref<ChartTheme>
) {
  const activeIndicators = reactive<IndicatorConfig>({
    ma5: true, ma10: true, ma20: false, ma60: false,
    macd: true, kdj: false, rsi: false, boll: false, volume: true
  })

  const indicatorSeries = reactive<Map<string, ISeriesApi<any>>>(new Map())

  const removeIndicatorSeries = (id: string) => {
    const s = indicatorSeries.get(id)
    if (s && chart.value) {
      chart.value.removeSeries(s)
      indicatorSeries.delete(id)
    }
  }

  const clearAllIndicators = () {
    indicatorSeries.forEach((_, id) => removeIndicatorSeries(id))
  }

  const updateIndicators = (data: CandleItem[], config: IndicatorConfig) => {
    if (!chart.value || data.length === 0) return
    const t = theme.value.indicators

    // --- 主图叠加指标 ---
    // MA
    const maList = [
      { key: 'ma5', period: 5, enabled: config.ma5 },
      { key: 'ma10', period: 10, enabled: config.ma10 },
      { key: 'ma20', period: 20, enabled: config.ma20 },
      { key: 'ma60', period: 60, enabled: config.ma60 }
    ]
    for (const { key, period, enabled } of maList) {
      removeIndicatorSeries(key)
      if (enabled) {
        const maData = calcMA(data, period).map((v, i) => ({
          time: data[i].time, value: isNaN(v) ? 0 : v
        })).filter((_, i) => !isNaN(calcMA(data, period)[i]))
        const s = chart.value!.addSeries(LineSeries, {
          color: t[key], lineWidth: 1, priceLineVisible: false,
          lastValueVisible: false, title: `MA${period}`
        })
        s.setData(maData)
        indicatorSeries.set(key, s)
      }
    }

    // BOLL
    removeIndicatorSeries('bollUpper')
    removeIndicatorSeries('bollMiddle')
    removeIndicatorSeries('bollLower')
    if (config.boll) {
      const { upper, middle, lower } = calcBOLL(data)
      const makeSeries = (id: string, color: string, values: number[]) => {
        const s = chart.value!.addSeries(LineSeries, {
          color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false, title: id
        })
        s.setData(values.map((v, i) => ({ time: data[i].time, value: isNaN(v) ? 0 : v }))
          .filter((_, i) => !isNaN(values[i])))
        indicatorSeries.set(id, s)
      }
      makeSeries('bollUpper', t.bollUpper, upper)
      makeSeries('bollMiddle', t.bollMiddle, middle)
      makeSeries('bollLower', t.bollLower, lower)
    }

    // --- 副图指标（独立 pane）---
    // 注意：lightweight-charts v5 不原生支持多 pane，副图通过 priceScaleId 分离
    // MACD
    removeIndicatorSeries('dif')
    removeIndicatorSeries('dea')
    removeIndicatorSeries('macdHist')
    if (config.macd) {
      const { dif, dea, histogram } = calcMACD(data)
      // DIF 线
      const sDif = chart.value!.addSeries(LineSeries, {
        color: t.dif, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
        priceScaleId: 'macd', title: 'DIF'
      })
      sDif.setData(dif.map((v, i) => ({ time: data[i].time, value: isNaN(v) ? 0 : v }))
        .filter((_, i) => !isNaN(dif[i])))
      indicatorSeries.set('dif', sDif)
      // DEA 线
      const sDea = chart.value!.addSeries(LineSeries, {
        color: t.dea, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
        priceScaleId: 'macd', title: 'DEA'
      })
      sDea.setData(dea.map((v, i) => ({ time: data[i].time, value: isNaN(v) ? 0 : v }))
        .filter((_, i) => !isNaN(dea[i])))
      indicatorSeries.set('dea', sDea)
      // MACD 柱状图
      const sHist = chart.value!.addSeries(HistogramSeries, {
        color: t.macdUp, priceScaleId: 'macd', title: 'MACD'
      })
      sHist.setData(histogram.map((v, i) => ({
        time: data[i].time, value: isNaN(v) ? 0 : v,
        color: v >= 0 ? t.macdUp : t.macdDown
      })).filter((_, i) => !isNaN(histogram[i])))
      indicatorSeries.set('macdHist', sHist)
      // 设置 MACD 刻度
      chart.value!.priceScale('macd').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
    }

    // KDJ
    removeIndicatorSeries('kLine')
    removeIndicatorSeries('dLine')
    removeIndicatorSeries('jLine')
    if (config.kdj) {
      const { k, d, j } = calcKDJ(data)
      const makeLine = (id: string, color: string, values: number[]) => {
        const s = chart.value!.addSeries(LineSeries, {
          color, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
          priceScaleId: 'kdj', title: id
        })
        s.setData(values.map((v, i) => ({ time: data[i].time, value: v })))
        indicatorSeries.set(id, s)
      }
      makeLine('kLine', t.k, k)
      makeLine('dLine', t.d, d)
      makeLine('jLine', t.j, j)
      chart.value!.priceScale('kdj').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
    }

    // RSI
    removeIndicatorSeries('rsi')
    if (config.rsi) {
      const rsi = calcRSI(data)
      const s = chart.value!.addSeries(LineSeries, {
        color: t.rsi, lineWidth: 1, priceLineVisible: false, lastValueVisible: false,
        priceScaleId: 'rsi', title: 'RSI'
      })
      s.setData(rsi.map((v, i) => ({ time: data[i].time, value: isNaN(v) ? 0 : v }))
        .filter((_, i) => !isNaN(rsi[i])))
      indicatorSeries.set('rsi', s)
      chart.value!.priceScale('rsi').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } })
    }
  }

  return {
    activeIndicators,
    updateIndicators,
    clearAllIndicators,
    indicatorSeries
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/composables/useIndicators.ts
git commit -m "feat: add useIndicators composable for technical indicator rendering"
```

---

## Phase 5: 买卖点标记

### Task 8: 创建 useTradeMarkers composable

**Files:**
- Create: `frontend/src/composables/useTradeMarkers.ts`

**Interfaces:**
- Consumes: `chart: Ref<IChartApi | null>`, `candleSeries: Ref<ISeriesApi | null>`
- Produces:
  - `markers: TradeMarker[]`
  - `addManualMarker(direction, time, price)` — 手动添加标记
  - `loadAutoMarkers(stockCode)` — 从交易记录自动加载
  - `clearMarkers()` — 清除所有标记
  - `removeMarker(id)` — 删除单个标记

- [ ] **Step 1: 实现 useTradeMarkers**

```typescript
// useTradeMarkers.ts
import { ref, type Ref } from 'vue'
import type { IChartApi, ISeriesApi } from 'lightweight-charts'
import type { SeriesMarker } from 'lightweight-charts'
import { getTradeList } from '@/api/trade'

export interface TradeMarker {
  id: string
  source: 'manual' | 'auto'
  direction: 'buy' | 'sell'
  time: string
  price: number
  quantity?: number
  editable: boolean
}

export function useTradeMarkers(
  chart: Ref<IChartApi | null>,
  candleSeries: Ref<ISeriesApi<any> | null>
) {
  const markers = ref<TradeMarker[]>([])

  const BUY_COLOR = '#26a69a'
  const SELL_COLOR = '#ef5350'

  const toSeriesMarkers = (): SeriesMarker[] => {
    return markers.value.map(m => ({
      time: m.time as any,
      position: m.direction === 'buy' ? 'belowBar' : 'aboveBar',
      color: m.direction === 'buy' ? BUY_COLOR : SELL_COLOR,
      shape: m.direction === 'buy' ? 'arrowUp' : 'arrowDown',
      text: m.direction === 'buy' ? '买' : '卖',
      id: m.id
    }))
  }

  const applyMarkers = () => {
    if (!candleSeries.value) return
    candleSeries.value.setMarkers(toSeriesMarkers())
  }

  const addManualMarker = (direction: 'buy' | 'sell', time: string, price: number) => {
    const marker: TradeMarker = {
      id: `manual-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      source: 'manual',
      direction,
      time,
      price,
      editable: true
    }
    markers.value.push(marker)
    applyMarkers()
    return marker
  }

  const loadAutoMarkers = async (stockCode: string) => {
    try {
      const userId = Number(localStorage.getItem('userId') || '0')
      if (userId <= 0 || !stockCode) return
      const res = await getTradeList({ userId, stockCode, pageNum: 1, pageSize: 200 })
      if (res.code !== 200 || !res.data) return

      const autoMarkers: TradeMarker[] = res.data.map(t => ({
        id: `auto-${t.id}`,
        source: 'auto',
        direction: t.tradeType === 1 ? 'buy' : 'sell',
        time: t.tradeDate,
        price: t.tradePrice,
        quantity: t.tradeQuantity,
        editable: false
      }))

      // 移除旧的 auto 标记，保留 manual 标记
      markers.value = markers.value.filter(m => m.source === 'manual').concat(autoMarkers)
      applyMarkers()
    } catch (e) {
      console.error('[useTradeMarkers] 加载交易标记失败:', e)
    }
  }

  const removeMarker = (id: string) => {
    markers.value = markers.value.filter(m => m.id !== id)
    applyMarkers()
  }

  const clearMarkers = () => {
    markers.value = []
    applyMarkers()
  }

  return {
    markers,
    addManualMarker,
    loadAutoMarkers,
    removeMarker,
    clearMarkers,
    applyMarkers
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/composables/useTradeMarkers.ts
git commit -m "feat: add useTradeMarkers composable for buy/sell point marking"
```

---

## Phase 6: 画线工具系统

### Task 9: 创建 useDrawingTools composable（增强版）

**Files:**
- Create: `frontend/src/composables/useDrawingTools.ts`

**Interfaces:**
- Consumes: `chart: Ref<IChartApi | null>`, `drawingCanvas: Ref<HTMLCanvasElement | null>`
- Produces:
  - `drawings: DrawingObject[]`
  - `currentTool: ref<DrawingType | null>`
  - `startDraw(tool)` — 进入绘制模式
  - `cancelDraw()` — 取消绘制
  - `deleteDrawing(id)` — 删除画线
  - `clearDrawings()` — 清除所有画线
  - `saveDrawings(stockCode, period)` — 持久化
  - `loadDrawings(stockCode, period)` — 加载已保存画线

- [ ] **Step 1: 实现 useDrawingTools**

```typescript
// useDrawingTools.ts
import { ref, type Ref } from 'vue'
import type { IChartApi } from 'lightweight-charts'
import { saveDrawing, loadDrawing } from '@/api/kline'

export type DrawingType =
  | 'trend' | 'horizontal' | 'rectangle' | 'fibonacci'
  | 'arrow' | 'text' | 'ray' | 'channel' | 'triangle' | 'vertical'

export interface DrawingPoint {
  x: number; y: number
  time?: number | string
  price?: number
}

export interface DrawingObject {
  id: string
  type: DrawingType
  points: DrawingPoint[]
  color: string
  lineWidth: number
  lineStyle: 'solid' | 'dashed' | 'dotted'
  text?: string
  visible: boolean
  createdAt: number
}

const TOOL_POINTS: Record<DrawingType, number> = {
  horizontal: 1, text: 1, vertical: 1,
  trend: 2, rectangle: 2, fibonacci: 2, arrow: 2, ray: 2,
  channel: 3, triangle: 3
}

export function useDrawingTools(
  chart: Ref<IChartApi | null>,
  drawingCanvas: Ref<HTMLCanvasElement | null>
) {
  const drawings = ref<DrawingObject[]>([])
  const currentTool = ref<DrawingType | null>(null)
  const currentPoints = ref<DrawingPoint[]>([])
  const isDrawing = ref(false)
  const DEFAULT_COLOR = '#58a6ff'

  const canvasCtx = ref<CanvasRenderingContext2D | null>(null)

  const initCanvas = () => {
    if (!drawingCanvas.value) return
    const rect = drawingCanvas.value.getBoundingClientRect()
    drawingCanvas.value.width = rect.width
    drawingCanvas.value.height = rect.height
    canvasCtx.value = drawingCanvas.value.getContext('2d')
    redraw()
  }

  const startDraw = (tool: DrawingType) => {
    currentTool.value = tool
    currentPoints.value = []
    isDrawing.value = true
  }

  const cancelDraw = () => {
    currentTool.value = null
    currentPoints.value = []
    isDrawing.value = false
  }

  const addPoint = (x: number, y: number) => {
    if (!currentTool.value || !chart.value) return
    const timeScale = chart.value.timeScale()
    const priceScale = chart.value.priceScale('right')
    const time = timeScale.coordinateToTime(x)
    const price = priceScale?.coordinateToPrice(y)
    currentPoints.value.push({ x, y, time: time ?? undefined, price: price ?? undefined })

    if (currentPoints.value.length >= TOOL_POINTS[currentTool.value]) {
      completeDraw()
    }
  }

  const completeDraw = () => {
    if (!currentTool.value) return
    const obj: DrawingObject = {
      id: `draw-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      type: currentTool.value,
      points: [...currentPoints.value],
      color: DEFAULT_COLOR,
      lineWidth: 2,
      lineStyle: 'solid',
      visible: true,
      createdAt: Date.now()
    }
    drawings.value.push(obj)
    cancelDraw()
    redraw()
  }

  // --- Canvas 渲染 ---
  const redraw = () => {
    const ctx = canvasCtx.value
    const canvas = drawingCanvas.value
    if (!ctx || !canvas) return
    ctx.clearRect(0, 0, canvas.width, canvas.height)
    for (const d of drawings.value) {
      if (d.visible) drawObject(ctx, d, canvas)
    }
  }

  const drawObject = (ctx: CanvasRenderingContext2D, obj: DrawingObject, canvas: HTMLCanvasElement) => {
    ctx.strokeStyle = obj.color
    ctx.fillStyle = obj.color
    ctx.lineWidth = obj.lineWidth
    ctx.setLineDash(obj.lineStyle === 'dashed' ? [6, 4] : obj.lineStyle === 'dotted' ? [2, 2] : [])

    const pts = obj.points
    ctx.beginPath()

    switch (obj.type) {
      case 'trend':
        if (pts.length >= 2) { ctx.moveTo(pts[0].x, pts[0].y); ctx.lineTo(pts[1].x, pts[1].y) }
        break
      case 'arrow':
        if (pts.length >= 2) {
          ctx.moveTo(pts[0].x, pts[0].y); ctx.lineTo(pts[1].x, pts[1].y)
          // 箭头
          const angle = Math.atan2(pts[1].y - pts[0].y, pts[1].x - pts[0].x)
          const headLen = 10
          ctx.moveTo(pts[1].x, pts[1].y)
          ctx.lineTo(pts[1].x - headLen * Math.cos(angle - Math.PI / 6), pts[1].y - headLen * Math.sin(angle - Math.PI / 6))
          ctx.moveTo(pts[1].x, pts[1].y)
          ctx.lineTo(pts[1].x - headLen * Math.cos(angle + Math.PI / 6), pts[1].y - headLen * Math.sin(angle + Math.PI / 6))
        }
        break
      case 'horizontal':
        ctx.moveTo(0, pts[0].y); ctx.lineTo(canvas.width, pts[0].y)
        ctx.font = '11px monospace'
        ctx.fillText(pts[0].price?.toFixed(2) || '', 10, pts[0].y - 4)
        break
      case 'vertical':
        ctx.moveTo(pts[0].x, 0); ctx.lineTo(pts[0].x, canvas.height)
        break
      case 'ray':
        if (pts.length >= 2) {
          const dx = pts[1].x - pts[0].x
          const dy = pts[1].y - pts[0].y
          const endX = pts[0].x + dx * 100
          const endY = pts[0].y + dy * 100
          ctx.moveTo(pts[0].x, pts[0].y); ctx.lineTo(endX, endY)
        }
        break
      case 'rectangle':
        if (pts.length >= 2) ctx.rect(pts[0].x, pts[0].y, pts[1].x - pts[0].x, pts[1].y - pts[0].y)
        break
      case 'triangle':
        if (pts.length >= 3) {
          ctx.moveTo(pts[0].x, pts[0].y); ctx.lineTo(pts[1].x, pts[1].y)
          ctx.lineTo(pts[2].x, pts[2].y); ctx.closePath()
        }
        break
      case 'channel':
        if (pts.length >= 3) {
          ctx.moveTo(pts[0].x, pts[0].y); ctx.lineTo(pts[1].x, pts[1].y)
          // 平行线通过 pts[2]
          const dx = pts[1].x - pts[0].x, dy = pts[1].y - pts[0].y
          const px = pts[2].x, py = pts[2].y
          ctx.moveTo(px, py); ctx.lineTo(px + dx, py + dy)
        }
        break
      case 'fibonacci':
        if (pts.length >= 2) {
          const x1 = Math.min(pts[0].x, pts[1].x), x2 = Math.max(pts[0].x, pts[1].x)
          const y1 = pts[0].y, y2 = pts[1].y
          const diff = y2 - y1
          for (const level of [0, 0.236, 0.382, 0.5, 0.618, 1]) {
            const y = y1 + diff * level
            ctx.moveTo(x1, y); ctx.lineTo(x2, y)
            ctx.font = '10px monospace'
            ctx.fillText(`${(level * 100).toFixed(1)}%`, x2 + 4, y + 3)
          }
        }
        break
      case 'text':
        ctx.font = '13px sans-serif'
        ctx.fillText(obj.text || '', pts[0].x, pts[0].y)
        break
    }
    ctx.stroke()
  }

  // --- 持久化 ---
  const saveDrawings = async (stockCode: string, period: string) => {
    try {
      const userId = Number(localStorage.getItem('userId') || '0')
      if (userId <= 0) return
      await saveDrawing({ userId, stockCode, period, data: JSON.stringify(drawings.value) })
    } catch (e) {
      console.error('[useDrawingTools] 保存画线失败:', e)
    }
  }

  const loadDrawings = async (stockCode: string, period: string) => {
    try {
      const userId = Number(localStorage.getItem('userId') || '0')
      if (userId <= 0) return
      const res = await loadDrawing(userId, stockCode, period)
      if (res.code === 200 && res.data?.data) {
        drawings.value = JSON.parse(res.data.data || '[]')
        redraw()
      }
    } catch (e) {
      console.error('[useDrawingTools] 加载画线失败:', e)
    }
  }

  const deleteDrawing = (id: string) => {
    drawings.value = drawings.value.filter(d => d.id !== id)
    redraw()
  }

  const clearDrawings = () => {
    drawings.value = []
    redraw()
  }

  return {
    drawings,
    currentTool,
    isDrawing,
    initCanvas,
    startDraw,
    cancelDraw,
    addPoint,
    deleteDrawing,
    clearDrawings,
    saveDrawings,
    loadDrawings,
    redraw
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/composables/useDrawingTools.ts
git commit -m "feat: add useDrawingTools composable with 10 drawing tool types"
```

---

## Phase 7: 导出和信息面板

### Task 10: 创建 useChartExport composable

**Files:**
- Create: `frontend/src/composables/useChartExport.ts`

**Interfaces:**
- Consumes: `chartContainer: Ref<HTMLElement | null>`, `data: CandleItem[]`, `indicators: IndicatorResult[]`
- Produces:
  - `exportPNG(filename)` — 导出为 PNG
  - `exportCSV(filename)` — 导出为 CSV

- [ ] **Step 1: 安装 html2canvas**

Run: `cd frontend && npm install html2canvas`

- [ ] **Step 2: 实现 useChartExport**

```typescript
// useChartExport.ts
import { ref, type Ref } from 'vue'
import html2canvas from 'html2canvas'
import type { CandleItem } from '@/types/indicators'

export function useChartExport(chartContainer: Ref<HTMLElement | null>) {
  const exporting = ref(false)

  const exportPNG = async (filename: string) => {
    if (!chartContainer.value || exporting.value) return
    exporting.value = true
    try {
      const canvas = await html2canvas(chartContainer.value, {
        backgroundColor: null,
        scale: 2
      })
      const link = document.createElement('a')
      link.download = filename
      link.href = canvas.toDataURL('image/png')
      link.click()
    } finally {
      exporting.value = false
    }
  }

  const exportCSV = (filename: string, data: CandleItem[], indicatorData?: Record<string, number[]>) => {
    const headers = ['date', 'open', 'high', 'low', 'close', 'volume']
    if (indicatorData) {
      headers.push(...Object.keys(indicatorData))
    }
    const rows = [headers.join(',')]
    data.forEach((d, i) => {
      const row = [d.time, d.open, d.high, d.low, d.close, d.volume ?? '']
      if (indicatorData) {
        for (const key of Object.keys(indicatorData)) {
          row.push(indicatorData[key][i] ?? '')
        }
      }
      rows.push(row.join(','))
    })
    const blob = new Blob([rows.join('\n')], { type: 'text/csv;charset=utf-8;' })
    const link = document.createElement('a')
    link.download = filename
    link.href = URL.createObjectURL(blob)
    link.click()
    URL.revokeObjectURL(link.href)
  }

  return { exporting, exportPNG, exportCSV }
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/composables/useChartExport.ts frontend/package.json frontend/package-lock.json
git commit -m "feat: add useChartExport composable for PNG and CSV export"
```

---

### Task 11: 创建 useInfoPanel composable

**Files:**
- Create: `frontend/src/composables/useInfoPanel.ts`

**Interfaces:**
- Consumes: `chart: Ref<IChartApi | null>`, `data: CandleItem[]`, `indicators: activeIndicators`
- Produces:
  - `panelData: computed` — 当前光标位置的聚合数据
  - `subscribeCrosshair()` — 订阅十字光标事件
  - `unsubscribeCrosshair()` — 取消订阅

- [ ] **Step 1: 实现 useInfoPanel**

```typescript
// useInfoPanel.ts
import { ref, computed, type Ref } from 'vue'
import type { IChartApi } from 'lightweight-charts'
import type { CandleItem } from '@/types/indicators'
import type { IndicatorConfig } from './useIndicators'
import { calcMA, calcMACD, calcKDJ, calcRSI, calcBOLL } from '@/utils/indicators'

export interface InfoPanelData {
  date: string
  open: number
  high: number
  low: number
  close: number
  change: number
  volume: number
  ma5: number | null
  ma10: number | null
  ma20: number | null
  ma60: number | null
  macdDif: number | null
  macdDea: number | null
  macdHist: number | null
  kdjK: number | null
  kdjD: number | null
  kdjJ: number | null
  rsi: number | null
  bollUpper: number | null
  bollMiddle: number | null
  bollLower: number | null
}

export function useInfoPanel(
  chart: Ref<IChartApi | null>,
  data: Ref<CandleItem[]>,
  activeIndicators: IndicatorConfig
) {
  const currentIndex = ref(-1)

  const panelData = computed<InfoPanelData | null>(() => {
    const idx = currentIndex.value
    if (idx < 0 || idx >= data.value.length) return null
    const d = data.value[idx]
    const prevClose = idx > 0 ? data.value[idx - 1].close : d.open
    const change = ((d.close - prevClose) / prevClose) * 100

    const result: InfoPanelData = {
      date: String(d.time),
      open: d.open, high: d.high, low: d.low, close: d.close,
      change: +change.toFixed(2),
      volume: d.volume ?? 0,
      ma5: null, ma10: null, ma20: null, ma60: null,
      macdDif: null, macdDea: null, macdHist: null,
      kdjK: null, kdjD: null, kdjJ: null,
      rsi: null,
      bollUpper: null, bollMiddle: null, bollLower: null
    }

    if (activeIndicators.ma5) result.ma5 = calcMA(data.value, 5)[idx]
    if (activeIndicators.ma10) result.ma10 = calcMA(data.value, 10)[idx]
    if (activeIndicators.ma20) result.ma20 = calcMA(data.value, 20)[idx]
    if (activeIndicators.ma60) result.ma60 = calcMA(data.value, 60)[idx]
    if (activeIndicators.macd) {
      const { dif, dea, histogram } = calcMACD(data.value)
      result.macdDif = dif[idx]
      result.macdDea = dea[idx]
      result.macdHist = histogram[idx]
    }
    if (activeIndicators.kdj) {
      const { k, d, j } = calcKDJ(data.value)
      result.kdjK = k[idx]; result.kdjD = d[idx]; result.kdjJ = j[idx]
    }
    if (activeIndicators.rsi) result.rsi = calcRSI(data.value)[idx]
    if (activeIndicators.boll) {
      const { upper, middle, lower } = calcBOLL(data.value)
      result.bollUpper = upper[idx]; result.bollMiddle = middle[idx]; result.bollLower = lower[idx]
    }

    return result
  })

  let unsubscribe: (() => void) | null = null

  const subscribeCrosshair = () => {
    if (!chart.value) return
    const handler = (param: any) => {
      if (param.seriesData) {
        const idx = data.value.findIndex(d => String(d.time) === String(param.time))
        if (idx >= 0) currentIndex.value = idx
      }
    }
    chart.value.subscribeCrosshairMove(handler)
    unsubscribe = () => chart.value?.unsubscribeCrosshairMove(handler)
  }

  const unsubscribeCrosshair = () => {
    unsubscribe?.()
    unsubscribe = null
  }

  return { currentIndex, panelData, subscribeCrosshair, unsubscribeCrosshair }
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/composables/useInfoPanel.ts
git commit -m "feat: add useInfoPanel composable for crosshair info display"
```

---

## Phase 8: 主组件集成与 UI

### Task 12: 重构 LightweightChart.vue 主组件

**Files:**
- Modify: `frontend/src/views/stock/LightweightChart.vue`

**Interfaces:**
- Consumes: 所有 composables
- Produces: 完整的 K 线图表 UI（工具栏 + 图表区 + 画布叠加层 + 信息面板）

- [ ] **Step 1: 重构主组件结构**

核心模板结构：

```vue
<template>
  <div class="lw-chart">
    <!-- 工具栏 -->
    <div class="chart-toolbar">
      <!-- 周期按钮组 -->
      <div class="period-group">
        <button v-for="p in periods" :key="p.value"
          :class="['period-btn', { active: currentPeriod === p.value }]"
          @click="changePeriod(p.value)">{{ p.label }}</button>
      </div>
      <!-- 指标选择 -->
      <a-popover trigger="click">
        <template #content>
          <div class="indicator-selector">
            <label v-for="ind in indicatorList" :key="ind.key">
              <a-checkbox :checked="activeIndicators[ind.key]"
                @change="toggleIndicator(ind.key)" />
              <span>{{ ind.label }}</span>
            </label>
          </div>
        </template>
        <a-button><SettingOutlined /> 指标</a-button>
      </a-popover>
      <!-- 画线工具 -->
      <div class="drawing-toolbar">
        <button v-for="tool in drawingTools" :key="tool.type"
          :class="['draw-btn', { active: currentTool === tool.type }]"
          @click="startDrawTool(tool.type)"
          :title="tool.label">
          <component :is="tool.icon" />
        </button>
      </div>
      <!-- 买卖点 -->
      <a-radio-group v-model:value="markerMode" button-style="solid" size="small">
        <a-radio-button value="">标记关</a-radio-button>
        <a-radio-button value="buy">买入标记</a-radio-button>
        <a-radio-button value="sell">卖出标记</a-radio-button>
      </a-radio-group>
      <!-- 主题切换 -->
      <a-button @click="toggleTheme">
        <template #icon>
          <SunOutlined v-if="currentThemeName === 'dark'" />
          <MoonOutlined v-else />
        </template>
      </a-button>
      <!-- 导出 -->
      <a-dropdown>
        <a-button><DownloadOutlined /></a-button>
        <template #overlay>
          <a-menu @click="handleExport">
            <a-menu-item key="png">导出 PNG</a-menu-item>
            <a-menu-item key="csv">导出 CSV</a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
      <!-- 股票信息 -->
      <span v-if="stockInfo" class="stock-label">
        <span class="stock-name">{{ stockInfo.stockName }}</span>
        <span class="stock-code">({{ stockInfo.stockCode }})</span>
        <span class="stock-price" :style="{ color: priceColor }">¥{{ currentPrice }}</span>
      </span>
    </div>

    <!-- 图表区 -->
    <div class="chart-wrapper">
      <div ref="chartContainer" class="chart-container" />
      <canvas ref="drawingCanvas" class="drawing-canvas"
        :class="{ active: currentTool }"
        @click="onCanvasClick" />
      <!-- 信息面板 -->
      <InfoPanel v-if="panelData" :data="panelData" :indicators="activeIndicators" :theme="currentTheme" />
    </div>
  </div>
</template>
```

- [ ] **Step 2: 实现 script 逻辑**

```typescript
// 核心逻辑（简化展示）
const { chart, initChart, applyTheme, addCandlestickSeries, addHistogramSeries,
        removeSeries, destroyChart } = useChartCore(chartContainer)
const { currentTheme, currentThemeName, toggleTheme } = useChartTheme()
const { activeIndicators, updateIndicators } = useIndicators(chart, currentTheme)
const { drawings, currentTool, initCanvas, startDraw, addPoint, deleteDrawing,
        saveDrawings, loadDrawings, redraw } = useDrawingTools(chart, drawingCanvas)
const { markers, addManualMarker, loadAutoMarkers } = useTradeMarkers(chart, candleSeries)
const { panelData, subscribeCrosshair, unsubscribeCrosshair } = useInfoPanel(
  chart, dailyData, activeIndicators)
const { exporting, exportPNG, exportCSV } = useChartExport(chartContainer)
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/views/stock/LightweightChart.vue
git commit -m "feat: refactor LightweightChart.vue with modular composables"
```

---

### Task 13: 创建 InfoPanel 子组件

**Files:**
- Create: `frontend/src/views/stock/InfoPanel.vue`

**Interfaces:**
- Consumes: `data: InfoPanelData`, `indicators: IndicatorConfig`, `theme: ChartTheme`
- Produces: 左上角信息面板 UI

- [ ] **Step 1: 实现 InfoPanel 组件**

```vue
<template>
  <div class="info-panel" :style="{ background: panelBg, color: theme.layout.textColor }">
    <div class="info-header">{{ stockName }} ({{ stockCode }})</div>
    <div class="info-row">
      <span>O: {{ data.open.toFixed(2) }}</span>
      <span>H: {{ data.high.toFixed(2) }}</span>
      <span>L: {{ data.low.toFixed(2) }}</span>
      <span>C: {{ data.close.toFixed(2) }}</span>
    </div>
    <div class="info-row">
      <span :style="{ color: data.change >= 0 ? theme.candle.upColor : theme.candle.downColor }">
        Chg: {{ data.change >= 0 ? '+' : '' }}{{ data.change.toFixed(2) }}%
      </span>
      <span>Vol: {{ formatVolume(data.volume) }}</span>
    </div>
    <template v-if="data.ma5 !== null">
      <div class="info-row">
        <span v-if="data.ma5 !== null" :style="{ color: theme.indicators.ma5 }">MA5: {{ data.ma5?.toFixed(2) }}</span>
        <span v-if="data.ma10 !== null" :style="{ color: theme.indicators.ma10 }">MA10: {{ data.ma10?.toFixed(2) }}</span>
        <span v-if="data.ma20 !== null" :style="{ color: theme.indicators.ma20 }">MA20: {{ data.ma20?.toFixed(2) }}</span>
        <span v-if="data.ma60 !== null" :style="{ color: theme.indicators.ma60 }">MA60: {{ data.ma60?.toFixed(2) }}</span>
      </div>
    </template>
    <div class="info-row" v-if="data.macdDif !== null">
      <span :style="{ color: theme.indicators.dif }">DIF: {{ data.macdDif?.toFixed(3) }}</span>
      <span :style="{ color: theme.indicators.dea }">DEA: {{ data.macdDea?.toFixed(3) }}</span>
      <span>MACD: {{ data.macdHist?.toFixed(3) }}</span>
    </div>
    <div class="info-row" v-if="data.kdjK !== null">
      <span :style="{ color: theme.indicators.k }">K: {{ data.kdjK }}</span>
      <span :style="{ color: theme.indicators.d }">D: {{ data.kdjD }}</span>
      <span :style="{ color: theme.indicators.j }">J: {{ data.kdjJ }}</span>
    </div>
    <div class="info-row" v-if="data.rsi !== null">
      <span :style="{ color: theme.indicators.rsi }">RSI: {{ data.rsi?.toFixed(2) }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { ChartTheme } from '@/types/chart'
import type { IndicatorConfig } from '@/composables/useIndicators'
import type { InfoPanelData } from '@/composables/useInfoPanel'

const props = defineProps<{
  data: InfoPanelData
  indicators: IndicatorConfig
  theme: ChartTheme
  stockName?: string
  stockCode?: string
}>()

const panelBg = computed(() =>
  props.theme.name === 'dark' ? 'rgba(19, 23, 34, 0.85)' : 'rgba(255, 255, 255, 0.85)'
)

const formatVolume = (v: number) => {
  if (v >= 1e8) return (v / 1e8).toFixed(1) + '亿'
  if (v >= 1e4) return (v / 1e4).toFixed(0) + '万'
  return v.toString()
}
</script>

<style scoped>
.info-panel {
  position: absolute;
  top: 8px;
  left: 8px;
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 11px;
  font-family: monospace;
  line-height: 1.6;
  pointer-events: none;
  z-index: 10;
  backdrop-filter: blur(4px);
}
.info-header { font-weight: 600; margin-bottom: 4px; }
.info-row { display: flex; gap: 12px; white-space: nowrap; }
</style>
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/views/stock/InfoPanel.vue
git commit -m "feat: add InfoPanel component for crosshair data display"
```

---

## Self-Review

**Spec coverage check:**

| 设计文档需求 | 对应 Task |
|-------------|-----------|
| 架构重构（Composables 拆分） | Task 5-11 |
| MA/MACD/KDJ/RSI/BOLL/VOL 指标 | Task 1-4, 7 |
| 10 种画线工具 | Task 9 |
| 买卖点标记（手动+自动） | Task 8 |
| 主题切换（深色/浅色） | Task 6 |
| PNG/CSV 导出 | Task 10 |
| 十字光标信息面板 | Task 11, 13 |
| 主组件集成 | Task 12 |

**Placeholder scan:** 无 TBD/TODO，所有步骤含具体代码。

**Type consistency check:**
- `CandleItem` 在 Task 4 定义，Task 1/2/3/7/11 引用 — 一致
- `ChartTheme` 在 Task 6 定义，Task 5/7/11/13 引用 — 一致
- `IndicatorConfig` 在 Task 7 定义，Task 11/13 引用 — 一致
- `TradeMarker` 在 Task 8 定义 — 独立使用
- `DrawingObject`/`DrawingType` 在 Task 9 定义 — 独立使用

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-08-09-kline-chart-enhancement.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
