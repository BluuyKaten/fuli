// useDrawingTools.ts
import { ref, type Ref } from 'vue'
import type { IChartApi, ISeriesApi, Time } from 'lightweight-charts'
import { saveDrawing, loadDrawing } from '@/api/kline'

export type DrawingType =
  | 'select'
  | 'trend' | 'horizontal' | 'rectangle' | 'fibonacci'
  | 'arrow' | 'text' | 'ray' | 'channel' | 'triangle' | 'vertical'

export interface DrawingPoint {
  time: Time
  price: number
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
  select: 0,
  horizontal: 1, text: 1, vertical: 1,
  trend: 2, rectangle: 2, fibonacci: 2, arrow: 2, ray: 2,
  channel: 3, triangle: 3
}

export function useDrawingTools(
  chart: Ref<IChartApi | null>,
  drawingCanvas: Ref<HTMLCanvasElement | null>,
  candleSeries: Ref<ISeriesApi<'Candlestick'> | null>
) {
  const drawings = ref<DrawingObject[]>([])
  const currentTool = ref<DrawingType | null>(null)
  const currentPoints = ref<DrawingPoint[]>([])
  const isDrawing = ref(false)
  const selectedId = ref<string | null>(null)
  const mousePosition = ref<{ x: number; y: number } | null>(null) // 鼠标当前位置（用于预览线）
  const isDragging = ref(false) // 是否正在拖拽控制点
  const dragTarget = ref<{ drawingId: string; pointIndex: number } | null>(null) // 拖拽目标
  const saveKey = ref<{ stockCode: string; period: string } | null>(null) // 当前保存 key（自动保存用）
  const loadVersion = ref(0) // 加载版本号，防止切换股票时旧请求覆盖新请求
  const DEFAULT_COLOR = '#58a6ff'

  const canvasCtx = ref<CanvasRenderingContext2D | null>(null)

  // --- 坐标转换：time/price → 屏幕 x/y ---
  const toScreen = (time: Time, price: number): { x: number; y: number } => {
    const c = chart.value
    const s = candleSeries.value
    if (!c || !s) return { x: -9999, y: -9999 } // 返回不可见坐标
    const x = time != null ? c.timeScale().timeToCoordinate(time) ?? -9999 : -9999
    const y = price != null ? s.priceToCoordinate(price) ?? -9999 : -9999
    return { x, y }
  }

  // --- 点击命中检测：判断点是否在某条画线附近 ---
  const HIT_RADIUS = 12 // 命中半径（像素），增大更容易选中

  // 检测是否命中控制点，返回 { drawingId, pointIndex } 或 null
  const hitTestControlPoint = (x: number, y: number): { drawingId: string; pointIndex: number } | null => {
    // 只有选中的线才允许拖拽控制点
    const d = drawings.value.find(d => d.id === selectedId.value)
    if (!d || !d.visible) return null
    const pts = d.points.map(p => toScreen(p.time, p.price))
    for (let i = 0; i < pts.length; i++) {
      // 跳过不可见的控制点（超出画布范围）
      if (pts[i].x < 0 || pts[i].y < 0) continue
      if (Math.hypot(pts[i].x - x, pts[i].y - y) < HIT_RADIUS) {
        return { drawingId: d.id, pointIndex: i }
      }
    }
    return null
  }

  const hitTest = (x: number, y: number): DrawingObject | null => {
    for (const d of drawings.value) {
      if (!d.visible) continue
      const pts = d.points.map(p => toScreen(p.time, p.price))
      // 检查是否靠近任一控制点
      for (const pt of pts) {
        if (pt.x < 0 || pt.y < 0) continue // 跳过不可见的控制点
        if (Math.hypot(pt.x - x, pt.y - y) < HIT_RADIUS) return d
      }
      // 特殊处理斐波那契：检查是否靠近任一条比例水平线
      if (d.type === 'fibonacci' && pts.length >= 2) {
        const x1 = Math.min(pts[0].x, pts[1].x), x2 = Math.max(pts[0].x, pts[1].x)
        const y1 = pts[0].y, y2 = pts[1].y
        const diff = y2 - y1
        if (Math.abs(diff) > 1e-3 && x >= x1 - HIT_RADIUS && x <= x2 + HIT_RADIUS) {
          for (const level of [0, 0.236, 0.382, 0.5, 0.618, 1]) {
            const ly = y1 + diff * level
            if (Math.abs(y - ly) < HIT_RADIUS) return d
          }
        }
      }
      // 特殊处理射线：检查是否靠近射线上的任意一点
      if (d.type === 'ray' && pts.length >= 2) {
        const [p0, p1] = pts
        // 不要求控制点可见，只要射线经过可见区域即可
        if (p0.x >= 0 && p0.y >= 0) {
          const dx = p1.x - p0.x, dy = p1.y - p0.y
          const lenSq = dx * dx + dy * dy
          if (lenSq > 1e-6) {
            const t = ((x - p0.x) * dx + (y - p0.y) * dy) / lenSq
            if (t >= 0) {
              const projX = p0.x + t * dx
              const projY = p0.y + t * dy
              if (Math.hypot(x - projX, y - projY) < HIT_RADIUS) return d
            }
          }
        }
      }
      // 特殊处理水平线：检查是否靠近水平线
      if (d.type === 'horizontal' && pts.length >= 1) {
        const { y: hy } = pts[0]
        if (hy >= 0 && Math.abs(y - hy) < HIT_RADIUS) return d
      }
      // 特殊处理垂直线：检查是否靠近垂直线
      if (d.type === 'vertical' && pts.length >= 1) {
        const { x: vx } = pts[0]
        if (vx >= 0 && Math.abs(x - vx) < HIT_RADIUS) return d
      }
      // 特殊处理矩形：检查是否靠近任意一条边
      if (d.type === 'rectangle' && pts.length >= 2) {
        const [p0, p1] = pts
        const sides = [
          [{ x: p0.x, y: p0.y }, { x: p1.x, y: p0.y }],
          [{ x: p1.x, y: p0.y }, { x: p1.x, y: p1.y }],
          [{ x: p1.x, y: p1.y }, { x: p0.x, y: p1.y }],
          [{ x: p0.x, y: p1.y }, { x: p0.x, y: p0.y }],
        ]
        for (const [a, b] of sides) {
          if (a.x >= 0 && a.y >= 0 && b.x >= 0 && b.y >= 0) {
            if (distToSegment(x, y, a, b) < HIT_RADIUS) return d
          }
        }
      }
      // 特殊处理三角形：检查是否靠近任意一条边
      if (d.type === 'triangle' && pts.length >= 3) {
        const sides = [[pts[0], pts[1]], [pts[1], pts[2]], [pts[2], pts[0]]]
        for (const [a, b] of sides) {
          if (a.x >= 0 && a.y >= 0 && b.x >= 0 && b.y >= 0) {
            if (distToSegment(x, y, a, b) < HIT_RADIUS) return d
          }
        }
      }
      // 特殊处理通道线：检查是否靠近两条平行线
      if (d.type === 'channel' && pts.length >= 3) {
        const [p0, p1, p2] = pts
        const dx = p1.x - p0.x, dy = p1.y - p0.y
        // 基线
        if (p0.x >= 0 && p0.y >= 0 && p1.x >= 0 && p1.y >= 0) {
          if (distToSegment(x, y, p0, p1) < HIT_RADIUS) return d
        }
        // 平行线（过 p2 点）
        if (p2.x >= 0 && p2.y >= 0) {
          const parallelLine = { x: p2.x + dx, y: p2.y + dy }
          if (distToSegment(x, y, p2, parallelLine) < HIT_RADIUS) return d
        }
      }
      // 检查是否靠近线段（端点之间的连线）
      for (let i = 0; i < pts.length - 1; i++) {
        if (pts[i].x < 0 || pts[i].y < 0 || pts[i + 1].x < 0 || pts[i + 1].y < 0) continue
        if (distToSegment(x, y, pts[i], pts[i + 1]) < HIT_RADIUS) return d
      }
    }
    return null
  }

  const distToSegment = (px: number, py: number, a: { x: number; y: number }, b: { x: number; y: number }) => {
    const dx = b.x - a.x, dy = b.y - a.y
    const lenSq = dx * dx + dy * dy
    if (lenSq === 0) return Math.hypot(px - a.x, py - a.y)
    let t = ((px - a.x) * dx + (py - a.y) * dy) / lenSq
    t = Math.max(0, Math.min(1, t))
    return Math.hypot(px - (a.x + t * dx), py - (a.y + t * dy))
  }

  // --- 拖拽控制点修改线轨迹 ---
  const startDrag = (x: number, y: number): boolean => {
    const hit = hitTestControlPoint(x, y)
    if (hit) {
      dragTarget.value = hit
      isDragging.value = true
      return true
    }
    return false
  }

  const updateDrag = (x: number, y: number) => {
    if (!dragTarget.value || !chart.value) return
    const { drawingId, pointIndex } = dragTarget.value
    const drawing = drawings.value.find(d => d.id === drawingId)
    if (!drawing) return
    const timeScale = chart.value.timeScale()
    const time = timeScale.coordinateToTime(x)
    const price = candleSeries.value?.coordinateToPrice(y)
    if (time == null || price == null || !Number.isFinite(price)) return
    drawing.points[pointIndex] = { time, price }
    redraw()
  }

  const endDrag = () => {
    if (!isDragging.value) return
    dragTarget.value = null
    isDragging.value = false
    autoSaveDrawings() // 拖拽修改后自动保存
  }

  const selectDrawing = (x: number, y: number): boolean => {
    const hit = hitTest(x, y)
    if (hit) {
      selectedId.value = hit.id
    }
    // 未命中时不取消选中，保持当前选中状态（避免点击空白后无法拖拽控制点）
    redraw()
    return hit !== null
  }

  const clearSelection = () => {
    selectedId.value = null
    redraw()
  }

  const initCanvas = () => {
    if (!drawingCanvas.value) return
    const rect = drawingCanvas.value.getBoundingClientRect()
    drawingCanvas.value.width = rect.width
    drawingCanvas.value.height = rect.height
    canvasCtx.value = drawingCanvas.value.getContext('2d')
    redraw()
  }

  const startDraw = (tool: DrawingType) => {
    // 点击已选中的工具时，退出该模式（回到默认状态）
    const nextTool = currentTool.value === tool ? null : tool
    cancelDraw()
    currentTool.value = nextTool
    isDrawing.value = nextTool !== null && nextTool !== 'select'
  }

  const cancelDraw = () => {
    currentTool.value = null
    currentPoints.value = []
    isDrawing.value = false
    mousePosition.value = null
    endDrag()
    redraw()
  }

  const updateMousePosition = (x: number, y: number) => {
    mousePosition.value = { x, y }
    if (isDragging.value) {
      // 拖拽控制点
      updateDrag(x, y)
    } else if (isDrawing.value && currentPoints.value.length > 0) {
      redraw()
    }
  }

  const addPoint = (x: number, y: number) => {
    if (!currentTool.value || currentTool.value === 'select' || !chart.value) return
    const timeScale = chart.value.timeScale()
    const time = timeScale.coordinateToTime(x)
    const price = candleSeries.value?.coordinateToPrice(y)
    if (time == null || price == null || !Number.isFinite(price)) return
    currentPoints.value.push({ time, price })
    mousePosition.value = { x, y }

    if (currentPoints.value.length >= TOOL_POINTS[currentTool.value]) {
      completeDraw()
    } else {
      redraw()
    }
  }

  const completeDraw = async () => {
    if (!currentTool.value || currentTool.value === 'select') return
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
    selectedId.value = obj.id // 画完后自动选中，显示起点/终点控制点
    // 画完后回到默认模式（不拦截事件，允许滑动/缩放图表）
    currentTool.value = null
    currentPoints.value = []
    isDrawing.value = false
    mousePosition.value = null
    redraw()
    await autoSaveDrawings() // 等待保存完成
  }

  // --- Canvas 渲染（rAF 节流，防止频繁事件导致死循环）---
  let rafId: number | null = null

  const redraw = () => {
    if (rafId !== null) return // 已有待执行的重绘，跳过
    rafId = requestAnimationFrame(() => {
      rafId = null
      const ctx = canvasCtx.value
      const canvas = drawingCanvas.value
      if (!ctx || !canvas) return
      ctx.clearRect(0, 0, canvas.width, canvas.height)
      for (const d of drawings.value) {
        if (d.visible) drawObject(ctx, d, canvas, d.id === selectedId.value)
      }
      // 绘制当前正在画的控制点
      if (isDrawing.value) {
        drawPreviewPoints(ctx)
        drawPreviewLine(ctx, canvas)
      }
    })
  }

  // --- 绘制控制点标记 ---
  const drawControlPoints = (ctx: CanvasRenderingContext2D, pts: { x: number; y: number }[]) => {
    ctx.save()
    for (const p of pts) {
      ctx.beginPath()
      ctx.arc(p.x, p.y, 4, 0, Math.PI * 2)
      ctx.fillStyle = '#ffffff'
      ctx.fill()
      ctx.strokeStyle = '#58a6ff'
      ctx.lineWidth = 2
      ctx.stroke()
    }
    ctx.restore()
  }

  // --- 绘制预览中的控制点（正在画时）---
  const drawPreviewPoints = (ctx: CanvasRenderingContext2D) => {
    const pts = currentPoints.value.map(p => toScreen(p.time, p.price))
    if (pts.length > 0) {
      drawControlPoints(ctx, pts)
    }
  }

  // --- 绘制预览线（随鼠标移动的橡皮筋效果）---
  const drawPreviewLine = (ctx: CanvasRenderingContext2D, canvas: HTMLCanvasElement) => {
    if (!currentTool.value || currentPoints.value.length === 0 || !mousePosition.value) return

    const tool = currentTool.value
    // 将 time/price 转换为屏幕坐标
    const pts = currentPoints.value.map(p => toScreen(p.time, p.price))
    const mouse = mousePosition.value

    ctx.save()
    ctx.strokeStyle = DEFAULT_COLOR
    ctx.lineWidth = 1.5
    ctx.setLineDash([4, 3]) // 虚线表示预览

    const first = pts[0]

    switch (tool) {
      case 'trend':
      case 'arrow':
      case 'ray': {
        // 从起点到鼠标位置的线段
        ctx.beginPath()
        ctx.moveTo(first.x, first.y)
        ctx.lineTo(mouse.x, mouse.y)
        ctx.stroke()
        // 箭头预览：在鼠标位置画箭头头部
        if (tool === 'arrow') {
          const angle = Math.atan2(mouse.y - first.y, mouse.x - first.x)
          const headLen = 10
          ctx.beginPath()
          ctx.moveTo(mouse.x, mouse.y)
          ctx.lineTo(mouse.x - headLen * Math.cos(angle - Math.PI / 6), mouse.y - headLen * Math.sin(angle - Math.PI / 6))
          ctx.moveTo(mouse.x, mouse.y)
          ctx.lineTo(mouse.x - headLen * Math.cos(angle + Math.PI / 6), mouse.y - headLen * Math.sin(angle + Math.PI / 6))
          ctx.stroke()
        }
        break
      }
      case 'horizontal': {
        // 水平线：通过起点的 y，贯穿画布
        ctx.beginPath()
        ctx.moveTo(0, first.y)
        ctx.lineTo(canvas.width, first.y)
        ctx.stroke()
        break
      }
      case 'vertical': {
        // 垂直线：通过起点的 x，贯穿画布
        ctx.beginPath()
        ctx.moveTo(first.x, 0)
        ctx.lineTo(first.x, canvas.height)
        ctx.stroke()
        break
      }
      case 'rectangle': {
        ctx.beginPath()
        ctx.rect(first.x, first.y, mouse.x - first.x, mouse.y - first.y)
        ctx.stroke()
        break
      }
      case 'fibonacci': {
        const x1 = Math.min(first.x, mouse.x), x2 = Math.max(first.x, mouse.x)
        const y1 = first.y, y2 = mouse.y
        const diff = y2 - y1
        if (Math.abs(diff) > 1e-3) {
          ctx.font = '10px monospace'
          for (const level of [0, 0.236, 0.382, 0.5, 0.618, 1]) {
            const y = y1 + diff * level
            ctx.beginPath()
            ctx.moveTo(x1, y); ctx.lineTo(x2, y)
            ctx.stroke()
            ctx.fillText(`${(level * 100).toFixed(1)}%`, x2 + 4, y + 3)
          }
        }
        break
      }
      case 'triangle': {
        if (pts.length >= 2) {
          // 已确定 2 个点：预览第三条边和闭合
          const second = pts[1]
          ctx.beginPath()
          ctx.moveTo(first.x, first.y)
          ctx.lineTo(second.x, second.y)
          ctx.lineTo(mouse.x, mouse.y)
          ctx.closePath()
          ctx.stroke()
        } else {
          // 只确定 1 个点：预览从起点到鼠标的线
          ctx.beginPath()
          ctx.moveTo(first.x, first.y)
          ctx.lineTo(mouse.x, mouse.y)
          ctx.stroke()
        }
        break
      }
      case 'channel': {
        if (pts.length >= 2) {
          // 已确定 2 个点：预览通道线（过鼠标点作平行线）
          const second = pts[1]
          const dx = second.x - first.x, dy = second.y - first.y
          ctx.beginPath()
          ctx.moveTo(first.x, first.y)
          ctx.lineTo(second.x, second.y)
          ctx.stroke()
          // 过鼠标点的平行线，延伸到画布边缘
          let tMin = -Infinity, tMax = Infinity
          if (Math.abs(dx) > 1e-6) {
            const t1 = (0 - mouse.x) / dx
            const t2 = (canvas.width - mouse.x) / dx
            tMin = Math.max(tMin, Math.min(t1, t2))
            tMax = Math.min(tMax, Math.max(t1, t2))
          }
          if (Math.abs(dy) > 1e-6) {
            const t1 = (0 - mouse.y) / dy
            const t2 = (canvas.height - mouse.y) / dy
            tMin = Math.max(tMin, Math.min(t1, t2))
            tMax = Math.min(tMax, Math.max(t1, t2))
          }
          ctx.beginPath()
          ctx.moveTo(mouse.x + dx * tMin, mouse.y + dy * tMin)
          ctx.lineTo(mouse.x + dx * tMax, mouse.y + dy * tMax)
          ctx.stroke()
        } else {
          ctx.beginPath()
          ctx.moveTo(first.x, first.y)
          ctx.lineTo(mouse.x, mouse.y)
          ctx.stroke()
        }
        break
      }
      // 'text' 工具无需预览线
    }
    ctx.restore()
  }

  const drawObject = (ctx: CanvasRenderingContext2D, obj: DrawingObject, canvas: HTMLCanvasElement, showPoints: boolean) => {
    ctx.strokeStyle = obj.color
    ctx.fillStyle = obj.color
    ctx.lineWidth = obj.lineWidth
    ctx.setLineDash(obj.lineStyle === 'dashed' ? [6, 4] : obj.lineStyle === 'dotted' ? [2, 2] : [])

    // 将 time/price 转换为当前屏幕坐标
    const pts = obj.points.map(p => toScreen(p.time, p.price))

    ctx.beginPath()

    switch (obj.type) {
      case 'trend':
        if (pts.length >= 2) { ctx.moveTo(pts[0].x, pts[0].y); ctx.lineTo(pts[1].x, pts[1].y) }
        break
      case 'arrow':
        if (pts.length >= 2) {
          ctx.moveTo(pts[0].x, pts[0].y); ctx.lineTo(pts[1].x, pts[1].y)
          const angle = Math.atan2(pts[1].y - pts[0].y, pts[1].x - pts[0].x)
          const headLen = 10
          ctx.moveTo(pts[1].x, pts[1].y)
          ctx.lineTo(pts[1].x - headLen * Math.cos(angle - Math.PI / 6), pts[1].y - headLen * Math.sin(angle - Math.PI / 6))
          ctx.moveTo(pts[1].x, pts[1].y)
          ctx.lineTo(pts[1].x - headLen * Math.cos(angle + Math.PI / 6), pts[1].y - headLen * Math.sin(angle + Math.PI / 6))
        }
        break
      case 'horizontal': {
        // 水平线：用 price 决定 y，贯穿整个画布宽度
        const { y } = toScreen(obj.points[0].time, obj.points[0].price)
        ctx.moveTo(0, y); ctx.lineTo(canvas.width, y)
        ctx.font = '11px monospace'
        ctx.fillText(obj.points[0].price?.toFixed(2) || '', 10, y - 4)
        break
      }
      case 'vertical': {
        // 垂直线：用 time 决定 x，贯穿整个画布高度
        const { x } = toScreen(obj.points[0].time, obj.points[0].price)
        ctx.moveTo(x, 0); ctx.lineTo(x, canvas.height)
        break
      }
      case 'ray':
        if (pts.length >= 2) {
          const dx = pts[1].x - pts[0].x
          const dy = pts[1].y - pts[0].y
          // 计算射线与画布边界的交点，避免绘制超出画布
          let endX = pts[0].x + dx * 100
          let endY = pts[0].y + dy * 100
          // 裁剪到画布边界内
          if (endX < 0) { endY = pts[0].y + (0 - pts[0].x) * dy / dx; endX = 0 }
          else if (endX > canvas.width) { endY = pts[0].y + (canvas.width - pts[0].x) * dy / dx; endX = canvas.width }
          if (endY < 0) { endX = pts[0].x + (0 - pts[0].y) * dx / dy; endY = 0 }
          else if (endY > canvas.height) { endX = pts[0].x + (canvas.height - pts[0].y) * dx / dy; endY = canvas.height }
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
          const dx = pts[1].x - pts[0].x, dy = pts[1].y - pts[0].y
          const px = pts[2].x, py = pts[2].y
          let tMin = -Infinity, tMax = Infinity
          if (Math.abs(dx) > 1e-6) {
            const t1 = (0 - px) / dx
            const t2 = (canvas.width - px) / dx
            tMin = Math.max(tMin, Math.min(t1, t2))
            tMax = Math.min(tMax, Math.max(t1, t2))
          }
          if (Math.abs(dy) > 1e-6) {
            const t1 = (0 - py) / dy
            const t2 = (canvas.height - py) / dy
            tMin = Math.max(tMin, Math.min(t1, t2))
            tMax = Math.min(tMax, Math.max(t1, t2))
          }
          ctx.moveTo(px + dx * tMin, py + dy * tMin)
          ctx.lineTo(px + dx * tMax, py + dy * tMax)
        }
        break
      case 'fibonacci':
        if (pts.length >= 2) {
          const x1 = Math.min(pts[0].x, pts[1].x), x2 = Math.max(pts[0].x, pts[1].x)
          const y1 = pts[0].y, y2 = pts[1].y
          const diff = y2 - y1
          if (Math.abs(diff) > 1e-3) {
            for (const level of [0, 0.236, 0.382, 0.5, 0.618, 1]) {
              const y = y1 + diff * level
              ctx.moveTo(x1, y); ctx.lineTo(x2, y)
              ctx.font = '10px monospace'
              ctx.fillText(`${(level * 100).toFixed(1)}%`, x2 + 4, y + 3)
            }
          }
        }
        break
      case 'text':
        ctx.font = '13px sans-serif'
        ctx.fillText(obj.text || '', pts[0].x, pts[0].y)
        break
    }
    ctx.stroke()

    // 选中时绘制控制点
    if (showPoints) {
      drawControlPoints(ctx, pts)
    }
  }

  // --- 监听图表平移/缩放，自动重绘 ---
  let unsubscribeVisibleRange: (() => void) | null = null

  const subscribeChartMove = () => {
    unsubscribeChartMove()
    if (!chart.value) return
    const timeScale = chart.value.timeScale()
    const onChartChange = () => redraw()
    timeScale.subscribeVisibleLogicalRangeChange(onChartChange)
    unsubscribeVisibleRange = () => timeScale.unsubscribeVisibleLogicalRangeChange(onChartChange)
  }

  const unsubscribeChartMove = () => {
    unsubscribeVisibleRange?.()
    unsubscribeVisibleRange = null
  }

  const disposeDrawingTools = () => {
    unsubscribeChartMove()
    ++loadVersion.value
    if (rafId !== null) cancelAnimationFrame(rafId)
    rafId = null
    canvasCtx.value = null
  }

  // --- 持久化 ---
  // localStorage key（作为即时保存和后援）
  const LOCAL_KEY = 'kline_drawings'

  // 同步保存到 localStorage（防止刷新/关闭时数据丢失）
  const saveDrawingsLocal = (stockCode: string, period: string) => {
    try {
      const map = JSON.parse(localStorage.getItem(LOCAL_KEY) || '{}')
      map[`${stockCode}|${period}`] = drawings.value
      localStorage.setItem(LOCAL_KEY, JSON.stringify(map))
    } catch (e) {
      console.error('[saveDrawingsLocal] 失败', e)
    }
  }

  // 从 localStorage 同步加载
  const loadDrawingsLocal = (stockCode: string, period: string): DrawingObject[] => {
    try {
      const map = JSON.parse(localStorage.getItem(LOCAL_KEY) || '{}')
      return map[`${stockCode}|${period}`] || []
    } catch {
      return []
    }
  }

  // 清理损坏的画线数据（移除 points 中的 x, y）
  const cleanDrawingsData = (data: any[]): DrawingObject[] => {
    return data.map(d => ({
      ...d,
      points: d.points.map((p: any) => ({
        time: p.time,
        price: p.price,
      })),
    }))
  }

  const saveDrawings = async (stockCode: string, period: string) => {
    // 立即同步到 localStorage（防止刷新丢失）
    saveDrawingsLocal(stockCode, period)
    const userId = Number(localStorage.getItem('userId') || '0')
    if (userId <= 0) return
    try {
      // 捕获快照，避免 await 期间 drawings.value 被其他操作修改
      const dataSnapshot = JSON.stringify(drawings.value)
      await saveDrawing({ userId, stockCode, period, data: dataSnapshot })
    } catch (e) {
      console.error('[useDrawingTools] 保存画线失败:', e)
    }
  }

  const loadDrawings = async (stockCode: string, period: string) => {
    // 成功和失败路径都必须受版本保护，包括切换前等待保存的阶段。
    const version = ++loadVersion.value
    try {
      const userId = Number(localStorage.getItem('userId') || '0')
      const prevKey = saveKey.value
      // 切换前，先保存旧股票的画线数据并等待完成（避免清空后保存空数据）
      if (prevKey && prevKey.stockCode !== stockCode && drawings.value.length > 0) {
        await saveDrawings(prevKey.stockCode, prevKey.period)
      }
      if (version !== loadVersion.value) return
      // 立即清空画布上的旧数据（防止残留）
      drawings.value = []
      selectedId.value = null
      redraw()
      // 记录保存 key
      saveKey.value = { stockCode, period }
      if (userId <= 0) {
        // 未登录，从 localStorage 加载
        drawings.value = loadDrawingsLocal(stockCode, period)
        redraw()
        return
      }
      const res = await loadDrawing(userId, stockCode, period)
      // 版本已变化，说明已切换到其他股票，放弃本次结果
      if (version !== loadVersion.value) return
      if (res.code === 200 && res.data?.data) {
        let backendData = JSON.parse(res.data.data || '[]')
        // 清理损坏数据：移除 points 中的 x, y，只保留 time, price
        backendData = cleanDrawingsData(backendData)
        // 合并：优先使用 localStorage 数据（更新），后端数据作为补充
        const localData = loadDrawingsLocal(stockCode, period)
        drawings.value = localData.length > backendData.length ? localData : backendData
        redraw()
      }
    } catch (e) {
      if (version !== loadVersion.value) return
      console.error('[useDrawingTools] 加载画线失败:', e)
      // 出错时尝试从 localStorage 加载
      drawings.value = loadDrawingsLocal(stockCode, period)
      redraw()
    }
  }

  const deleteDrawing = (id: string) => {
    drawings.value = drawings.value.filter(d => d.id !== id)
    if (selectedId.value === id) selectedId.value = null
    redraw()
    autoSaveDrawings() // 删除后自动保存
  }

  const clearDrawings = () => {
    drawings.value = []
    selectedId.value = null
    redraw()
    autoSaveDrawings() // 清空后自动保存
  }

  // --- 自动保存当前股票的画线 ---
  const autoSaveDrawings = async () => {
    if (!saveKey.value) return
    await saveDrawings(saveKey.value.stockCode, saveKey.value.period)
  }

  return {
    drawings,
    currentTool,
    isDrawing,
    selectedId,
    isDragging,
    initCanvas,
    startDraw,
    cancelDraw,
    addPoint,
    updateMousePosition,
    selectDrawing,
    clearSelection,
    startDrag,
    endDrag,
    deleteDrawing,
    clearDrawings,
    saveDrawings,
    loadDrawings,
    redraw,
    subscribeChartMove,
    unsubscribeChartMove,
    disposeDrawingTools
  }
}
