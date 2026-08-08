package com.fuli.analysis.controller;

import com.fuli.analysis.service.AnalysisService;
import com.fuli.analysis.service.DashboardService;
import com.fuli.analysis.vo.DashboardVO;
import com.fuli.common.api.Result;
import com.fuli.common.api.vo.MonthlyProfitVO;
import com.fuli.common.api.vo.StatisticsVO;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    private final AnalysisService analysisService;
    private final DashboardService dashboardService;
    private final HttpServletRequest request;

    public AnalysisController(AnalysisService analysisService, DashboardService dashboardService, HttpServletRequest request) {
        this.analysisService = analysisService;
        this.dashboardService = dashboardService;
        this.request = request;
    }

    private Long getCurrentUserId() {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader != null && !userIdHeader.isEmpty()) {
            return Long.parseLong(userIdHeader);
        }
        return null;
    }

    @GetMapping("/statistics")
    public Result<StatisticsVO> statistics(@RequestParam(required = false) String stockCode,
                                            @RequestParam(required = false) LocalDate startDate,
                                            @RequestParam(required = false) LocalDate endDate) {
        Long userId = getCurrentUserId();
        StatisticsVO statistics = analysisService.getStatistics(userId, stockCode, startDate, endDate);
        return Result.success(statistics);
    }

    @GetMapping("/monthly-profit")
    public Result<List<MonthlyProfitVO>> monthlyProfit(@RequestParam(required = false) LocalDate startDate,
                                                        @RequestParam(required = false) LocalDate endDate) {
        Long userId = getCurrentUserId();
        List<MonthlyProfitVO> result = analysisService.getMonthlyProfit(userId, startDate, endDate);
        return Result.success(result);
    }

    @GetMapping("/asset-curve")
    public Result<Map<String, Object>> assetCurve(@RequestParam(required = false) LocalDate startDate,
                                                   @RequestParam(required = false) LocalDate endDate) {
        Long userId = getCurrentUserId();
        Map<String, Object> result = analysisService.getAssetCurve(userId, startDate, endDate);
        return Result.success(result);
    }

    @GetMapping("/dashboard")
    public Result<DashboardVO> dashboard() {
        Long userId = getCurrentUserId();
        DashboardVO result = dashboardService.getDashboardData(userId);
        if (result == null) {
            return Result.error("查询资金余额失败，请稍后重试");
        }
        return Result.success(result);
    }
}
