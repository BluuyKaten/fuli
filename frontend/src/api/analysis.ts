import request from '@/utils/request'
import type { StatisticsVO, MonthlyProfitVO, AssetCurveVO, DashboardData as DashboardVO, PerformanceStats } from '@/types'

export const getAnalysisStatistics = (params?: { stockCode?: string; startDate?: string; endDate?: string }) =>
  request.get<any, { code: number; data: StatisticsVO }>('/analysis/statistics', { params })

export const getMonthlyProfit = (params?: { startDate?: string; endDate?: string }) =>
  request.get<any, { code: number; data: MonthlyProfitVO[] }>('/analysis/monthly-profit', { params })

export const getAssetCurve = (params?: { startDate?: string; endDate?: string }) =>
  request.get<any, { code: number; data: AssetCurveVO }>('/analysis/asset-curve', { params })

export const getDashboardData = () =>
  request.get<any, { code: number; data: DashboardVO }>('/analysis/dashboard')

export const getPerformanceStats = (params?: { startDate?: string; endDate?: string }) =>
  request.get<any, { code: number; data: PerformanceStats }>('/analysis/performance', { params })

