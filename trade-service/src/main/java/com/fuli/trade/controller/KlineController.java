package com.fuli.trade.controller;

import com.fuli.common.api.Result;
import com.fuli.common.api.util.StockCodeUtil;
import com.fuli.trade.dto.KlineBarDTO;
import com.fuli.trade.entity.ChartDrawing;
import com.fuli.trade.entity.RealtimeQuote;
import com.fuli.trade.mapper.ChartDrawingMapper;
import com.fuli.trade.mapper.RealtimeQuoteMapper;
import com.fuli.trade.service.StockDataRouterService;
import com.fuli.trade.service.StockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * K 线多周期 + 实时行情 + 画线
 */
@RestController
@RequestMapping("/stock")
@RequiredArgsConstructor
public class KlineController {

    private final StockDataRouterService routerService;
    private final RealtimeQuoteMapper realtimeQuoteMapper;
    private final ChartDrawingMapper chartDrawingMapper;
    private final StockService stockService;
    private final jakarta.servlet.http.HttpServletRequest request;

    /**
     * 分钟 K 线
     * GET /stock/minute?code=300750&period=5
     */
    @GetMapping("/minute")
    public Result<List<KlineBarDTO>> minute(
            @RequestParam String code,
            @RequestParam(defaultValue = "5") int period) {
        // 东财要纯代码；解析只走 StockCodeUtil，不再本地 split
        return Result.success(routerService.getMinuteData(StockCodeUtil.pureCode(code), period));
    }

    /**
     * 周 K 线
     */
    @GetMapping("/weekly")
    public Result<List<KlineBarDTO>> weekly(@RequestParam String code) {
        return Result.success(routerService.getWeeklyData(StockCodeUtil.pureCode(code)));
    }

    /**
     * 月 K 线
     */
    @GetMapping("/monthly")
    public Result<List<KlineBarDTO>> monthly(@RequestParam String code) {
        return Result.success(routerService.getMonthlyData(StockCodeUtil.pureCode(code)));
    }

    /**
     * 实时行情快照（5 档）
     *
     * <p>优先读实时快照表；当前项目未接入实时行情源，该表为空，
     * 此时退化为 data-service 的最新日线收盘价（真实数据）。
     * 历史版本在这里返回定时任务随机生成的模拟盘口，已移除。
     */
    @GetMapping("/quote")
    public Result<Map<String, Object>> quote(@RequestParam String code) {
        String tushareCode = StockCodeUtil.resolveOrNull(code);
        if (tushareCode == null) {
            return Result.error("非法的标的符号：" + code + "；美股/加密必须带市场后缀（如 AAPL.US）");
        }

        RealtimeQuote quote = realtimeQuoteMapper.selectById(tushareCode);
        if (quote != null) {
            return Result.success(buildRealtimeQuote(quote));
        }

        Map<String, Object> latest = stockService.getLatestPrice(tushareCode);
        if (latest == null || latest.get("closePrice") == null) {
            return Result.error("暂无 " + tushareCode + " 的行情数据，请先在数据同步页同步日线");
        }
        return Result.success(buildDailyQuote(tushareCode, latest));
    }

    private Map<String, Object> buildRealtimeQuote(RealtimeQuote quote) {
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", quote.getStockCode());
        map.put("price", quote.getClosePrice());
        map.put("preClose", quote.getPreClose());
        map.put("open", quote.getOpenPrice());
        map.put("high", quote.getHighPrice());
        map.put("low", quote.getLowPrice());
        map.put("volume", quote.getVol());
        map.put("amount", quote.getAmount());

        // 组装 5 档
        Map<String, Object> orderBook = new HashMap<>();
        Map<String, Object> bids = new HashMap<>();
        Map<String, Object> asks = new HashMap<>();
        bids.put("prices", new Object[]{quote.getBid1Price(), quote.getBid2Price(), quote.getBid3Price(), quote.getBid4Price(), quote.getBid5Price()});
        bids.put("volumes", new Object[]{quote.getBid1Vol(), quote.getBid2Vol(), quote.getBid3Vol(), quote.getBid4Vol(), quote.getBid5Vol()});
        asks.put("prices", new Object[]{quote.getAsk1Price(), quote.getAsk2Price(), quote.getAsk3Price(), quote.getAsk4Price(), quote.getAsk5Price()});
        asks.put("volumes", new Object[]{quote.getAsk1Vol(), quote.getAsk2Vol(), quote.getAsk3Vol(), quote.getAsk4Vol(), quote.getAsk5Vol()});
        orderBook.put("bids", bids);
        orderBook.put("asks", asks);
        map.put("orderBook", orderBook);
        map.put("source", "realtime");
        map.put("stale", false);
        map.put("timestamp", quote.getUpdateTime() != null ? quote.getUpdateTime().toString() : "");
        return map;
    }

    /**
     * 无实时源时的降级响应：价格取最新日线收盘，五档为空数组（前端渲染"暂无五档"）。
     */
    private Map<String, Object> buildDailyQuote(String tushareCode, Map<String, Object> latest) {
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", tushareCode);
        map.put("price", latest.get("closePrice"));
        map.put("preClose", latest.get("preClose"));
        map.put("open", null);
        map.put("high", null);
        map.put("low", null);
        map.put("volume", null);
        map.put("amount", null);

        Map<String, Object> emptySide = new HashMap<>();
        emptySide.put("prices", new Object[0]);
        emptySide.put("volumes", new Object[0]);
        Map<String, Object> orderBook = new HashMap<>();
        orderBook.put("bids", emptySide);
        orderBook.put("asks", emptySide);
        map.put("orderBook", orderBook);
        map.put("source", "daily");
        map.put("stale", true);
        map.put("timestamp", latest.get("tradeDate") != null ? latest.get("tradeDate").toString() : "");
        return map;
    }

    /**
     * 保存画线
     */
    @PostMapping("/drawing")
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public Result<Void> saveDrawing(@RequestBody Map<String, Object> payload) {
        Long userId = com.fuli.common.api.security.AuthenticatedUser.requireId(request);
        if (!(payload.get("stockCode") instanceof String stockCode) || stockCode.isBlank()
                || !(payload.get("period") instanceof String period) || period.isBlank()
                || !(payload.get("data") instanceof String data) || data.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "股票代码、周期和画线数据不能为空；清空画线请传 []");
        }
        ChartDrawing drawing = new ChartDrawing();
        drawing.setUserId(userId);
        drawing.setStockCode(stockCode);
        drawing.setPeriod(period);

        // 先删除旧的
        chartDrawingMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ChartDrawing>()
                        .eq("user_id", drawing.getUserId())
                        .eq("stock_code", drawing.getStockCode())
                        .eq("period", drawing.getPeriod())
        );

        // 如果数据为空数组，不插入（相当于删除）
        if ("[]".equals(data.trim())) {
            return Result.success();
        }

        drawing.setDrawingData(data);
        chartDrawingMapper.insert(drawing);
        return Result.success();
    }

    /**
     * 加载画线
     */
    @GetMapping("/drawing")
    public Result<Map<String, Object>> loadDrawing(
            @RequestParam(required = false) Long userId,
            @RequestParam String code,
            @RequestParam(defaultValue = "1D") String period) {
        userId = com.fuli.common.api.security.AuthenticatedUser.requireId(request);
        ChartDrawing drawing = chartDrawingMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ChartDrawing>()
                        .eq("user_id", userId)
                        .eq("stock_code", code)
                        .eq("period", period)
        );
        if (drawing == null) {
            return Result.success(Map.of("data", "[]"));
        }
        return Result.success(Map.of("data", drawing.getDrawingData()));
    }
}
