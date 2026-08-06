package com.fuli.data.tushare;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 同步任务进度
 */
@Data
public class SyncProgress implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 任务唯一标识 */
    private String taskId;

    /** 任务状态 */
    private SyncStatus status;

    /** 起始日期 yyyyMMdd */
    private String startDate;

    /** 结束日期 yyyyMMdd */
    private String endDate;

    /** 总天数 */
    private int totalDays;

    /** 已处理天数 */
    private int processedDays;

    /** 成功天数 */
    private int successDays;

    /** 跳过天数(数据已存在) */
    private int skippedDays;

    /** 失败天数 */
    private int failedDays;

    /** 同步的总数据条数 */
    private int totalCount;

    /** 当前正在同步的日期 */
    private String currentDate;

    /** 进度百分比 0-100 */
    private int percent;

    /** 提示信息 */
    private String message;

    /** 开始时间 */
    private LocalDateTime startTime;

    /** 结束时间 */
    private LocalDateTime endTime;

    /** 失败详情 */
    private String errorDetail;

    public SyncProgress() {
        this.status = SyncStatus.PENDING;
        this.startTime = LocalDateTime.now();
    }

    /**
     * 计算百分比
     */
    public void calculatePercent() {
        if (totalDays <= 0) {
            this.percent = 0;
            return;
        }
        this.percent = Math.min(100, (int) ((processedDays * 100.0) / totalDays));
    }

    /**
     * 同步任务状态枚举
     */
    public enum SyncStatus {
        /** 等待中 */
        PENDING,
        /** 运行中 */
        RUNNING,
        /** 已完成 */
        COMPLETED,
        /** 失败 */
        FAILED
    }
}
