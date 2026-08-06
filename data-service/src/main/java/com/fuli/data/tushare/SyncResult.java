package com.fuli.data.tushare;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 日线数据同步结果
 */
@Data
public class SyncResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 起始日期 yyyyMMdd */
    private String startDate;

    /** 结束日期 yyyyMMdd */
    private String endDate;

    /** 请求同步的总天数 */
    private int totalDays;

    /** 成功同步的天数 */
    private int successDays;

    /** 跳过的天数(数据已存在) */
    private int skippedDays;

    /** 失败的天数 */
    private int failedDays;

    /** 同步的总数据条数 */
    private int totalCount;

    /** 失败的日期及原因 */
    private List<FailedDate> failedDates;

    /** 同步耗时(毫秒) */
    private long elapsedMs;

    public SyncResult() {
        this.failedDates = new ArrayList<>();
    }

    /**
     * 添加失败记录
     */
    public void addFailedDate(String tradeDate, String reason) {
        if (this.failedDates == null) {
            this.failedDates = new ArrayList<>();
        }
        this.failedDates.add(new FailedDate(tradeDate, reason));
        this.failedDays = this.failedDates.size();
    }

    /**
     * 判断是否全部成功
     */
    public boolean isAllSuccess() {
        return failedDays == 0;
    }

    /**
     * 获取结果摘要
     */
    public String getSummary() {
        if (isAllSuccess()) {
            return String.format("同步完成: 共 %d 天, 跳过 %d 天(已存在), 新同步 %d 条数据, 耗时 %.1f 秒",
                    totalDays, skippedDays, totalCount, elapsedMs / 1000.0);
        }
        return String.format("同步部分失败: 请求 %d 天, 成功 %d 天, 跳过 %d 天, 失败 %d 天, 同步 %d 条数据, 耗时 %.1f 秒",
                totalDays, successDays, skippedDays, failedDays, totalCount, elapsedMs / 1000.0);
    }

    /**
     * 失败的日期记录
     */
    @Data
    public static class FailedDate implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private String tradeDate;
        private String reason;

        public FailedDate() {
        }

        public FailedDate(String tradeDate, String reason) {
            this.tradeDate = tradeDate;
            this.reason = reason;
        }
    }
}
