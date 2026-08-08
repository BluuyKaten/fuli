/**
 * 图表主题类型定义
 */
export interface ChartTheme {
  name: 'dark' | 'light'
  layout: { background: string; textColor: string }
  grid: { vertLines: string; horzLines: string }
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

export type ThemeName = 'dark' | 'light'
