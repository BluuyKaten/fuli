// macd.test.ts
import { calcMACD } from '../macd'

describe('calcMACD', () => {
  const data = Array.from({ length: 50 }, (_, i) => ({ close: 10 + i * 0.5 }))

  test('返回 dif/dea/histogram 三个数组', () => {
    const { dif, dea, histogram } = calcMACD(data)
    expect(dif.length).toBe(50)
    expect(dea.length).toBe(50)
    expect(histogram.length).toBe(50)
  })

  test('histogram = (dif - dea) * 2', () => {
    const { dif, dea, histogram } = calcMACD(data)
    for (let i = 0; i < data.length; i++) {
      if (!isNaN(dif[i]) && !isNaN(dea[i])) {
        expect(histogram[i]).toBeCloseTo((dif[i] - dea[i]) * 2)
      }
    }
  })
})
