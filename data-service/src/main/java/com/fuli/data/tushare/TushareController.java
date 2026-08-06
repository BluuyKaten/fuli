package com.fuli.data.tushare;

import com.fuli.common.api.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/data/tushare")
public class TushareController {

    private final TushareSyncService tushareSyncService;
    private final SyncProgressManager progressManager;

    public TushareController(TushareSyncService tushareSyncService, SyncProgressManager progressManager) {
        this.tushareSyncService = tushareSyncService;
        this.progressManager = progressManager;
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

    /**
     * 按自定义日期范围同步所有股票日线数据(异步执行,通过 SSE 推送进度)
     *
     * @param params 请求参数:
     *               - startDate(起始日期 yyyyMMdd)
     *               - endDate(结束日期 yyyyMMdd)
     *               - forceSync(可选,true: 强制重新同步,忽略已有数据; false/null: 跳过已同步的日期)
     * @return 任务 ID,前端通过 SSE 接口 /data/tushare/sync/progress/{taskId} 监听进度
     */
    @PostMapping("/sync/daily-by-date-range")
    public Result<String> syncDailyByDateRange(@RequestBody Map<String, String> params) {
        try {
            String startDate = params.get("startDate");
            String endDate = params.get("endDate");
            // forceSync 参数,默认为 false
            boolean forceSync = "true".equalsIgnoreCase(params.get("forceSync"));
            String taskId = tushareSyncService.syncDailyDataByDateRange(startDate, endDate, forceSync);
            return Result.success(taskId);
        } catch (IllegalArgumentException e) {
            log.warn("参数错误: {}", e.getMessage());
            return Result.error("参数错误: " + e.getMessage());
        } catch (Exception e) {
            log.error("按日期范围同步日线数据异常", e);
            return Result.error("同步异常: " + e.getMessage());
        }
    }

    /**
     * SSE 实时推送同步进度
     *
     * @param taskId 任务 ID
     * @return SSE 连接
     */
    @GetMapping("/sync/progress/{taskId}")
    public SseEmitter syncProgress(@PathVariable String taskId) {
        log.info("建立 SSE 连接,监听任务进度: {}", taskId);
        SseEmitter emitter = progressManager.subscribe(taskId);
        if (emitter == null) {
            // 任务不存在,返回一个已完成的 emitter
            SseEmitter deadEmitter = new SseEmitter(1L);
            try {
                SyncProgress progress = new SyncProgress();
                progress.setTaskId(taskId);
                progress.setStatus(SyncProgress.SyncStatus.FAILED);
                progress.setMessage("任务不存在或已过期");
                deadEmitter.send(SseEmitter.event().name("progress").data(progress));
                deadEmitter.complete();
            } catch (Exception e) {
                log.warn("发送失败消息异常", e);
            }
            return deadEmitter;
        }
        return emitter;
    }

    /**
     * 查询同步任务进度(轮询方式,适用于不支持 SSE 的场景)
     *
     * @param taskId 任务 ID
     * @return 任务进度
     */
    @GetMapping("/sync/progress/{taskId}/status")
    public Result<SyncProgress> getSyncProgress(@PathVariable String taskId) {
        SyncProgress progress = progressManager.getProgress(taskId);
        if (progress == null) {
            return Result.error("任务不存在或已过期");
        }
        return Result.success(progress);
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
