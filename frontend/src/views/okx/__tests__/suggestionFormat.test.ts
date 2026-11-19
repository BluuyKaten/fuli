import { describe, expect, it } from 'vitest'
import { fmtPercent, fmtQty, fmtTime, sideText, statusColor, statusText } from '../suggestionFormat'

describe('suggestionFormat', () => {
  it('formats quantity without trailing zeros', () => {
    expect(fmtQty(90.90909091)).toBe('90.90909091')
    expect(fmtQty(1)).toBe('1')
    expect(fmtQty(0.00001)).toBe('0.00001')
    expect(fmtQty(0)).toBe('0')
    expect(fmtQty(100)).toBe('100')
  })

  it('keeps placeholders distinct from zero', () => {
    expect(fmtQty(null)).toBe('-')
    expect(fmtQty(undefined)).toBe('-')
    expect(fmtQty('')).toBe('-')
  })

  it('returns non-numeric input as-is instead of hiding it', () => {
    expect(fmtQty('abc')).toBe('abc')
  })

  it('renders confidence as percent and keeps null as dash', () => {
    expect(fmtPercent(0.85)).toBe('85%')
    expect(fmtPercent(1)).toBe('100%')
    expect(fmtPercent(0)).toBe('0%')
    // 策略信号不传 confidence：显示 0% 会被误读成「毫无把握」
    expect(fmtPercent(null)).toBe('-')
    expect(fmtPercent(undefined)).toBe('-')
  })

  it('formats LocalDateTime as yyyy-MM-dd HH:mm:ss', () => {
    expect(fmtTime('2026-11-10T18:30:05')).toMatch(/^2026-11-10 \d{2}:30:05$/)
    expect(fmtTime(null)).toBe('-')
    expect(fmtTime('')).toBe('-')
    // 解析失败时保留原文，避免显示 Invalid Date
    expect(fmtTime('not-a-date')).toBe('not-a-date')
  })

  it('maps side and status to Chinese labels', () => {
    expect(sideText('buy')).toBe('买入')
    expect(sideText('sell')).toBe('卖出')
    expect(statusText('new')).toBe('待确认')
    expect(statusText('executed')).toBe('已执行')
    expect(statusText('executing')).toBe('执行中 / 待核实')
    expect(statusText('rejected')).toBe('已拒绝')
    expect(statusText('expired')).toBe('已过期')
    // 未知状态原样返回，别把新状态吞成空白
    expect(statusText('pending-review')).toBe('pending-review')
  })

  it('colors status by settlement state', () => {
    expect(statusColor('new')).toBe('orange')
    expect(statusColor('executed')).toBe('green')
    expect(statusColor('executing')).toBe('blue')
    expect(statusColor('rejected')).toBe('red')
    expect(statusColor('expired')).toBe('default')
  })
})
