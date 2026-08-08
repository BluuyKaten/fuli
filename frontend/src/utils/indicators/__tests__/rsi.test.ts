// rsi.test.ts
import { calcRSI } from '../rsi'

describe('calcRSI', () => {
  const data = Array.from({ length: 20 }, (_, i) => ({ close: 10 + (i % 3 === 0 ? 1 : -0.5) }))

  test('返回数组长度与输入一致', () => {
    const result = calcRSI(data, 14)
    expect(result.length).toBe(data.length)
  })

  test('RSI 值在 0-100 之间', () => {
    const result = calcRSI(data, 14)
    result.forEach(v => {
      if (!isNaN(v)) {
        expect(v).toBeGreaterThanOrEqual(0)
        expect(v).toBeLessThanOrEqual(100)
      }
    })
  })
})
