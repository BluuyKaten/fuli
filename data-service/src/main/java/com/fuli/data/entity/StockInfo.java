package com.fuli.data.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 股票基础信息实体（data-service 自有）。
 *
 * <p>归属 {@code data_db.stock_info} 表，由 data-service 独立维护，
 * 其他服务通过 {@code DataFeignClient} 访问，不再直接读写此表。
 */
@Data
@TableName("stock_info")
public class StockInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private String stockCode;

    private String stockName;

    private String area;

    private String industry;

    private String listDate;

    private String market;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
