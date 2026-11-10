/**
 * K 线加载去重闸门（模块级状态，跨组件实例共享）。
 *
 * 为什么不能放在 `<script setup>` 里：那里声明的变量属于组件实例，
 * 组件因 `:key` 变化重建后状态归零，闸门形同虚设。
 *
 * 背景：首屏会对同一只股票连发两次日线请求——`activeStock` 先由 localStorage 恢复、
 * 再被 watchlist 接口覆盖，两次的代码拼写不同（`600519` vs `600519.SH`）导致 key 变化、
 * 组件重建，于是走了两遍 onMounted → loadData。图表会闪一下，观感像在自动刷新。
 */

let lastKey = ''
let lastAt = 0

/** 同参数在 windowMs 内只放行一次；返回 true 表示应当跳过本次加载 */
export const shouldSkipLoad = (key: string, windowMs = 400): boolean => {
  const now = Date.now()
  if (key === lastKey && now - lastAt < windowMs) {
    return true
  }
  lastKey = key
  lastAt = now
  return false
}
