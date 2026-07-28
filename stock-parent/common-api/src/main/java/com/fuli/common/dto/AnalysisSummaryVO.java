package com.fuli.common.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisSummaryVO {
    private BigDecimal winRate;
    private BigDecimal profitLossRatio;
    private List<AssetPoint> assetCurve;
    private List<MonthlyBar> monthlyBars;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssetPoint {
        private String date;
        private BigDecimal totalAsset;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MonthlyBar {
        private String month;
        private BigDecimal profitLoss;
    }
}
