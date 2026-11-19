/**
 * 建议单的展示格式化。
 *
 * 抽成纯函数是为了可单测：这里的分支（null 置信度、8 位小数、市价单无价格）
 * 都是「看起来对、实际显示成 0% 或 1.00000000」的高发区。
 */

/** 方向：后端存 buy/sell，界面按 A 股习惯「买红卖绿」由调用方定色 */
export const sideText = (side: string): string => (side === 'buy' ? '买入' : '卖出')

/** 五态与表约束 chk_okx_suggestion_status 对齐（V16 起含 executing） */
const STATUS_TEXT: Record<string, string> = {
  new: '待确认',
  executed: '已执行',
  executing: '执行中 / 待核实',
  rejected: '已拒绝',
  expired: '已过期'
}

export const statusText = (status: string): string => STATUS_TEXT[status] ?? status

export const statusColor = (status: string): string =>
  status === 'new' ? 'orange' : status === 'executed' ? 'green' : status === 'executing' ? 'blue' : status === 'rejected' ? 'red' : 'default'

/**
 * 数量 / 价格：小数位数不固定（BTC 8 位、部分币 2 位），
 * 直接 toFixed(8) 会把 1 显示成 1.00000000。
 *
 * 非数字（极少见，例如后端改成字符串口径）原样返回，不要吞成 "-"。
 */
export const fmtQty = (v: unknown): string => {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  if (!Number.isFinite(n)) return String(v)
  return n.toFixed(8).replace(/\.?0+$/, '')
}

/**
 * confidence 是 0~1。策略信号不传（null），必须显示 "-" 而不是 0%——
 * 0% 会被读成「一点把握都没有」，而实际是「没给」。
 */
export const fmtPercent = (v: unknown): string => {
  if (v === null || v === undefined || v === '') return '-'
  const n = Number(v)
  return Number.isFinite(n) ? `${(n * 100).toFixed(0)}%` : '-'
}

/**
 * 后端 LocalDateTime 没有时区（同一进程内网部署），按浏览器本地时区解析。
 * 解析失败时返回原文，方便对口径而不是显示成 Invalid Date。
 */
export const fmtTime = (v?: string | null): string => {
  if (!v) return '-'
  const d = new Date(v)
  if (Number.isNaN(d.getTime())) return v
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}
