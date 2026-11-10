package com.fuli.data.risk;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 风控状态 {@code data_db.risk_state}：每个用户一行。
 *
 * <p>维护两类跨请求状态：{@code peakEquity}（回撤峰值基准）与
 * {@code dailyNotional}/{@code dailyDate}（当日累计下单金额，跨天自动归零）。
 */
@Data
@TableName("risk_state")
public class RiskState implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.INPUT)
    private Long userId;

    /** 峰值权益（回撤的基准）；首次下单时取当前权益初始化。 */
    private BigDecimal peakEquity;

    /** 当日累计下单金额。 */
    private BigDecimal dailyNotional;

    /** dailyNotional 对应的交易日。 */
    private LocalDate dailyDate;

    private LocalDateTime updatedAt;
}
