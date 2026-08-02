package com.fuli.data.tushare;

import com.fuli.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/data/tushare")
public class TushareController {

    private final TushareSyncService tushareSyncService;

    public TushareController(TushareSyncService tushareSyncService) {
        this.tushareSyncService = tushareSyncService;
    }

    @PostMapping("/sync/stock-basic")
    public Result<Integer> syncStockBasic() {
        try {
            int count = tushareSyncService.syncStockBasic();
            return Result.success(count);
        } catch (Exception e) {
            log.error("同步股票基础信息失败", e);
            return Result.error("同步失败: " + e.getMessage());
        }
    }

    @PostMapping("/sync/daily")
    public Result<Integer> syncDaily(@RequestBody Map<String, String> params) {
        try {
            String tsCode = params.get("tsCode");
            String startDate = params.get("startDate");
            String endDate = params.get("endDate");
            int count = tushareSyncService.syncDailyData(tsCode, startDate, endDate);
            return Result.success(count);
        } catch (Exception e) {
            log.error("同步日线数据失败", e);
            return Result.error("同步失败: " + e.getMessage());
        }
    }

    @PostMapping("/sync/daily-by-date")
    public Result<Integer> syncDailyByDate(@RequestBody Map<String, String> params) {
        try {
            String tradeDate = params.get("tradeDate");
            int count = tushareSyncService.syncDailyDataByTradeDate(tradeDate);
            return Result.success(count);
        } catch (Exception e) {
            log.error("同步日线数据失败", e);
            return Result.error("同步失败: " + e.getMessage());
        }
    }

    @PostMapping("/sync/all-incremental")
    public Result<Integer> syncAllIncremental(@RequestBody Map<String, String> params) {
        try {
            String startDate = params.get("startDate");
            String endDate = params.get("endDate");
            int count = tushareSyncService.syncAllStocksIncremental(startDate, endDate);
            return Result.success(count);
        } catch (Exception e) {
            log.error("增量同步所有股票失败", e);
            return Result.error("同步失败: " + e.getMessage());
        }
    }

    @GetMapping("/sync/status")
    public Result<SyncStatus> syncStatus(@RequestParam String tsCode) {
        try {
            SyncStatus status = tushareSyncService.getSyncStatus(tsCode);
            return Result.success(status);
        } catch (Exception e) {
            log.error("获取同步状态失败", e);
            return Result.error("获取失败: " + e.getMessage());
        }
    }
}
