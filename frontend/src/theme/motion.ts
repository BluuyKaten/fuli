/**
 * 动效层 —— 全站动画的唯一入口。
 *
 * 设计原则：
 * 1. 只用 gsap timeline 编排，不引入第二套动画体系；
 * 2. 所有函数内部都会检查 prefers-reduced-motion，用户开了「减弱动效」时直接跳过；
 * 3. 时长/缓动集中在这里定义，页面不要自己写死数字，保证节奏统一。
 */
import gsap from 'gsap'
import { Flip } from 'gsap/Flip'

gsap.registerPlugin(Flip)

/** 缓动与时长（节奏基准） */
export const DUR = {
  fast: 0.18,
  /** 页面级进场 */
  enter: 0.34,
  /** 卡片错峰入场单步间隔 */
  stagger: 0.045,
  /** 页面离场 */
  leave: 0.16
} as const

export const EASE = {
  out: 'power3.out',
  inOut: 'power2.inOut',
  back: 'back.out(1.4)',
  soft: 'power1.out'
} as const

const reduceQuery =
  typeof window !== 'undefined' && window.matchMedia
    ? window.matchMedia('(prefers-reduced-motion: reduce)')
    : null

/** 系统是否开启「减弱动效」 */
export const prefersReducedMotion = (): boolean => !!reduceQuery?.matches

/* ------------------------------------------------------------------ *
 * 1. 卡片 / 列表错峰入场
 * ------------------------------------------------------------------ */

export interface RevealOptions {
  /** 从下方位移的距离，0 表示只淡入 */
  y?: number
  /** 缩放起点 */
  scale?: number
  /** 单步间隔 */
  stagger?: number
  /** 延迟 */
  delay?: number
  /** 整体时长 */
  duration?: number
}

/**
 * 让容器内匹配 selector 的元素依次入场。
 * 典型用法：onMounted 时 revealIn(el, '.stat-card')
 */
export function revealIn(
  container: Element | null | undefined,
  selector: string,
  options: RevealOptions = {}
): gsap.core.Timeline | null {
  if (!container) return null
  const targets = container.querySelectorAll(selector)
  if (!targets.length) return null

  if (prefersReducedMotion()) {
    gsap.set(targets, { opacity: 1, y: 0, scale: 1, clearProps: 'transform,opacity' })
    return null
  }

  const { y = 14, scale = 1, stagger = DUR.stagger, delay = 0, duration = 0.42 } = options
  const tl = gsap.timeline()
  tl.fromTo(
    targets,
    { opacity: 0, y, scale, filter: 'blur(3px)' },
    {
      opacity: 1,
      y: 0,
      scale: 1,
      filter: 'blur(0px)',
      duration,
      delay,
      stagger,
      ease: EASE.out,
      clearProps: 'filter,transform'
    }
  )
  return tl
}

/* ------------------------------------------------------------------ *
 * 2. 路由切换：进场 / 离场
 * ------------------------------------------------------------------ */

/** 页面进场（配合 <Transition :css="false"> 的 enter 钩子） */
export function pageEnter(el: Element, done: () => void, opts: { fade?: boolean } = {}): void {
  if (prefersReducedMotion()) {
    done()
    return
  }
  gsap.fromTo(
    el,
    { opacity: 0, y: opts.fade ? 0 : 10 },
    {
      opacity: 1,
      y: 0,
      duration: DUR.enter,
      ease: EASE.out,
      onComplete: done,
      clearProps: 'transform'
    }
  )
}

/** 页面离场 */
export function pageLeave(el: Element, done: () => void): void {
  if (prefersReducedMotion()) {
    done()
    return
  }
  gsap.to(el, {
    opacity: 0,
    y: -6,
    duration: DUR.leave,
    ease: EASE.inOut,
    onComplete: done
  })
}

/* ------------------------------------------------------------------ *
 * 3. 数字滚动
 * ------------------------------------------------------------------ */

/**
 * 数字滚动到目标值。返回 tween 便于在卸载时 kill。
 * @param el      承载文本的元素
 * @param to      目标数值
 * @param format  (value) => 文本，默认保留两位小数并加千分位
 */
export function countTo(
  el: HTMLElement | null | undefined,
  to: number,
  format?: (value: number) => string,
  duration = 0.9,
  from = 0
): gsap.core.Tween | null {
  if (!el) return null
  const fmt = format ?? ((v: number) => v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }))
  const target = Number.isFinite(to) ? to : 0

  if (prefersReducedMotion()) {
    el.textContent = fmt(target)
    return null
  }

  const state = { value: from }
  return gsap.to(state, {
    value: target,
    duration,
    ease: 'power2.out',
    onUpdate: () => {
      el.textContent = fmt(state.value)
    },
    onComplete: () => {
      el.textContent = fmt(target)
    }
  })
}

/* ------------------------------------------------------------------ *
 * 4. 行情的瞬时反馈
 * ------------------------------------------------------------------ */

let tickTween: gsap.core.Tween | null = null

/** 价格跳动：向上闪红、向下闪绿（红涨绿跌），并轻微脉冲 */
export function flashTick(el: HTMLElement | null | undefined, direction: 'up' | 'down' | 'flat'): void {
  if (!el || prefersReducedMotion() || direction === 'flat') return
  tickTween?.kill()
  const color = direction === 'up' ? 'var(--term-up)' : 'var(--term-down)'
  const glow = direction === 'up' ? 'var(--term-glow-up)' : 'var(--term-glow-down)'

  gsap.set(el, { color })
  tickTween = gsap.fromTo(
    el,
    { textShadow: `0 0 0 ${glow}`, scale: 1 },
    {
      textShadow: `0 0 14px ${glow}`,
      scale: 1.04,
      duration: 0.22,
      yoyo: true,
      repeat: 1,
      ease: EASE.inOut,
      onComplete: () => gsap.set(el, { scale: 1 })
    }
  )
}

/** 强调脉冲（用于新数据到达、预警触发） */
export function pulse(el: Element | null | undefined, times = 2): void {
  if (!el || prefersReducedMotion()) return
  gsap.fromTo(
    el,
    { boxShadow: '0 0 0 0 var(--term-glow)' },
    {
      boxShadow: '0 0 0 6px rgba(56, 189, 248, 0)',
      duration: 0.9,
      repeat: times - 1,
      ease: 'power2.out'
    }
  )
}

/* ------------------------------------------------------------------ *
 * 5. 布局变化：用 Flip 让重排本身有动画
 * ------------------------------------------------------------------ */

/**
 * 记录当前布局快照。用法：
 *   const state = captureLayout(container)
 *   collapsed.value = !collapsed.value        // 改状态
 *   await nextTick()
 *   flipLayout(state)
 */
export function captureLayout(
  targets: Element | Element[] | NodeListOf<Element> | string
): Flip.FlipState | null {
  if (prefersReducedMotion()) return null
  return Flip.getState(targets)
}

/** 播放布局变化动画；state 为 null 时静默跳过 */
export function flipLayout(state: Flip.FlipState | null, duration = 0.42): void {
  if (!state) return
  Flip.from(state, {
    duration,
    ease: EASE.out,
    absolute: false,
    nested: true,
    prune: true,
    onEnter: (els) => gsap.fromTo(els, { opacity: 0, scale: 0.9 }, { opacity: 1, scale: 1, duration: 0.3 }),
    onLeave: (els) => gsap.to(els, { opacity: 0, scale: 0.9, duration: 0.2 })
  })
}

/* ------------------------------------------------------------------ *
 * 6. 面板级过渡（K 线页容器层）
 * ------------------------------------------------------------------ */

/** 面板内容切换：淡出旧内容 → 淡入新内容（K 线 canvas 只改透明度，不动 transform） */
export function swapFade(el: Element | null | undefined, done?: () => void): void {
  if (!el || prefersReducedMotion()) {
    done?.()
    return
  }
  gsap.fromTo(el, { opacity: 0 }, { opacity: 1, duration: 0.28, ease: EASE.soft, onComplete: () => done?.() })
}

export { gsap, Flip }
