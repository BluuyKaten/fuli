# K线图美化和功能扩展设计文档

**日期**: 2026-08-09
**状态**: 待实施

---

## 1. 背景与目标

### 1.1 现状

项目使用 TradingView lightweight-charts 库绘制 K 线图，当前 `LightweightChart.vue` 已实现：
- 多周期切换（1分/5分/15分/60分/日/周/月）
- 成交量副图
- 基础画线工具（趋势线/水平线/矩形/斐波那契）
- 深色主题

### 1.2 目标

在现有基础上进行美化和功能扩展，兼顾个人分析、教学演示、实盘辅助三种场景，打造现代简约（TradingView 风格）的专业 K 线图表。

### 1.3 设计原则

- 现代简约风，信息层次清晰
- 功能模块化，可独立启用/禁用
- 指标计算与渲染解耦，便于测试和扩展
- 主题系统统一管理，支持一键切换

---

## 2. 整体架构

### 2.1 分层设计

采用"主组件 + Composables"的分层架构：

```
KLinePage.vue (页面容器)
  └── LightweightChart.vue (主组件 — 图表生命周期、布局、事件分发)
        ├── useChartCore.ts       — 图表初始化、生命周期、ResizeObserver
        ├── useIndicators.ts      — 指标计算与渲染系列
        ├── useDrawingTools.ts    — 画线工具状态管理
        ├── useTradeMarkers.ts    — 买卖点标记（手动/自动）
        ├── useChartTheme.ts      — 主题配色管理
        ├── useChartExport.ts     — 导出 PNG / CSV
        └── useInfoPanel.ts       — 十字光标信息面板数据
```

### 2.2 职责划分

| 模块 | 职责 | 输入 | 输出 |
|------|------|------|------|
| LightweightChart.vue | DOM 容器、用户交互分发、组合各 composable | stockCode, price-change 事件 | 渲染完成的图表 |
| useChartCore | 图表实例管理、ResizeObserver、销毁 | container ref | chart 实例、尺寸变化 |
| useIndicators | 指标计算、系列创建/更新/销毁 | K 线数据、指标配置 | 图表系列 |
| useDrawingTools | 画线状态、Canvas 渲染、持久化 | 鼠标事件、工具选择 | 画布绘制 |
| useTradeMarkers | 买卖点数据、标记渲染 | 交易记录、手动操作 | SeriesMarkers |
| useChartTheme | 主题配置、切换、持久化 | 主题名 | 图表重绘 |
| useChartExport | PNG/CSV 导出逻辑 | 导出指令 | 文件下载 |
| useInfoPanel | 当前 K 线数据聚合 | 十字光标位置 | 面板展示数据 |

---

## 3. 功能模块设计

### 3.1 技术指标系统

#### 3.1.1 支持的指标

| 指标 | 类型 | 渲染位置 | 参数 |
|------|------|----------|------|
| MA | 均线 | 主图叠加 | 5/10/20/60 日 |
| MACD | 副图 | 独立面板 | 12/26/9 |
| KDJ | 副图 | 独立面板 | 9/3/3 |
| RSI | 副图 | 独立面板 | 14 |
| BOLL | 主图叠加 | 主图 | 20/2 |
| VOL | 副图 | 独立面板 | - |

#### 3.1.2 计算层设计

```typescript
// 指标计算纯函数接口
interface IndicatorResult {
  name: string
  type: 'line' | 'histogram' | 'area'
  data: (number | '-')[]
  colors?: string[]
}

// 各指标独立函数
function calcMA(data: CandleItem[], period: number): IndicatorResult
function calcMACD(data: CandleItem[]): { dif: IndicatorResult; dea: IndicatorResult; macd: IndicatorResult }
function calcKDJ(data: CandleItem[], n = 9): { k: IndicatorResult; d: IndicatorResult; j: IndicatorResult }
function calcRSI(data: CandleItem[], period = 14): IndicatorResult
function calcBOLL(data: CandleItem[], period = 20, k = 2): { upper: IndicatorResult; middle: IndicatorResult; lower: IndicatorResult }
```

#### 3.1.3 交互设计

- 图表下方设指标选择栏（MA/MACD/KDJ/RSI/BOLL 可勾选）
- 主图叠加：MA、BOLL（与 K 线同区域）
- 副图区域：MACD、KDJ、RSI、VOL（各自独立面板，可折叠/展开）
- 副图面板支持拖拽排序

#### 3.1.4 配色方案

| 指标 | 深色主题色 | 浅色主题色 |
|------|-----------|-----------|
| MA5 | #ff9800 | #ff9800 |
| MA10 | #2196f3 | #2196f3 |
| MA20 | #e91e63 | #e91e63 |
| MA60 | #9c27b0 | #9c27b0 |
| MACD-DIF | #ff9800 | #ff9800 |
| MACD-DEA | #2196f3 | #2196f3 |
| KDJ-K | #ff9800 | #ff9800 |
| KDJ-D | #2196f3 | #2196f3 |
| KDJ-J | #e91e63 | #e91e63 |
| RSI | #7c4dff | #7c4dff |
| BOLL 上轨 | #ef5350 | #ef5350 |
| BOLL 中轨 | #8b949e | #8b949e |
| BOLL 下轨 | #26a69a | #26a69a |

---

### 3.2 画线工具系统

#### 3.2.1 工具清单

| 工具 | 交互方式 | 点数 | 说明 |
|------|----------|------|------|
| 趋势线 | 两点确定一条线 | 2 | 现有 |
| 水平线 | 点击确定价格 | 1 | 现有 |
| 矩形 | 对角两点 | 2 | 现有 |
| 斐波那契 | 两点确定区间 | 2 | 现有 |
| 箭头线 | 两点，终点带箭头 | 2 | 新增 |
| 文字标注 | 点击放置 + 输入文字 | 1 | 新增 |
| 射线 | 起点 + 方向点 | 2 | 新增 |
| 平行通道 | 三点（趋势线+通道宽） | 3 | 新增 |
| 三角形 | 三点确定 | 3 | 新增 |
| 垂直线 | 点击确定时间 | 1 | 新增 |

#### 3.2.2 数据模型

```typescript
interface DrawingPoint {
  x: number          // 屏幕 X
  y: number          // 屏幕 Y
  time?: number      // 对应时间戳（用于持久化）
  price?: number     // 对应价格（用于持久化）
}

interface DrawingObject {
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
```

#### 3.2.3 渲染方案

使用独立 Canvas 层叠加在 lightweight-charts 图表上方：
- 原因：lightweight-charts 不原生支持自定义图形，Overlay Canvas 更灵活
- Canvas 与图表同步尺寸（通过 ResizeObserver）
- 绘制时根据 time/price 反算屏幕坐标，保证缩放/平移时画线跟随

#### 3.2.4 交互流程

1. 工具栏选择工具 → 画布进入对应绘制模式
2. 用户点击/拖拽 → 实时预览（ghost 效果）
3. 绘制完成 → 对象存入 `drawings` 数组
4. 选中已绘制对象 → 可编辑（拖拽节点）、删除、改颜色/线宽
5. 支持持久化保存到后端（接口已有 `loadDrawing`）

---

### 3.3 买卖点标记系统

#### 3.3.1 两种模式

**手动标记**：
- 工具栏设"买入标记"/"卖出标记"模式
- 点击 K 线 → 在该 K 线收盘价位置添加标记
- 买入 ▲（绿色，K 线下方）、卖出 ▼（红色，K 线上方）

**自动标记**（从交易记录加载）：
- 调用后端接口获取当前股票的成交记录
- 在对应时间 K 线上自动渲染买卖点
- 手动标记和自动标记用不同样式区分

#### 3.3.2 数据模型

```typescript
interface TradeMarker {
  id: string
  source: 'manual' | 'auto'
  direction: 'buy' | 'sell'
  time: string
  price: number
  quantity?: number
  editable: boolean
}
```

#### 3.3.3 实现方式

使用 lightweight-charts 的 `SeriesMarkers` API：
- 性能好（原生支持，不额外渲染）
- 手动标记可拖拽调整价格位置
- hover 显示详情 tooltip（价格、数量、时间）

---

### 3.4 主题系统

#### 3.4.1 主题配置

```typescript
interface ChartTheme {
  name: 'dark' | 'light'
  layout: {
    background: string
    textColor: string
  }
  grid: {
    vertLines: string
    horzLines: string
  }
  candle: {
    upColor: string
    downColor: string
    borderUpColor: string
    borderDownColor: string
    wickUpColor: string
    wickDownColor: string
  }
  crosshair: string
  indicators: Record<string, string>
}
```

#### 3.4.2 内置主题

| 属性 | 深色主题 | 浅色主题 |
|------|----------|----------|
| 背景 | #131722 | #ffffff |
| 文字 | #8b949e | #333333 |
| 网格 | #30363d | #e0e0e0 |
| 涨色 | #ef5350 | #ef5350 |
| 跌色 | #26a69a | #26a69a |
| 十字光标 | #58a6ff | #2196f3 |

#### 3.4.3 切换机制

- 工具栏设主题切换按钮（太阳/月亮图标）
- 切换时：更新 CSS 变量 + 调用 `chart.applyOptions()` 重绘 + 重绘所有指标系列
- 主题偏好存 localStorage

---

### 3.5 导出功能

#### 3.5.1 PNG 导出

- 使用 `html2canvas` 截取图表区域
- 导出时临时隐藏工具栏和画线，保留纯净图表
- 自动添加水印（股票代码 + 周期信息）
- 文件名格式：`{stockCode}_{period}_{date}.png`

#### 3.5.2 CSV 导出

- 导出当前可见的 K 线数据 + 可见指标数值
- 列：日期, 开盘, 收盘, 最高, 最低, 成交量, MA5, MA10, MA20, MA60, MACD_DIF, MACD_DEA, MACD, KDJ_K, KDJ_D, KDJ_J, RSI, BOLL_UPPER, BOLL_MIDDLE, BOLL_LOWER
- 使用 Blob + URL.createObjectURL 触发下载
- 文件名格式：`{stockCode}_{period}_{startDate}_{endDate}.csv`

---

### 3.6 十字光标信息面板

#### 3.6.1 位置与样式

- 固定在图表左上角
- 半透明背景，不遮挡 K 线
- 样式与当前主题联动

#### 3.6.2 展示内容

```
┌─────────────────────────────┐
│ 贵州茅台 (600519)  日线      │
│                             │
│ O: 1850.00   H: 1862.50    │
│ L: 1845.00   C: 1858.00    │
│ Chg: +0.43%  Vol: 2.3M     │
│                             │
│ MA5: 1852.30  MA10: 1848.50│
│ MA20: 1845.20 MA60: 1830.00│
│ MACD: +1.234  KDJ-K: 65.32 │
│ RSI(14): 58.5               │
└─────────────────────────────┘
```

- 指标部分根据当前启用的指标动态显示
- 涨跌幅根据收盘价与开盘价计算

---

## 4. 文件结构

```
frontend/src/
  composables/
    useChartCore.ts           # 图表核心（初始化/销毁/Resize）
    useIndicators.ts          # 指标管理（计算+渲染）
    useDrawingTools.ts        # 画线工具（状态+渲染）
    useTradeMarkers.ts        # 买卖点标记
    useChartTheme.ts          # 主题管理
    useChartExport.ts         # 导出功能
    useInfoPanel.ts           # 信息面板数据
  utils/
    indicators/
      index.ts                # 统一导出
      ma.ts                   # MA 计算
      macd.ts                 # MACD 计算
      kdj.ts                  # KDJ 计算
      rsi.ts                  # RSI 计算
      boll.ts                 # BOLL 计算
  views/stock/
    LightweightChart.vue      # 重构后主组件
    ChartToolbar.vue          # 工具栏子组件
    IndicatorPanel.vue        # 副图面板
    InfoPanel.vue             # 左上角信息面板
```

---

## 5. 技术依赖

| 依赖 | 用途 | 是否新增 |
|------|------|----------|
| lightweight-charts@5.2.0 | 图表库 | 已有 |
| html2canvas | PNG 导出 | 新增 |
| dayjs | 日期处理 | 已有 |

---

## 6. 实施顺序建议

1. **架构重构**：拆分 `LightweightChart.vue` 为 composables，保持现有功能不变
2. **主题系统**：实现浅色/深色主题切换
3. **技术指标**：逐个实现 MA → MACD → KDJ → RSI → BOLL
4. **信息面板**：实现十字光标数据面板
5. **买卖点标记**：手动标记 + 自动标记
6. **画线工具增强**：新增箭头/文字/射线/通道/三角形/垂直线
7. **导出功能**：PNG + CSV 导出
8. **视觉打磨**：间距、动画、过渡效果

---

## 7. 风险与注意事项

| 风险 | 应对策略 |
|------|----------|
| lightweight-charts 系列数量限制 | 控制同时显示的指标数量，副图按需创建/销毁 |
| Canvas 画线与图表缩放不同步 | 监听 timeScale 变化事件，实时重绘 Canvas |
| 大数据量下指标计算卡顿 | 使用 Web Worker 或按需计算可见区域 |
| 主题切换时闪烁 | 使用 CSS 变量过渡动画，批量更新图表选项 |
