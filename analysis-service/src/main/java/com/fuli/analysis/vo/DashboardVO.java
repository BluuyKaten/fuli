package com.fuli.analysis.vo;

import com.fuli.common.api.vo.PositionVO;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 仪表盘数据 VO。
 *
 * <p>替代裸 {@code Map<String, Object>}，为 dashboard 接口提供强类型返回值，
 * 便于前端类型推断与接口文档生成。
 */
@Data
public class DashboardVO {

    /** 总资产（资金余额 + 持仓总市值） */
    private BigDecimal totalAssets;

    /** 盈利百分比（浮动盈亏 / 持仓总成本 × 100%） */
    private BigDecimal profitPercentage;

    /** 浮动盈亏（总市值 - 持仓总成本） */
    private BigDecimal floatingProfitLoss;

    /** 持仓总市值 */
    private BigDecimal totalMarketValue;

    /** 资金余额 */
    private BigDecimal cashBalance;

    /** 持仓明细 */
    private List<PositionVO> positions;
}
