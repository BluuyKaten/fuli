package com.fuli.data.okx;

import com.fuli.data.config.OkxConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * OKX 行情定时同步。默认关闭（{@code okx.auto-sync=false}）：
 * 本地起服务就往外发请求会污染日志、也可能踩限频，要开是显式动作。
 *
 * <p>频率按「最小周期」定：跑 1m 就得每分钟左右一次；只跑 1d 时每 10 分钟一次也够，
 * 因为只写已完结的 K 线，重复拉同一根是幂等的（主键 upsert）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkxScheduler {

    private final OkxConfig config;

    private final OkxMarketService marketService;

    @Scheduled(cron = "0 */10 * * * ?")
    public void syncEveryTenMinutes() {
        if (!config.isAutoSync()) {
            return;
        }
        try {
            int n = marketService.syncConfigured();
            log.info("[OKX] 定时同步完成：{} 根", n);
        } catch (Exception e) {
            log.error("[OKX] 定时同步失败: {}", e.getMessage());
        }
    }
}
