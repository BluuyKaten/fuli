// kdj.test.ts
import { calcKDJ } from '../kdj'

describe('calcKDJ', () => {
  const data = Array.from({ length: 20 }, (_, i) => ({
    time: `2024-01-${String(i + 1).padStart(2, '0')}`,
    open: 10 + i, high: 12 + i, low: 8 + i, close: 11 + i
  }))

  test('返回 k/d/j 三个数组，长度与输入一致', () => {
    const { k, d, j } = calcKDJ(data)
    expect(k.length).toBe(data.length)
    expect(d.length).toBe(data.length)
    expect(j.length).toBe(data.length)
  })

  test('前 n-1 个值使用默认值 50', () => {
    const { k, d, j } = calcKDJ(data, 9)
    expect(k[0]).toBe(50)
    expect(d[0]).toBe(50)
    expect(j[0]).toBe(50)
  })
})
