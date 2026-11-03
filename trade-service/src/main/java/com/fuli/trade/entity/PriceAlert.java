package com.fuli.trade.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("price_alert")
public class PriceAlert implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String stockCode;

    private String stockName;

    /**
     * 预警类型:
     * 1 - 价格上限 / 止盈提醒 (当前价 >= 目标价)
     * 2 - 价格下限 / 止损提醒 (当前价 <= 目标价)
     * 3 - 当日涨幅超过 % (涨幅 >= 目标%)
     * 4 - 当日跌幅超过 % (跌幅 <= -目标%)
     */
    private Integer alertType;

    /** 目标价格或涨跌幅百分比 */
    private BigDecimal targetValue;

    /** 风控策略备注 */
    private String notes;

    /** 0 - 监控中, 1 - 已触发, 2 - 已停用 */
    private Integer status;

    /** 触发时间 */
    private LocalDateTime triggeredTime;

    /** 触发时的实际数值 */
    private BigDecimal triggeredValue;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
