// boll.test.ts
import { calcBOLL } from '../boll'

describe('calcBOLL', () => {
  const data = Array.from({ length: 30 }, (_, i) => ({
    close: 10 + Math.sin(i) * 2
  }))

  test('返回 upper/middle/lower 三个数组', () => {
    const { upper, middle, lower } = calcBOLL(data)
    expect(upper.length).toBe(data.length)
    expect(middle.length).toBe(data.length)
    expect(lower.length).toBe(data.length)
  })

  test('upper >= middle >= lower（有效值范围内）', () => {
    const { upper, middle, lower } = calcBOLL(data, 20)
    for (let i = 19; i < data.length; i++) {
      expect(upper[i]).toBeGreaterThanOrEqual(middle[i])
      expect(middle[i]).toBeGreaterThanOrEqual(lower[i])
    }
  })
})
