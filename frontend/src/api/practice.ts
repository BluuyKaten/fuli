import request from '@/utils/request'
import { mapPracticeView } from '@/views/practice/practiceData'

export type PracticeSide = 'BUY' | 'SELL'
export type PracticeStatus = 'ACTIVE' | 'FINISHED'
export interface PracticeBar {
  date: string
  open: number
  high: number
  low: number
  close: number
  volume: number
}
export interface PracticeOrder {
  id: string
  side: PracticeSide
  quantity: number
  submittedDate: string
  status: 'PENDING' | 'FILLED' | 'REJECTED' | 'CANCELLED'
  reason?: string
  executedDate?: string
}
export interface PracticeFill {
  id: string
  orderId: string
  side: PracticeSide
  date: string
  price: number
  quantity: number
  amount: number
  commission: number
  tax: number
  fee: number
}
export interface PracticeEquityPoint {
  date: string
  equity: number
  benchmarkEquity: number
}
export interface PracticeAnnotationPoint { date: string; price: number }
export interface PracticeAnnotation {
  id: string
  type: 'LINE' | PracticeSide
  points: PracticeAnnotationPoint[]
  text?: string
}
export interface PracticeSummary {
  id: string
  stockCode: string
  startDate: string
  endDate: string
  currentDate: string
  status: PracticeStatus
  version: number
  equity: number
  returnRate: number
  createdAt: string
}
export interface PracticeView extends PracticeSummary {
  initialCash: number
  cash: number
  quantity: number
  availableQuantity: number
  benchmarkEquity: number
  benchmarkReturnRate: number
  excessReturnRate: number
  maxDrawdown: number
  fees: number
  backgroundCount: number
  revealedSteps: number
  totalSteps: number
  bars: PracticeBar[]
  reviewBars?: PracticeBar[]
  pendingOrder: PracticeOrder | null
  orders: PracticeOrder[]
  fills: PracticeFill[]
  equityCurve: PracticeEquityPoint[]
  annotations: PracticeAnnotation[]
}
export interface CreatePracticeRequest {
  stockCode: string
  startDate: string
  endDate: string
  initialCash: number
}
interface Result<T> { code: number; message?: string; data: T }
const base = '/trade/practice'
const path = (id: string) => `${base}/${encodeURIComponent(id)}`
const view = async (response: Promise<Result<PracticeView>>) => mapPracticeView((await response).data)

export const practiceApi = {
  list: async () => (await request.get<unknown, Result<PracticeSummary[]>>(base)).data,
  get: (id: string) => view(request.get<unknown, Result<PracticeView>>(path(id))),
  create: (data: CreatePracticeRequest) => view(request.post<unknown, Result<PracticeView>>(base, data)),
  order: (id: string, expectedVersion: number, side: PracticeSide, quantity: number) =>
    view(request.post<unknown, Result<PracticeView>>(`${path(id)}/orders`, { expectedVersion, side, quantity })),
  cancel: (id: string, expectedVersion: number) =>
    view(request.post<unknown, Result<PracticeView>>(`${path(id)}/cancel-order`, { expectedVersion })),
  advance: (id: string, expectedVersion: number) =>
    view(request.post<unknown, Result<PracticeView>>(`${path(id)}/advance`, { expectedVersion })),
  finish: (id: string, expectedVersion: number) =>
    view(request.post<unknown, Result<PracticeView>>(`${path(id)}/finish`, { expectedVersion })),
  annotations: (id: string, expectedVersion: number, annotations: PracticeAnnotation[]) =>
    view(request.put<unknown, Result<PracticeView>>(`${path(id)}/annotations`, { expectedVersion, annotations }))
}
export type PracticeApi = typeof practiceApi
