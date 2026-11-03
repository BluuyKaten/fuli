import request from '@/utils/request'
import type { TradeJournal, TradeJournalRequest, JournalCalendarItem } from '@/types'

export interface JournalQueryParams {
  stockCode?: string
  startDate?: string
  endDate?: string
  keyword?: string
  tag?: string
  pageNum?: number
  pageSize?: number
}

export const createJournal = (data: TradeJournalRequest) =>
  request.post<any, { code: number; message: string; data: number }>('/trade/journal', data)

export const updateJournal = (id: number, data: TradeJournalRequest) =>
  request.put<any, { code: number; message: string; data: boolean }>(`/trade/journal/${id}`, data)

export const deleteJournal = (id: number) =>
  request.delete<any, { code: number; message: string; data: boolean }>(`/trade/journal/${id}`)

export const getJournal = (id: number) =>
  request.get<any, { code: number; data: TradeJournal }>(`/trade/journal/${id}`)

export const getJournalPage = (params: JournalQueryParams) =>
  request.post<any, { code: number; data: { records: TradeJournal[]; total: number; current: number; size: number } }>('/trade/journal/page', params)

export const getJournalCalendar = (yearMonth?: string) =>
  request.get<any, { code: number; data: JournalCalendarItem[] }>('/trade/journal/calendar-summary', { params: { yearMonth } })
