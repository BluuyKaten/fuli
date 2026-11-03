package com.fuli.analysis.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceStatsVO {

    /** 基础交易计数 */
    private int totalTrades;
    private int buyCount;
    private int sellCount;
    private int winCount;
    private int lossCount;
    private int breakEvenCount;

    /** 核心指标 */
    private BigDecimal winRate;            // 胜率 %
    private BigDecimal totalProfitLoss;    // 总实现盈亏 ¥
    private BigDecimal totalProfitAmount;  // 盈利总额 ¥
    private BigDecimal totalLossAmount;    // 亏损总额 ¥
    private BigDecimal profitLossRatio;    // 盈亏比 (平均盈利 / 平均亏损)
    private BigDecimal profitFactor;       // 获利因子 (盈利总额 / 亏损总额绝对值)
    private BigDecimal avgWinAmount;       // 单笔平均盈利 ¥
    private BigDecimal avgLossAmount;      // 单笔平均亏损 ¥
    private BigDecimal avgTradeProfit;     // 单笔平均净收益 ¥
    private int maxConsecutiveWins;        // 最大连胜次数
    private int maxConsecutiveLosses;      // 最大连亏次数
    private double avgHoldingDays;         // 平均持仓天数

    /** 最佳/最差单笔 */
    private ExtremeTradeVO bestTrade;
    private ExtremeTradeVO worstTrade;

    /** 收益率区间分布 */
    private List<DistributionItemVO> profitDistribution;

    /** 持仓周期分布 */
    private List<DistributionItemVO> holdingDistribution;

    /** 盈亏个股排行 */
    private List<StockPerformanceVO> topProfitStocks;
    private List<StockPerformanceVO> topLossStocks;

    /** 月度绩效表现 */
    private List<MonthlyPerformanceVO> monthlyStats;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ExtremeTradeVO {
        private String stockCode;
        private String stockName;
        private BigDecimal profitLoss;
        private BigDecimal profitLossRatio;
        private String tradeDate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DistributionItemVO {
        private String label;
        private int count;
        private double percentage;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StockPerformanceVO {
        private String stockCode;
        private String stockName;
        private BigDecimal profitLoss;
        private int tradeCount;
        private BigDecimal winRate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyPerformanceVO {
        private String month;
        private BigDecimal profitLoss;
        private int tradeCount;
        private BigDecimal winRate;
    }
}
