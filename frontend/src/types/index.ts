import type { TradeRecord } from '@/api/trade';

/** 持仓信息 */
export interface Position {
  stockCode: string;
  stockName: string;
  holdingQuantity: number;
  availableQuantity: number;
  costPrice: number;
  currentPrice: number;
  marketValue: number;
  floatingProfitLoss: number;
  dailyProfitLoss: number;
  priceDate: string | null;
}

/** 仪表盘数据 */
export interface DashboardData {
  totalAssets: number;
  profitPercentage: number;
  floatingProfitLoss: number;
  totalMarketValue: number;
  cashBalance: number;
  positions: Position[];
}

/** 月度盈亏 */
export interface MonthlyProfit {
  month: string;
  tradeCount: number;
  profitLoss: number;
  winRate: number;
}

/** 资产曲线 */
export interface AssetCurve {
  dates: string[];
  assets: number[];
}

/** 统计数据 */
export interface Statistics {
  userId: number;
  stockCode: string;
  stockName: string;
  totalTrades: number;
  buyCount: number;
  sellCount: number;
  totalBuyAmount: number;
  totalSellAmount: number;
  totalProfitLoss: number;
  winRate: number;
  profitLossRatio: number;
  avgProfit: number;
  avgLoss: number;
  maxProfit: number;
  maxLoss: number;
}

/** 股票基础信息 */
export interface StockInfo {
  stockCode: string;
  stockName: string;
  area?: string;
  industry?: string;
  market?: string;
  listDate?: string;
}

/** 股票日线数据 */
export interface StockDailyData {
  stockCode: string;
  tradeDate: string;
  openPrice: number;
  highPrice: number;
  lowPrice: number;
  closePrice: number;
  preClose?: number;
  vol?: number;
  amount?: number;
}

/** 交易记录（re-export） */
export type { TradeRecord };
