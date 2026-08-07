/** 登录返回(对齐后端 LoginVO) */
export interface LoginVO {
  token: string
  tokenType: string
  expiresIn: number
  username: string
  nickname: string
  cash: number
}

/** 用户资料(对齐 auth_db.sys_user) */
export interface UserProfile {
  id: number
  username: string
  nickname: string | null
  email: string | null
  phone: string | null
  avatar: string | null
  cash: number
  status: number
}

/** 持仓信息 */
export interface Position {
  stockCode: string
  stockName: string
  holdingQuantity: number
  availableQuantity: number
  costPrice: number
  currentPrice: number
  marketValue: number
  floatingProfitLoss: number
  dailyProfitLoss: number
  priceDate: string | null
}

/** 仪表盘数据(对应 analysis-service DashboardService 返回结构) */
export interface DashboardData {
  userId?: number
  username?: string
  nickname?: string | null
  cash: number
  totalAssets: number
  totalMarketValue: number
  totalCost: number
  totalProfitLoss: number
  totalProfitLossPercent: number
  profitPercentage?: number
  floatingProfitLoss?: number
  cashBalance?: number
  dailyProfitLoss?: number
  positions: PositionVO[]
  monthlyProfits?: MonthlyProfitVO[]
  assetCurve?: AssetCurveVO
  lastSyncDate?: string | null
}

/** 持仓(对应 analysis-service PositionVO) */
export interface PositionVO {
  stockCode: string
  stockName: string
  holdingQuantity: number
  avgCost: number
  currentPrice: number
  marketValue: number
  profitLoss: number
  profitLossPercent: number
  dailyProfitLoss?: number
  priceDate?: string | null
}

/** 统计数据(对齐后端 StatisticsVO) */
export interface StatisticsVO {
  userId: number | null
  stockCode: string | null
  stockName: string | null
  totalTrades: number
  buyCount: number
  sellCount: number
  totalBuyAmount: number
  totalSellAmount: number
  totalProfitLoss: number
  winRate: number
  profitLossRatio: number
  avgProfit: number
  avgLoss: number
  maxProfit: number
  maxLoss: number
  startDate?: string | null
  endDate?: string | null
}

/** 月度盈亏(对齐后端 MonthlyProfitVO) */
export interface MonthlyProfitVO {
  month: string
  profitLoss: number
  tradeCount: number
  winRate: number
}

/** 为兼容旧代码保留别名 */
export type MonthlyProfit = MonthlyProfitVO

/** 资产曲线 */
export interface AssetCurveVO {
  dates: string[]
  assets: number[]
}

/** 为兼容旧代码保留别名 */
export type AssetCurve = AssetCurveVO

/** 同步状态 */
export interface SyncStatusVO {
  tsCode: string
  latestTradeDate: string | null
  missingDays: number
  status: 'UP_TO_DATE' | 'NEEDS_SYNC' | 'NO_DATA'
}

/** 股票基础信息 */
export interface StockInfo {
  stockCode: string
  stockName: string
  area?: string
  industry?: string
  market?: string
  listDate?: string
  changePercent?: number  // 涨跌幅（%），运行时附加字段
}

/** 股票日线数据 */
export interface StockDailyData {
  stockCode: string
  tradeDate: string
  openPrice: number
  highPrice: number
  lowPrice: number
  closePrice: number
  preClose?: number
  vol?: number
  amount?: number
}
