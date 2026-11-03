import { describe, expect, it } from 'vitest'
import { annotationAtVisiblePoint, chartBars, mapPracticeView, movingAverage, normalizePracticeCode, percent, validQuantity, validateCreation } from '../practiceData'
import { bar, practice } from './fixtures'

describe('visible prefix and review data boundary', () => {
  it('drops future chart/indicator/annotation inputs and review snapshot before ACTIVE state', () => {
    const source = { ...practice(), bars: [bar('2024-01-02'), bar('2024-01-03', 1000)], reviewBars: [bar('2024-01-05', 9000)],
      equityCurve: [{ date: '2024-01-02', equity: 100000, benchmarkEquity: 100000 }, { date: '2024-01-05', equity: 999999, benchmarkEquity: 999999 }],
      annotations: [{ id: 'future-note', type: 'BUY' as const, points: [{ date: '2024-01-05', price: 9000 }] }],
      snapshot: [bar('2024-01-05', 99999)] }
    const result = mapPracticeView(source)
    expect(chartBars(result)).toEqual([bar('2024-01-02')])
    expect(result).not.toHaveProperty('reviewBars')
    expect(result).not.toHaveProperty('snapshot')
    expect(result.annotations).toEqual([])
    expect(result.equityCurve).toHaveLength(1)
    expect(movingAverage(chartBars(result), 2)).toEqual([])
    expect(source.bars).toHaveLength(2)
  })
  it('reveals review bars only on FINISHED, preserving actual-end metrics and curve', () => {
    const result = mapPracticeView(practice({ status: 'FINISHED', reviewBars: [bar('2024-01-02'), bar('2024-01-05', 1000)], equity: 123456.78, returnRate: .2345678, benchmarkReturnRate: .1, maxDrawdown: .02 }))
    expect(chartBars(result)).toHaveLength(2)
    expect(result.bars).toHaveLength(1)
    expect(result.equityCurve).toHaveLength(1)
    expect(result.equity).toBe(123456.78)
    expect(percent(result.returnRate)).toBe('+23.46%')
    expect(result.maxDrawdown).toBe(.02)
  })
  it('keeps a large backend ID as a string and rejects a numeric ID', () => {
    expect(mapPracticeView(practice()).id).toBe('9007199254740999')
    expect(() => mapPracticeView(practice({ id: 9007199254740999 as unknown as string }))).toThrow('练习标识无效')
  })
  it('uses only supplied bars for the moving average, including historical background', () => {
    expect(movingAverage([bar('2024-01-01', 10), bar('2024-01-02', 12), bar('2024-01-03', 14)], 2)).toEqual([
      { time: '2024-01-02', value: 11 }, { time: '2024-01-03', value: 13 }
    ])
  })
})

describe('practice input and annotation limits', () => {
  it('normalizes stock entry without a stock quote or search', () => {
    expect(normalizePracticeCode(' 600519 ')).toBe('600519.SH')
    expect(normalizePracticeCode('000001.sz')).toBe('000001.SZ')
    expect(normalizePracticeCode('920001')).toBe('920001.BJ')
  })
  it('validates calendar dates, date ordering, cash limits and whole lots', () => {
    const valid = { stockCode: '600519.SH', startDate: '2024-02-01', endDate: '2024-03-01', initialCash: 100000 }
    expect(validateCreation(valid)).toBeNull()
    expect(validateCreation({ ...valid, startDate: '2024-02-30' })).toContain('有效')
    expect(validateCreation({ ...valid, startDate: '2024-99-01' })).toContain('有效')
    expect(validateCreation({ ...valid, endDate: valid.startDate })).toContain('晚于')
    expect(validateCreation({ ...valid, initialCash: 99 })).toContain('100')
    expect(validateCreation({ ...valid, initialCash: 1000000001 })).toContain('1,000,000,000')
    expect(validQuantity(100)).toBe(true)
    for (const quantity of [null, 0, -100, 50, 100.5, 10000100]) expect(validQuantity(quantity)).toBe(false)
  })
  it('rejects future, missing and malformed annotation points', () => {
    const bars = [bar('2024-01-02')]
    expect(annotationAtVisiblePoint({ id: 'a', type: 'BUY', points: [{ date: '2024-01-02', price: 10 }] }, bars, '2024-01-02')).toBe(true)
    expect(annotationAtVisiblePoint({ id: 'a', type: 'LINE', points: [{ date: '2024-01-02', price: 10 }] }, bars, '2024-01-02')).toBe(false)
    expect(annotationAtVisiblePoint({ id: 'a', type: 'SELL', points: [{ date: '2024-01-03', price: 10 }] }, bars, '2024-01-02')).toBe(false)
    expect(annotationAtVisiblePoint({ id: 'a', type: 'SELL', points: [{ date: '2024-01-01', price: 10 }] }, bars, '2024-01-02')).toBe(false)
    expect(annotationAtVisiblePoint({ id: 'a', type: 'BUY', points: [{ date: '2024-01-02', price: 0 }] }, bars, '2024-01-02')).toBe(false)
  })
})
