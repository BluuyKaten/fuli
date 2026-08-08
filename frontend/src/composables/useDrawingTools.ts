// useDrawingTools.ts
import { ref, type Ref } from 'vue'
import type { IChartApi, ISeriesApi } from 'lightweight-charts'
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
  drawingCanvas: Ref<HTMLCanvasElement | null>,
  candleSeries: Ref<ISeriesApi<any> | null>
) {
  const drawings = ref<DrawingObject[]>([])
  const currentTool = ref<DrawingType | null>(null)
  const currentPoints = ref<DrawingPoint[]>([])
  const isDrawing = ref(false)
  const selectedId = ref<string | null>(null)
  const DEFAULT_COLOR = '#58a6ff'

  const canvasCtx = ref<CanvasRenderingContext2D | null>(null)

  // --- 坐标转换：time/price → 屏幕 x/y ---
  const toScreen = (time: number | string | undefined, price: number | undefined): { x: number; y: number } => {
    const c = chart.value
    const s = candleSeries.value
    if (!c || !s) return { x: 0, y: 0 }
    const x = time != null ? c.timeScale().timeToCoordinate(time as any) ?? 0 : 0
    const y = price != null ? s.priceToCoordinate(price) ?? 0 : 0
    return { x, y }
  }

  // --- 点击命中检测：判断点是否在某条画线附近 ---
  const HIT_RADIUS = 8 // 命中半径（像素）

  const hitTest = (x: number, y: number): DrawingObject | null => {
    for (const d of drawings.value) {
      if (!d.visible) continue
      const pts = d.points.map(p => toScreen(p.time, p.price))
      // 检查是否靠近任一控制点
      for (const pt of pts) {
        if (Math.hypot(pt.x - x, pt.y - y) < HIT_RADIUS) return d
      }
      // 检查是否靠近线段（端点之间的连线）
      for (let i = 0; i < pts.length - 1; i++) {
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

  const selectDrawing = (x: number, y: number): boolean => {
    const hit = hitTest(x, y)
    selectedId.value = hit ? hit.id : null
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
    const time = timeScale.coordinateToTime(x)
    const price = candleSeries.value?.coordinateToPrice(y)
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
    const pts = currentPoints.value.map(p => ({ x: p.x, y: p.y }))
    if (pts.length > 0) {
      drawControlPoints(ctx, pts)
    }
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
    selectedId,
    initCanvas,
    startDraw,
    cancelDraw,
    addPoint,
    selectDrawing,
    clearSelection,
    deleteDrawing,
    clearDrawings,
    saveDrawings,
    loadDrawings,
    redraw,
    subscribeChartMove,
    unsubscribeChartMove
  }
}
