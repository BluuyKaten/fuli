# 0001 - 设计令牌运行时注入 + gsap 统一编排动效

- 状态：已采纳
- 日期：2026-11-02

## 背景

前端 `frontend/` 的界面原本是「GitHub Dark」终端风：色值分散在三处（`App.vue` 的 `:root` 变量、Ant Design `themeConfig`、各页面 scoped 样式里的硬编码十六进制），全项目没有任何 `@keyframes` 或 `<transition>`，动效基础设施为零。

要做整体视觉升级，必须先解决两个绕不开的问题：

1. **色值双份维护**：AntD 的 token 是 JS 对象，CSS 变量在样式表里，两者无法互相引用，任何改色都要改两遍，改漏一处就出现色差。
2. **动效一致性的地基**：一旦允许多种方式写动画（CSS keyframes、Vue Transition、动画库、行内 style），节奏（时长/缓动）就会漂移，最终看起来像几个不同产品拼在一起。

## 决策

1. **令牌只有一个真相源**：`src/theme/tokens.ts` 存放全部色板与尺度常量，`main.ts` 启动时调用 `injectThemeVars()` 把值写进 `document.documentElement.style`；`src/theme/antd.ts` 从同一份常量派生 AntD 主题。`src/styles/tokens.css` 只做首屏防闪烁的静态兜底副本。
2. **变量名沿用 `--term-*`**：不重命名，保证未改造的 8 个页面自动继承新配色。
3. **动效统一走 `src/theme/motion.ts`**：所有动画都是该模块导出的函数（`revealIn` / `pageEnter` / `countTo` / `flashTick` / `flipLayout`），页面不直接调用 gsap API，也不写死时长。
4. **只引入 gsap（含内置 Flip），不引入 animate.css**：animate.css 的缓动写死，无法与 gsap timeline 串联编排，且会多 70KB CSS。不用 ScrollTrigger：本应用全是 100vh 固定视口 + 内部滚动，没有长滚动叙事页。
5. **动效强度限定为「中档」**：容器层动效允许（面板入场、分隔条发光、布局 Flip、数字滚动、价格闪烁），K 线 canvas 内部不做逐根动画。

## 备选方案与取舍

- **手写 `tokens.css` + 在 `theme.ts` 里再写一份给 AntD**（否决）：可读性好，但两份必然漂移——这正是改造前的问题。
- **纯 CSS + Vue Transition，零依赖**（否决）：中档动效里「侧栏折叠重排」「dock 项增删」这类布局变化，纯 CSS 只能硬切，Flip 才能做出连续感。
- **animate.css + gsap 双体系**（否决）：能力重叠，节奏难统一。
- **在 K 线 canvas 内做逐根 K 线入场**（否决）：会与 WebSocket 实时推送互相打断，且 `LightweightChart.vue`（954 行）回归成本过高。

## 影响

- 改色只需改 `tokens.ts`（并同步 `tokens.css` 的兜底值），AntD 与自定义样式同步生效。
- 新增动画必须走 `motion.ts`，否则节奏会失控；`motion.ts` 内部统一处理 `prefers-reduced-motion`，页面不需要各写一遍。
- 打包体积增加 gsap core + Flip（约 70KB min）。
- 组件库层面的 AntD 覆盖仍依赖 `!important`（AntD 样式在运行时注入 `<head>` 末尾，静态 CSS 优先级低于它），这是既有约束，不是本次引入的。
