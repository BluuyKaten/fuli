package com.fuli.data.market;

import com.fuli.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 美股行情同步端点（阶段 1）。
 *
 * <p>经网关走 {@code /api/data/us/sync}（需 JWT）；内网直连 8084 与 Tushare/加密同步一致。
 */
@Slf4j
@RestController
@RequestMapping("/data/us")
public class UsSyncController {

    private final UsSyncService usSyncService;

    public UsSyncController(UsSyncService usSyncService) {
        this.usSyncService = usSyncService;
    }

    @PostMapping("/sync")
    public Result<Integer> sync(@RequestBody Map<String, String> params) {
        try {
            String symbol = params.get("symbol");
            if (symbol == null || symbol.isBlank()) {
                return Result.error(400, "缺少 symbol（如 AAPL.US）");
            }
            int count = usSyncService.sync(symbol);
            return Result.success(count);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        } catch (Exception e) {
            log.error("美股同步失败", e);
            return Result.error("同步失败: " + e.getMessage());
        }
    }
}
