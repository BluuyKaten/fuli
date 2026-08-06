package com.fuli.data.tushare;

import com.fuli.data.config.TushareConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Component
public class TushareScheduler {

    private final TushareSyncService tushareSyncService;
    private final TushareConfig tushareConfig;

    public TushareScheduler(TushareSyncService tushareSyncService, TushareConfig tushareConfig) {
        this.tushareSyncService = tushareSyncService;
        this.tushareConfig = tushareConfig;
    }

    @Scheduled(cron = "0 0 18 * * ?")
    public void syncDailyDataEveryDay() {
        if (!tushareConfig.isTokenConfigured()) {
            log.warn("Tushare Token 未配置，跳过每日行情同步");
            return;
        }
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        log.info("定时任务: 开始同步 {} 日线数据", today);
        try {
            int count = tushareSyncService.syncDailyDataByTradeDate(today);
            log.info("定时任务: {} 日线数据同步完成, 共 {} 条", today, count);
        } catch (Exception e) {
            log.error("定时任务: {} 日线数据同步失败", today, e);
        }
    }

    @Scheduled(cron = "0 0 19 * * 1-5")
    public void syncStockBasicEveryWeek() {
        if (!tushareConfig.isTokenConfigured()) {
            log.warn("Tushare Token 未配置，跳过股票基础信息同步");
            return;
        }
        log.info("定时任务: 开始同步股票基础信息");
        try {
            int count = tushareSyncService.syncStockBasic();
            log.info("定时任务: 股票基础信息同步完成, 共 {} 条", count);
        } catch (Exception e) {
            log.error("定时任务: 股票基础信息同步失败", e);
        }
    }

    @Scheduled(cron = "0 30 19 * * 1-5")
    public void syncAllIncrementalEveryWeek() {
        if (!tushareConfig.isTokenConfigured()) {
            log.warn("Tushare Token 未配置，跳过增量同步");
            return;
        }
        log.info("定时任务: 开始增量同步所有股票日线数据");
        try {
            int count = tushareSyncService.syncAllStocksIncremental(null, null);
            log.info("定时任务: 增量同步所有股票完成, 共 {} 条", count);
        } catch (Exception e) {
            log.error("定时任务: 增量同步所有股票失败", e);
        }
    }
}
