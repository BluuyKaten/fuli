/**
 * 图表主题管理 composable
 * 提供暗色/亮色主题切换、本地存储持久化
 */
import { ref, readonly } from 'vue'
import type { ChartTheme, ThemeName } from '@/types/chart'

const darkTheme: ChartTheme = {
  name: 'dark',
  layout: { background: '#131722', textColor: '#8b949e' },
  grid: { vertLines: '#30363d', horzLines: '#30363d' },
  candle: {
    upColor: '#ef5350',
    downColor: '#26a69a',
    borderUpColor: '#ef5350',
    borderDownColor: '#26a69a',
    wickUpColor: '#ef5350',
    wickDownColor: '#26a69a'
  },
  crosshair: '#58a6ff',
  indicators: {
    ma5: '#ff9800',
    ma10: '#2196f3',
    ma20: '#e91e63',
    ma60: '#9c27b0',
    dif: '#ff9800',
    dea: '#2196f3',
    k: '#ff9800',
    d: '#2196f3',
    j: '#e91e63',
    rsi: '#7c4dff',
    bollUpper: '#ef5350',
    bollMiddle: '#8b949e',
    bollLower: '#26a69a',
    macdUp: '#ef5350',
    macdDown: '#26a69a',
    volumeUp: '#ef535080',
    volumeDown: '#26a69a80'
  }
}

const lightTheme: ChartTheme = {
  name: 'light',
  layout: { background: '#ffffff', textColor: '#333333' },
  grid: { vertLines: '#e0e0e0', horzLines: '#e0e0e0' },
  candle: {
    upColor: '#ef5350',
    downColor: '#26a69a',
    borderUpColor: '#ef5350',
    borderDownColor: '#26a69a',
    wickUpColor: '#ef5350',
    wickDownColor: '#26a69a'
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
