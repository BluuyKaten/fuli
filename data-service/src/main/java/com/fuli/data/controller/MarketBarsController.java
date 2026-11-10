package com.fuli.data.controller;

import com.fuli.common.api.Result;
import com.fuli.data.service.MarketBarsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 多市场 K 线统一契约 {@code GET /market/bars}。
 *
 * <p>落位在 data-service（无认证、仅限内网），gateway 另开 {@code /api/market/**} 路由。
 * Python 回测进程内网直连 {@code http://data-service:8084/market/bars}，
 * 与前端图表走同一份数据、同一个 {@code adjust} 口径——回测结论才和图上看到的一致。
 *
 * <p>与旧 {@code /data/stock/**} 的区别：本接口成功与失败都返回 {@code Result} 包装
 * （§11 的错误契约），旧 Feign 端点仍是裸 JSON，调用方无感。
 */
@RestController
@RequestMapping("/market")
@RequiredArgsConstructor
public class MarketBarsController {

    private final MarketBarsService marketBarsService;

    /**
     * @param symbol 完整符号 {@code 600519.SH}；裸 6 位按 A 股规则推断；其余非法
     * @param period 受控集合 1m/5m/15m/30m/1h/4h/1d/1w/1mo
     * @param from   可选，yyyyMMdd 或 yyyy-MM-dd，闭区间
     * @param to     可选，yyyyMMdd 或 yyyy-MM-dd，闭区间
     * @param limit  可选；不传区间时表示「最近 N 根」（默认 620，上限 5000），
     *               传区间时表示「区间内最近 N 根」
     * @param adjust 可选，none（默认）/ forward
     */
    @GetMapping("/bars")
    public ResponseEntity<Result<Map<String, Object>>> bars(
            @RequestParam("symbol") String symbol,
            @RequestParam("period") String period,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "adjust", required = false, defaultValue = "none") String adjust) {
        Map<String, Object> data;
        try {
            data = marketBarsService.query(symbol, period, from, to, limit, adjust);
        } catch (IllegalArgumentException e) {
            // 参数问题一律 400 + 中文消息，不让调用方去猜是哪一段不合法
            return ResponseEntity.badRequest()
                    .body(Result.error(HttpStatus.BAD_REQUEST.value(), e.getMessage()));
        }
        if (data == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Result.error(HttpStatus.NOT_FOUND.value(), "未知标的：" + symbol));
        }
        return ResponseEntity.ok(Result.success(data));
    }
}
