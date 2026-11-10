package com.fuli.data.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 复权因子 {@code data_db.adj_factor}：每交易日一行，全量存储。
 *
 * <p>前复权公式 {@code price × factor / factor_latest}，其中 {@code factor_latest}
 * 取窗口末端最新一根 bar 的因子。复权是视图不是事实，原始价才是事实，
 * 因此不落前复权价格序列（ADR-0007）。
 */
@Data
public class AdjFactor implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String instrumentCode;

    /** 与 1d bar 同口径：A 股交易日 01:30 UTC */
    private Long ts;

    /** 累积复权因子，恒 &gt; 0 */
    private BigDecimal factor;
}
