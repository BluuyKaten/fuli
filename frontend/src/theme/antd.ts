/**
 * Ant Design Vue 主题配置 —— 与 tokens.ts 共用同一份色板，避免两套色值漂移。
 */
import { theme } from 'ant-design-vue'
import { palette, alpha } from './tokens'

export const antdTheme = {
  algorithm: theme.darkAlgorithm,
  token: {
    colorPrimary: palette.accent,
    colorInfo: palette.info,
    colorSuccess: palette.success,
    colorWarning: palette.warning,
    colorError: palette.danger,
    colorLink: palette.accent,
    colorLinkHover: palette.accentAlt,

    colorBgBase: palette.bg,
    colorBgContainer: palette.bgPanel,
    colorBgElevated: palette.bgElevated,
    colorBgSpotlight: palette.bgElevated,
    colorBgLayout: palette.bg,

    colorTextBase: palette.fg,
    colorText: palette.fg,
    colorTextSecondary: palette.fgMuted,
    colorTextTertiary: palette.fgDim,
    colorBorder: palette.border,
    colorBorderSecondary: palette.border,
    colorSplit: palette.border,

    borderRadius: 6,
    borderRadiusLG: 10,
    controlHeight: 32,
    fontSize: 13,
    fontFamily:
      "'Inter', 'PingFang SC', 'Microsoft YaHei', system-ui, -apple-system, sans-serif",
    wireframe: false
  },
  components: {
    Layout: {
      headerBg: palette.bgPanelHeader,
      siderBg: palette.bgDeep,
      bodyBg: palette.bg
    },
    Menu: {
      darkItemBg: palette.bgPanel,
      darkSubMenuItemBg: palette.bgDeep,
      darkItemSelectedBg: alpha(palette.accent, 0.14),
      darkItemSelectedColor: palette.accent,
      darkItemHoverBg: palette.bgHover,
      itemBg: palette.bgPanel,
      itemSelectedBg: alpha(palette.accent, 0.14),
      itemSelectedColor: palette.accent,
      itemHoverBg: palette.bgHover,
      popupBg: palette.bgPanel
    },
    Card: {
      colorBgContainer: palette.bgPanel,
      colorBorderSecondary: palette.border,
      headerBg: palette.bgPanelHeader,
      headerFontSize: 13
    },
    Table: {
      headerBg: palette.bgPanelHeader,
      headerColor: palette.fgMuted,
      rowHoverBg: palette.bgHover,
      borderColor: palette.border,
      headerSplitColor: palette.border,
      colorBgContainer: palette.bgPanel
    },
    Modal: {
      contentBg: palette.bgPanel,
      headerBg: palette.bgPanel,
      titleColor: palette.fg
    },
    Input: {
      colorBgContainer: palette.bgDeep,
      activeBorderColor: palette.accent,
      hoverBorderColor: palette.borderStrong,
      activeShadow: `0 0 0 2px ${alpha(palette.accent, 0.18)}`
    },
    InputNumber: {
      colorBgContainer: palette.bgDeep,
      activeBorderColor: palette.accent,
      hoverBorderColor: palette.borderStrong
    },
    Select: {
      colorBgContainer: palette.bgDeep,
      optionSelectedBg: alpha(palette.accent, 0.14),
      optionSelectedColor: palette.accent,
      optionActiveBg: palette.bgHover
    },
    DatePicker: {
      colorBgContainer: palette.bgDeep,
      activeBorderColor: palette.accent
    },
    Button: {
      fontWeight: 500,
      primaryShadow: `0 0 0 1px ${alpha(palette.accent, 0.25)}, 0 4px 16px ${alpha(palette.accent, 0.22)}`,
      defaultBg: palette.bgPanelHeader,
      defaultBorderColor: palette.border,
      defaultColor: palette.fg,
      dangerShadow: `0 0 0 1px ${alpha(palette.danger, 0.3)}, 0 4px 16px ${alpha(palette.danger, 0.2)}`
    },
    Tabs: {
      itemColor: palette.fgMuted,
      itemSelectedColor: palette.accent,
      itemHoverColor: palette.fg,
      inkBarColor: palette.accent
    },
    Tooltip: {
      colorBgSpotlight: palette.bgElevated,
      colorTextLightSolid: palette.fg
    },
    Popover: {
      colorBgElevated: palette.bgPanel
    },
    Statistic: {
      titleFontSize: 12,
      contentFontSize: 24
    },
    Spin: {
      colorPrimary: palette.accent
    },
    Tag: {
      defaultBg: palette.bgPanelHeader,
      defaultColor: palette.fg
    },
    Divider: {
      colorSplit: palette.border
    }
  }
} as const
