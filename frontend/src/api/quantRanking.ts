import request from '@/utils/request'
import type {
  QuantAutomationConfig,
  QuantAutomationStatus,
  QuantColumnMapping,
  QuantImportRequest,
  QuantImportResult,
  QuantNotificationConfig,
  QuantNotificationResult,
  QuantRankingDailyData,
  QuantRankingLog,
  QuantRankingSummaryData,
  QuantSchedulerResult
} from '@/types/quantRanking'

export interface ApiResult<T> {
  code?: number
  message?: string
  data: T
}

export const getQuantSummary = (limit = 20) =>
  request.get<any, ApiResult<QuantRankingSummaryData>>('/trade/quant-ranking/summary', { params: { limit } })

export const getQuantDaily = (date?: string) =>
  request.get<any, ApiResult<QuantRankingDailyData>>('/trade/quant-ranking/daily', { params: { date } })

export const getQuantLogs = (limit = 30) =>
  request.get<any, ApiResult<QuantRankingLog[]>>('/trade/quant-ranking/logs', { params: { limit } })

export const getQuantNotificationConfig = () =>
  request.get<any, ApiResult<QuantNotificationConfig>>('/trade/quant-ranking/notification/config')

export const getQuantAutomationConfig = () =>
  request.get<any, ApiResult<QuantAutomationConfig>>('/trade/quant-ranking/automation/config')

export const saveQuantAutomationConfig = (config: QuantAutomationConfig) =>
  request.put<any, ApiResult<null>>('/trade/quant-ranking/automation/config', config)

export const getQuantAutomationStatus = () =>
  request.get<any, ApiResult<QuantAutomationStatus>>('/trade/quant-ranking/automation/status')

export const collectQuantRanking = () =>
  request.post<any, ApiResult<QuantSchedulerResult>>('/trade/quant-ranking/collect', undefined, { timeout: 60000 })

export const importQuantRanking = (payload: QuantImportRequest) => {
  if (payload.file) {
    const formData = new FormData()
    formData.append('file', payload.file)
    if (payload.tradingDate) formData.append('tradingDate', payload.tradingDate)
    if (payload.mapping) formData.append('mapping', JSON.stringify(payload.mapping))
    return request.post<any, ApiResult<QuantImportResult>>('/trade/quant-ranking/import', formData)
  }

  return request.post<any, ApiResult<QuantImportResult>>('/trade/quant-ranking/import', {
    text: payload.text || '',
    tradingDate: payload.tradingDate,
    mapping: payload.mapping
  })
}

export const saveQuantNotificationConfig = (config: QuantNotificationConfig) =>
  request.put<any, ApiResult<boolean>>('/trade/quant-ranking/notification/config', config)

export const sendQuantNotificationTest = () =>
  request.post<any, ApiResult<QuantNotificationResult>>('/trade/quant-ranking/notification/test')

export const defaultQuantMapping: Required<QuantColumnMapping> = {
  rank: '排名',
  code: '代码',
  name: '名称',
  metric: '指标',
  metricValue: '人气{wyz}'
}
