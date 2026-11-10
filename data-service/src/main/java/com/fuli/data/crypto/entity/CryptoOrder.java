package com.fuli.data.crypto.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * OKX 订单主表，对应 {@code data_db.crypto_order}（V10）。
 *
 * <p><b>写前日志</b>：状态 {@code pending} 表示「已落库、尚未发网络」。
 * 下单必须先 insert 这一行再调交易所，断网重启时靠它识别孤儿单（实施计划 P4）。
 *
 * <p>时间字段一律毫秒 epoch（OKX 口径），与 {@code bar.ts} 的秒不同；
 * {@code inTime}/{@code outTime} 在 OKX WS 文档里是微秒，见 DDL 列注释。
 */
@Data
@TableName("crypto_order")
public class CryptoOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 客户自定义订单 ID，幂等键，最长 32 位。 */
    private String clOrdId;

    /** 交易所订单 ID，全局唯一；入口被拒时可为空。 */
    private String ordId;

    private String instId;

    private String instType;

    /** 见 {@link com.fuli.data.crypto.CryptoOrderState}。 */
    private String state;

    private String side;

    private String ordType;

    private BigDecimal px;

    /** SPOT 为基础币数量；合约为张数，按 instType 区分口径。 */
    private BigDecimal sz;

    /** 本次推送成交量。 */
    private BigDecimal fillSz;

    /** 累计成交量；WS 推送中始终是累计值，不是增量。 */
    private BigDecimal accFillSz;

    private BigDecimal avgPx;

    private BigDecimal fee;

    private String feeCcy;

    private BigDecimal pnl;

    private String category;

    private BigDecimal lever;

    /** cash / cross / isolated。 */
    private String tdMode;

    /** net / long / short。 */
    private String posSide;

    /** 最新成交 ID，用于与持仓对账。 */
    private String tradeId;

    private String posId;

    /** 撤单原因，区分系统撤单与自成交保护。 */
    private String cancelSource;

    /** 入口 sCode，非 0 表示被拒（此时没有 WS 推送）。 */
    private String sCode;

    private String sMsg;

    private Long cTime;

    private Long uTime;

    private Long fillTime;

    private Long inTime;

    private Long outTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
