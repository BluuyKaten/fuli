package com.fuli.analysis.controller;

import com.fuli.common.dto.AnalysisSummaryVO;
import com.fuli.common.dto.TradeVO;
import com.fuli.common.feign.TradeFeignClient;
import com.fuli.common.model.Result;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/analysis")
@RequiredArgsConstructor
public class AnalysisController {

    private final TradeFeignClient tradeFeignClient;

    @GetMapping("/stats")
    public Result<AnalysisSummaryVO> stats(@RequestParam("userId") Long userId,
                                           @RequestParam(value = "start", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
                                           @RequestParam(value = "end", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end) {
        List<TradeVO> trades = tradeFeignClient.listByUserId(userId, start, end).getData();
        if (trades == null) {
            trades = List.of();
        }

        BigDecimal positive = BigDecimal.ZERO;
        BigDecimal negativeAbs = BigDecimal.ZERO;
        int wins = 0;

        Map<String, BigDecimal> monthMap = new LinkedHashMap<>();
        BigDecimal cumulative = BigDecimal.ZERO;
        List<AnalysisSummaryVO.AssetPoint> curve = new ArrayList<>();

        List<TradeVO> ordered = trades.stream()
                .filter(t -> t.getTradeTime() != null)
                .sorted(Comparator.comparing(TradeVO::getTradeTime))
                .toList();

        for (TradeVO t : ordered) {
            BigDecimal pl = t.getProfitLoss() == null ? BigDecimal.ZERO : t.getProfitLoss();
            if (pl.compareTo(BigDecimal.ZERO) > 0) {
                positive = positive.add(pl);
                wins++;
            } else if (pl.compareTo(BigDecimal.ZERO) < 0) {
                negativeAbs = negativeAbs.add(pl.abs());
            }
            cumulative = cumulative.add(pl);
            curve.add(new AnalysisSummaryVO.AssetPoint(
                    t.getTradeTime().toLocalDate().toString(), cumulative));

            String month = t.getTradeTime().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            monthMap.put(month, monthMap.getOrDefault(month, BigDecimal.ZERO).add(pl));
        }

        BigDecimal winRate = trades.isEmpty() ? BigDecimal.ZERO :
                BigDecimal.valueOf(wins).divide(BigDecimal.valueOf(trades.size()), 4, RoundingMode.HALF_UP);
        BigDecimal plRatio = negativeAbs.compareTo(BigDecimal.ZERO) == 0
                ? positive
                : positive.divide(negativeAbs, 4, RoundingMode.HALF_UP);

        List<AnalysisSummaryVO.MonthlyBar> monthlyBars = monthMap.entrySet()
                .stream()
                .map(e -> new AnalysisSummaryVO.MonthlyBar(e.getKey(), e.getValue()))
                .toList();

        return Result.ok(new AnalysisSummaryVO(winRate, plRatio, curve, monthlyBars));
    }
}
