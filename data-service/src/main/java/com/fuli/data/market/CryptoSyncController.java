package com.fuli.data.market;

import com.fuli.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 加密行情同步端点（阶段 2）。
 *
 * <p>经网关走 {@code /api/data/crypto/sync}（需 JWT）；内网直连 8084 的 {@code /data/crypto/sync}
 * 与 Tushare 同步一致，仅限内网使用。回测只读走 {@code GET /market/bars}，不经过本端点。
 */
@Slf4j
@RestController
@RequestMapping("/data/crypto")
public class CryptoSyncController {

    private final CryptoSyncService cryptoSyncService;

    public CryptoSyncController(CryptoSyncService cryptoSyncService) {
        this.cryptoSyncService = cryptoSyncService;
    }

    @PostMapping("/sync")
    public Result<Integer> sync(@RequestBody Map<String, String> params) {
        try {
            String symbol = params.get("symbol");
            String interval = params.getOrDefault("interval", "1d");
            String from = params.get("from");
            String to = params.get("to");
            if (symbol == null || symbol.isBlank()) {
                return Result.error(400, "缺少 symbol（如 BTC-USDT.CRYPTO）");
            }
            int count = cryptoSyncService.sync(symbol, interval, from, to);
            return Result.success(count);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("加密行情同步失败", e);
            return Result.error("同步失败: " + e.getMessage());
        }
    }
}
