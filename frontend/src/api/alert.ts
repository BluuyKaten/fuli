import request from '@/utils/request'
import type { PriceAlert, PriceAlertRequest } from '@/types'

export const createAlert = (data: PriceAlertRequest) =>
  request.post<any, { code: number; message: string; data: number }>('/trade/alert', data)

export const updateAlert = (id: number, data: PriceAlertRequest) =>
  request.put<any, { code: number; message: string; data: boolean }>(`/trade/alert/${id}`, data)

export const deleteAlert = (id: number) =>
  request.delete<any, { code: number; message: string; data: boolean }>(`/trade/alert/${id}`)

export const toggleAlertStatus = (id: number, status: number) =>
  request.put<any, { code: number; message: string; data: boolean }>(`/trade/alert/${id}/status`, null, { params: { status } })

export const getAlertList = (status?: number) =>
  request.get<any, { code: number; data: PriceAlert[] }>('/trade/alert/list', { params: { status } })

export const checkAlerts = () =>
  request.post<any, { code: number; data: PriceAlert[] }>('/trade/alert/check')
