import type { PracticeBar, PracticeView } from '@/api/practice'
export const bar = (date: string, close = 10): PracticeBar => ({ date, open: close, high: close + 1, low: close - 1, close, volume: 1000 })
export const practice = (overrides: Partial<PracticeView> = {}): PracticeView => ({
  id: '9007199254740999', stockCode: '600519.SH', startDate: '2024-01-02', endDate: '2024-01-05',
  currentDate: '2024-01-02', status: 'ACTIVE', version: 7, createdAt: '2024-01-01T12:00:00',
  initialCash: 100000, cash: 100000, quantity: 0, availableQuantity: 0, equity: 100000,
  returnRate: 0, benchmarkEquity: 100000, benchmarkReturnRate: 0, excessReturnRate: 0, maxDrawdown: 0, fees: 0,
  backgroundCount: 1, revealedSteps: 0, totalSteps: 3, bars: [bar('2024-01-02')],
  pendingOrder: null, orders: [], fills: [], equityCurve: [{ date: '2024-01-02', equity: 100000, benchmarkEquity: 100000 }],
  annotations: [], ...overrides
})
