package com.fuli.data.tushare;

import lombok.Data;

@Data
public class SyncStatus {
    private String tsCode;
    private String latestTradeDate;
    private int missingDays;
    private String status;
}
