package com.fuli.analysis.service;

import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.feign.TradeFeignClient;
import com.fuli.common.api.vo.MonthlyProfitVO;
import com.fuli.common.api.vo.StatisticsVO;
import com.fuli.common.api.vo.TradeVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class AnalysisService {

    private final TradeFeignClient tradeFeignClient;

    public AnalysisService(TradeFeignClient tradeFeignClient) {
        this.tradeFeignClient = tradeFeignClient;
    }

    public StatisticsVO getStatistics(Long userId, String stockCode, LocalDate startDate, LocalDate endDate) {
        TradeQueryDTO queryDTO = new TradeQueryDTO();
        queryDTO.setUserId(userId);
        queryDTO.setStockCode(stockCode);
        queryDTO.setStartDate(startDate);
        queryDTO.setEndDate(endDate);

        var result = tradeFeignClient.getStatistics(queryDTO);
        if (result != null && result.getCode() == 200) {
            return result.getData();
        }
        return null;
    }

    public List<TradeVO> getTradeList(Long userId, String stockCode, LocalDate startDate, LocalDate endDate) {
        TradeQueryDTO queryDTO = new TradeQueryDTO();
        queryDTO.setUserId(userId);
        queryDTO.setStockCode(stockCode);
        queryDTO.setStartDate(startDate);
        queryDTO.setEndDate(endDate);

        var result = tradeFeignClient.queryByCondition(queryDTO);
        if (result != null && result.getCode() == 200) {
            return result.getData();
        }
        return new ArrayList<>();
    }

    public List<MonthlyProfitVO> getMonthlyProfit(Long userId, LocalDate startDate, LocalDate endDate) {
        List<TradeVO> trades = getTradeList(userId, null, startDate, endDate);

        Map<String, List<TradeVO>> monthlyMap = trades.stream()
                .filter(t -> t.getProfitLoss() != null)
                .collect(Collectors.groupingBy(
                        t -> t.getTradeDate().format(DateTimeFormatter.ofPattern("yyyy-MM")),
                        LinkedHashMap::new,
                        Collectors.toList()
                ));

        return monthlyMap.entrySet().stream().map(entry -> {
            String month = entry.getKey();
            List<TradeVO> monthTrades = entry.getValue();

            MonthlyProfitVO vo = new MonthlyProfitVO();
            vo.setMonth(month);
            vo.setTradeCount(monthTrades.size());

            BigDecimal totalProfitLoss = monthTrades.stream()
                    .map(TradeVO::getProfitLoss)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            vo.setProfitLoss(totalProfitLoss);

            long winCount = monthTrades.stream()
                    .filter(t -> t.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                    .count();
            BigDecimal winRate = BigDecimal.valueOf(winCount)
                    .divide(BigDecimal.valueOf(monthTrades.size()), 4, RoundingMode.HALF_UP);
            vo.setWinRate(winRate);

            return vo;
        }).collect(Collectors.toList());
    }

    public Map<String, Object> getAssetCurve(Long userId, LocalDate startDate, LocalDate endDate) {
        List<TradeVO> trades = getTradeList(userId, null, startDate, endDate);

        List<String> dates = new ArrayList<>();
        List<BigDecimal> assets = new ArrayList<>();
        BigDecimal cumulativeAsset = BigDecimal.ZERO;

        for (TradeVO trade : trades) {
            if (trade.getProfitLoss() != null) {
                cumulativeAsset = cumulativeAsset.add(trade.getProfitLoss());
            }
            dates.add(trade.getTradeDate().toString());
            assets.add(cumulativeAsset);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dates", dates);
        result.put("assets", assets);
        return result;
    }
}
