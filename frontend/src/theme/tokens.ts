/**
 * 设计令牌 —— 全站颜色的唯一真相源。
 *
 * 约定：
 * 1. 任何组件 / 页面都不要再写死十六进制色值，一律引用 CSS 变量 var(--term-*)。
 * 2. `src/styles/tokens.css` 是首屏防闪烁的静态兜底副本，由本文件的值手工同步，
 *    两者必须一致；运行时 main.ts 会用本文件的值覆盖 :root。
 * 3. 变量名沿用改造前的 `--term-*` 前缀，保证未改造的页面自动继承新配色。
 */

/** 原始色板：只在这里写十六进制值 */
export const palette = {
  /* 背景层 */
  bg: '#070a12',
  bgDeep: '#04060c',
  bgPanel: '#0f1420',
  bgPanelHeader: '#121a2b',
  bgElevated: '#151d30',
  bgHover: '#18213a',

  /* 描边 */
  border: '#1e2740',
  borderStrong: '#2a3550',

  /* 文本 */
  fg: '#e6edf6',
  fgMuted: '#8b9bb4',
  fgDim: '#5c6b85',

  /* 强调色：青蓝 → 紫罗兰 */
  accent: '#38bdf8',
  accentAlt: '#a78bfa',
  accentDeep: '#0ea5e9',

  /* 语义色 */
  up: '#ff4d6d',
  down: '#00e5a0',
  warning: '#fbbf24',
  danger: '#f43f5e',
  success: '#22c55e',
  info: '#38bdf8'
} as const

/** 透明度工具：把十六进制色转成 rgba 字符串 */
export const alpha = (hex: string, a: number): string => {
  const h = hex.replace('#', '')
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h
  const r = parseInt(full.slice(0, 2), 16)
  const g = parseInt(full.slice(2, 4), 16)
  const b = parseInt(full.slice(4, 6), 16)
  return `rgba(${r}, ${g}, ${b}, ${a})`
}

/** 间距 / 圆角 / 阴影 / 动效时长 */
export const scale = {
  radius: '8px',
  radiusSm: '5px',
  radiusLg: '14px',

  shadow: '0 14px 38px rgba(0, 0, 0, 0.5)',
  shadowSm: '0 4px 14px rgba(0, 0, 0, 0.35)',

  durFast: '160ms',
  durBase: '300ms',
  durSlow: '520ms',
  ease: 'cubic-bezier(0.16, 1, 0.3, 1)'
} as const

/** 运行时注入到 :root 的 CSS 变量表 */
export const cssVars: Record<string, string> = {
  /* —— 兼容旧变量的核心色 —— */
  '--term-bg': palette.bg,
  '--term-fg': palette.fg,
  '--term-fg-muted': palette.fgMuted,
  '--term-accent': palette.accent,
  '--term-border': palette.border,
  '--term-hover': palette.bgHover,
  '--term-active': alpha(palette.accent, 0.12),

  '--term-toolbar-bg': palette.bgPanelHeader,
  '--term-sidebar-bg': palette.bgDeep,
  '--term-dock-bg': palette.bgDeep,
  '--term-panel-bg': palette.bgPanel,
  '--term-panel-header': palette.bgPanelHeader,

  '--term-danger': palette.danger,
  '--term-success': palette.success,
  '--term-warning': palette.warning,
  '--term-info': palette.info,

  /* 涨跌：红涨绿跌（中国习惯），霓虹版 */
  '--term-up': palette.up,
  '--term-down': palette.down,

  /* —— 霓虹扩展变量 —— */
  '--term-fg-dim': palette.fgDim,
  '--term-bg-deep': palette.bgDeep,
  '--term-bg-elevated': palette.bgElevated,
  '--term-border-strong': palette.borderStrong,
  '--term-accent-2': palette.accentAlt,

  '--term-grad': `linear-gradient(135deg, ${palette.accent} 0%, ${palette.accentAlt} 100%)`,
  '--term-grad-line': `linear-gradient(90deg, ${alpha(palette.accent, 0)} 0%, ${palette.accent} 35%, ${palette.accentAlt} 65%, ${alpha(palette.accentAlt, 0)} 100%)`,
  '--term-grad-panel': `linear-gradient(180deg, ${alpha(palette.accent, 0.06)} 0%, rgba(0, 0, 0, 0) 42%)`,

  '--term-glow': alpha(palette.accent, 0.3),
  '--term-glow-2': alpha(palette.accentAlt, 0.26),
  '--term-glow-up': alpha(palette.up, 0.3),
  '--term-glow-down': alpha(palette.down, 0.28),

  '--term-grid-line': alpha(palette.accent, 0.045),
  '--term-ring': `0 0 0 1px ${alpha(palette.accent, 0.45)}, 0 0 18px ${alpha(palette.accent, 0.18)}`,

  /* —— 尺度与字体 —— */
  '--term-radius': scale.radius,
  '--term-radius-sm': scale.radiusSm,
  '--term-radius-lg': scale.radiusLg,
  '--term-shadow': scale.shadow,
  '--term-shadow-sm': scale.shadowSm,

  '--term-dur-fast': scale.durFast,
  '--term-dur': scale.durBase,
  '--term-dur-slow': scale.durSlow,
  '--term-ease': scale.ease,

  '--term-font-sans': "'Inter', 'PingFang SC', 'Microsoft YaHei', system-ui, -apple-system, sans-serif",
  '--term-font-mono': "'JetBrains Mono', 'Fira Code', 'SF Mono', ui-monospace, Menlo, Consolas, monospace"
}

/**
 * 把令牌写进 documentElement 的 inline style。
 * inline 优先级高于 tokens.css 的 :root，因此这里是生效值，tokens.css 只负责首屏兜底。
 */
export function injectThemeVars(target: HTMLElement = document.documentElement): void {
  for (const [name, value] of Object.entries(cssVars)) {
    target.style.setProperty(name, value)
  }
}
