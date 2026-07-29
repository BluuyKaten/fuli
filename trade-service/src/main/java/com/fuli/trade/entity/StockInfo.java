package com.fuli.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

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
