/**
 * 线性图标集 —— 替代原先散落在界面里的 emoji。
 * 统一 24×24 视框、圆角端点，颜色继承 currentColor。
 * 新增图标：在 ICONS 里加一条 inner SVG 字符串即可，无需注册。
 */
export const ICONS = {
  /* 导航 */
  dashboard:
    '<rect x="3" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1.6"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.6"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1.6"/>',
  candle:
    '<path d="M7 4v3M7 17v3M17 3v4M17 16v5"/><rect x="4.5" y="7" width="5" height="10" rx="1"/><rect x="14.5" y="7" width="5" height="9" rx="1"/>',
  target:
    '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="5"/><circle cx="12" cy="12" r="1.2" fill="currentColor" stroke="none"/>',
  clipboard:
    '<path d="M9 4H7a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V6a2 2 0 0 0-2-2h-2"/><rect x="9" y="2.5" width="6" height="3.5" rx="1.2"/><path d="M9 12h6M9 16h4"/>',
  notebook:
    '<path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H19a1 1 0 0 1 1 1v15a1 1 0 0 1-1 1H6.5A2.5 2.5 0 0 1 4 17.5z"/><path d="M8 3v18M11.5 8h5M11.5 12h5"/>',
  shield:
    '<path d="M12 2.8 4.5 6v6c0 4.4 3.1 7.8 7.5 9.2 4.4-1.4 7.5-4.8 7.5-9.2V6z"/><path d="M12 8.6v3.2M12 15.2h.01"/>',
  trophy:
    '<path d="M7 4h10v5a5 5 0 0 1-10 0z"/><path d="M7 5.5H4.6a.6.6 0 0 0-.6.6c0 2.4 1.6 4.2 3.8 4.6M17 5.5h2.4a.6.6 0 0 1 .6.6c0 2.4-1.6 4.2-3.8 4.6"/><path d="M12 14v3.5M8.5 21h7"/>',
  refresh: '<path d="M20.5 12a8.5 8.5 0 1 1-2.6-6.1"/><path d="M20.5 4v5h-5"/>',
  user: '<circle cx="12" cy="8" r="4"/><path d="M4.5 21a7.5 7.5 0 0 1 15 0"/>',
  users:
    '<circle cx="9" cy="8" r="3.6"/><path d="M2.5 20.5a6.5 6.5 0 0 1 13 0"/><path d="M17 5.2a3.6 3.6 0 0 1 0 6.6M18.5 14.4a6.5 6.5 0 0 1 3 5.1"/>',

  /* 操作 */
  lock: '<rect x="4" y="10.5" width="16" height="10.5" rx="2.2"/><path d="M7.8 10.5V7.4a4.2 4.2 0 0 1 8.4 0v3.1"/><path d="M12 15v2.4"/>',
  logout:
    '<path d="M9.5 21H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h3.5"/><path d="M15.5 16.5 20 12l-4.5-4.5"/><path d="M20 12H9"/>',
  search: '<circle cx="10.8" cy="10.8" r="6.8"/><path d="m20 20-4.2-4.2"/>',
  panelLeft:
    '<rect x="3" y="4" width="18" height="16" rx="2.2"/><path d="M9.2 4v16"/><path d="M13.4 10 16 12l-2.6 2"/>',
  chevronLeft: '<path d="m14.5 5-7 7 7 7"/>',
  chevronRight: '<path d="m9.5 5 7 7-7 7"/>',
  chevronDown: '<path d="m5 9.5 7 7 7-7"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  minus: '<path d="M5 12h14"/>',
  close: '<path d="M6 6l12 12M18 6 6 18"/>',
  check: '<path d="m4.5 12.5 5 5 10-11"/>',
  maximize:
    '<path d="M4 9V4.8A.8.8 0 0 1 4.8 4H9M15 4h4.2a.8.8 0 0 1 .8.8V9M20 15v4.2a.8.8 0 0 1-.8.8H15M9 20H4.8a.8.8 0 0 1-.8-.8V15"/>',

  /* 状态与信息 */
  activity: '<path d="M22 12h-4.2l-2.6 7L10.8 5 8.2 12H2"/>',
  pulse:
    '<circle cx="12" cy="12" r="2.6"/><path d="M7.2 7.2a6.8 6.8 0 0 0 0 9.6M16.8 16.8a6.8 6.8 0 0 0 0-9.6"/><path d="M4 4a11.3 11.3 0 0 0 0 16M20 20a11.3 11.3 0 0 0 0-16"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5.2l3.4 2"/>',
  sun: '<circle cx="12" cy="12" r="4.2"/><path d="M12 2.6v2.2M12 19.2v2.2M4.6 12H2.4M21.6 12h-2.2M6.4 6.4 4.8 4.8M19.2 19.2l-1.6-1.6M17.6 6.4l1.6-1.6M4.8 19.2l1.6-1.6"/>',
  moon: '<path d="M20.5 14.8A8.6 8.6 0 0 1 9.2 3.5a8.7 8.7 0 1 0 11.3 11.3z"/>',
  bell: '<path d="M18 8.5a6 6 0 1 0-12 0c0 5.5-2 6.8-2 6.8h16s-2-1.3-2-6.8"/><path d="M13.7 19.2a2 2 0 0 1-3.4 0"/>',
  sparkles:
    '<path d="m12 3 1.7 4.6L18.5 9l-4.8 1.4L12 15l-1.7-4.6L5.5 9l4.8-1.4z"/><path d="M18.5 15.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8z"/>',
  layers: '<path d="m12 3 8.5 4.7L12 12.4 3.5 7.7z"/><path d="m3.5 12.3 8.5 4.7 8.5-4.7"/><path d="m3.5 16.9 8.5 4.7 8.5-4.7"/>',
  coin: '<ellipse cx="12" cy="6.5" rx="7.5" ry="3.2"/><path d="M4.5 6.5v5c0 1.8 3.4 3.2 7.5 3.2s7.5-1.4 7.5-3.2v-5"/><path d="M4.5 11.5v5c0 1.8 3.4 3.2 7.5 3.2s7.5-1.4 7.5-3.2v-5"/>',
  trendUp: '<path d="M3 17l5.5-5.5 3.5 3.5L21 6"/><path d="M15.5 6H21v5.5"/>',
  trendDown: '<path d="M3 7l5.5 5.5 3.5-3.5L21 18"/><path d="M15.5 18H21v-5.5"/>',
  wallet:
    '<path d="M3.5 7.5A2.5 2.5 0 0 1 6 5h11.5a2 2 0 0 1 2 2v1"/><rect x="3.5" y="7.5" width="17" height="11.5" rx="2.2"/><path d="M16.5 13.2h.01"/>',
  alert:
    '<path d="M10.3 3.9 2.3 18a2 2 0 0 0 1.7 3h16a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/><path d="M12 9.2v4.2M12 17h.01"/>'
} as const

export type IconName = keyof typeof ICONS
