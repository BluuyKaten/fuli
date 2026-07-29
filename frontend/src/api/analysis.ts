import request from '@/utils/request'

export const getAnalysisStatistics = (params?: { stockCode?: string; startDate?: string; endDate?: string }) =>
  request.get<any, { code: number; data: any }>('/analysis/statistics', { params })

export const getMonthlyProfit = (params?: { startDate?: string; endDate?: string }) =>
  request.get<any, { code: number; data: any[] }>('/analysis/monthly-profit', { params })

export const getAssetCurve = (params?: { startDate?: string; endDate?: string }) =>
  request.get<any, { code: number; data: { dates: string[]; assets: number[] } }>('/analysis/asset-curve', { params })
