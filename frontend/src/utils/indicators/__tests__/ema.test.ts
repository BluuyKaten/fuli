// ema.test.ts
import { calcEMA } from '../ema'

describe('calcEMA', () => {
  const data = [
    { close: 10 }, { close: 11 }, { close: 12 }, { close: 13 }, { close: 14 }
  ]

  test('period=3 时前2位为NaN', () => {
    const result = calcEMA(data, 3)
    expect(result[0]).toBeNaN()
    expect(result[1]).toBeNaN()
    // EMA初始值 = SMA(前3个) = 11
    expect(result[2]).toBeCloseTo(11)
    // EMA = close * k + prev * (1-k), k=2/4=0.5
    expect(result[3]).toBeCloseTo(13 * 0.5 + 11 * 0.5) // 12
  })
})
