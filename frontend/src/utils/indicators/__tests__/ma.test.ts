// ma.test.ts
import { calcMA } from '../ma'

describe('calcMA', () => {
  const data = [
    { close: 10 }, { close: 11 }, { close: 12 }, { close: 13 }, { close: 14 }
  ]

  test('period=3 时前2位为NaN', () => {
    const result = calcMA(data, 3)
    expect(result[0]).toBeNaN()
    expect(result[1]).toBeNaN()
    expect(result[2]).toBeCloseTo(11) // (10+11+12)/3
    expect(result[3]).toBeCloseTo(12) // (11+12+13)/3
    expect(result[4]).toBeCloseTo(13) // (12+13+14)/3
  })

  test('period=1 返回原始值', () => {
    const result = calcMA(data, 1)
    expect(result).toEqual([10, 11, 12, 13, 14])
  })

  test('空数组返回空数组', () => {
    expect(calcMA([], 5)).toEqual([])
  })
})
