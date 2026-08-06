package com.fuli.data.tushare;

public interface TushareSyncService {

    int syncStockBasic();

    int syncDailyData(String tsCode, String startDate, String endDate);

    int syncDailyDataByTradeDate(String tradeDate);

    SyncStatus getSyncStatus(String tsCode);

    int syncAllStocksIncremental(String startDate, String endDate);

    /**
     * 按自定义日期范围同步所有股票日线数据(异步执行,返回任务 ID)
     * <p>
     * 采用按交易日期逐日查询策略,避免单次请求超过 Tushare 6000 条限制。
     * 已同步过的交易日会自动跳过,保证幂等性。
     * 通过 SSE 接口 /data/tushare/sync/progress/{taskId} 监听进度。
     * </p>
     *
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     * @param forceSync 是否强制重新同步(true: 忽略已有数据,全部重新同步; false: 跳过已同步的日期)
     * @return 任务 ID,用于监听进度
     */
    String syncDailyDataByDateRange(String startDate, String endDate, boolean forceSync);
}
