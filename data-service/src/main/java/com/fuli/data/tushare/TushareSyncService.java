package com.fuli.data.tushare;

public interface TushareSyncService {

    int syncStockBasic();

    int syncDailyData(String tsCode, String startDate, String endDate);

    int syncDailyDataByTradeDate(String tradeDate);

    SyncStatus getSyncStatus(String tsCode);

    int syncAllStocksIncremental(String startDate, String endDate);
}
