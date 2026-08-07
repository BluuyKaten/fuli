package com.fuli.trade.controller;

import com.fuli.common.api.Result;
import com.fuli.trade.dto.KlineBarDTO;
import com.fuli.trade.entity.ChartDrawing;
import com.fuli.trade.entity.RealtimeQuote;
import com.fuli.trade.mapper.ChartDrawingMapper;
import com.fuli.trade.mapper.RealtimeQuoteMapper;
import com.fuli.trade.service.StockDataRouterService;
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

    /**
     * 分钟 K 线
     * GET /stock/minute?code=300750&period=5
     */
    @GetMapping("/minute")
    public Result<List<KlineBarDTO>> minute(
            @RequestParam String code,
            @RequestParam(defaultValue = "5") int period) {
        // 去掉后缀
        String pureCode = code.split("\\.")[0];
        return Result.success(routerService.getMinuteData(pureCode, period));
    }

    /**
     * 周 K 线
     */
    @GetMapping("/weekly")
    public Result<List<KlineBarDTO>> weekly(@RequestParam String code) {
        String pureCode = code.split("\\.")[0];
        return Result.success(routerService.getWeeklyData(pureCode));
    }

    /**
     * 月 K 线
     */
    @GetMapping("/monthly")
    public Result<List<KlineBarDTO>> monthly(@RequestParam String code) {
        String pureCode = code.split("\\.")[0];
        return Result.success(routerService.getMonthlyData(pureCode));
    }

    /**
     * 实时行情快照（5 档）
     */
    @GetMapping("/quote")
    public Result<Map<String, Object>> quote(@RequestParam String code) {
        String tushareCode = code.contains(".") ? code : code + getSuffix(code);
        RealtimeQuote quote = realtimeQuoteMapper.selectById(tushareCode);
        if (quote == null) {
            return Result.error("无行情数据");
        }
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
        map.put("timestamp", quote.getUpdateTime() != null ? quote.getUpdateTime().toString() : "");

        return Result.success(map);
    }

    /**
     * 保存画线
     */
    @PostMapping("/drawing")
    public Result<Void> saveDrawing(@RequestBody Map<String, Object> payload) {
        ChartDrawing drawing = new ChartDrawing();
        drawing.setUserId(Long.parseLong(payload.get("userId").toString()));
        drawing.setStockCode((String) payload.get("stockCode"));
        drawing.setPeriod((String) payload.get("period"));
        drawing.setDrawingData(payload.get("data").toString());

        // 先删除旧的，再插入
        chartDrawingMapper.delete(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ChartDrawing>()
                        .eq("user_id", drawing.getUserId())
                        .eq("stock_code", drawing.getStockCode())
                        .eq("period", drawing.getPeriod())
        );
        chartDrawingMapper.insert(drawing);
        return Result.success();
    }

    /**
     * 加载画线
     */
    @GetMapping("/drawing")
    public Result<Map<String, Object>> loadDrawing(
            @RequestParam Long userId,
            @RequestParam String code,
            @RequestParam(defaultValue = "1D") String period) {
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

    private String getSuffix(String code) {
        if (code.startsWith("6")) return ".SH";
        if (code.startsWith("0") || code.startsWith("3")) return ".SZ";
        return ".BJ";
    }
}
