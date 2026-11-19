export interface QuantRankingSummaryRow {
  rank: number
  code: string
  name: string
  appearanceCount: number
  averageRank: number
  bestRank: number
  latestRank: number
  lastDate: string
}

export interface QuantRankingDailyRow {
  rank: number
  code: string
  name: string
  metric?: string
  metricValue?: number | string
  rawFields?: Record<string, string | number | null>
}

export interface QuantRankingDailyData {
  date: string
  rows: QuantRankingDailyRow[]
}

export interface QuantRankingSummaryData {
  rows: QuantRankingSummaryRow[]
  total: number
}

export interface QuantRankingLog {
  id?: string | number
  date?: string
  status: '成功' | '失败' | '跳过' | string
  source?: string
  message?: string
  importedCount?: number
  createdAt?: string
}

export interface QuantNotificationConfig {
  enabled: boolean
  webhookUrl: string
}

export interface QuantNotificationResult {
  sent: boolean
  status: string
  message: string
}

export interface QuantAutomationConfig {
  enabled: boolean
  executablePath: string
  scheduleTime: '15:10'
  startupCatchUp: boolean
}

export interface QuantSchedulerResult {
  status: string
  tradingDate: string
  importedRows: number
  message: string
}

export interface QuantAutomationStatus {
  supported: boolean
  running: boolean
  message: string
  lastRun?: QuantSchedulerResult
  nextRunAt?: string
}

export interface QuantColumnMapping {
  rank?: string
  code?: string
  name?: string
  metric?: string
  metricValue?: string
}

export interface QuantImportRequest {
  text?: string
  tradingDate?: string
  mapping?: QuantColumnMapping
  file?: File
}

export interface QuantImportResult {
  skippedRows?: number
  imported?: number
  rowCount?: number
  replaced?: boolean
  date?: string
  tradingDate?: string
  message?: string
}
