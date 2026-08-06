package com.fuli.trade.service;

/**
 * 资金变动服务接口（用于重试）
 */
public interface CashChangeService {

    /**
     * 执行买入扣款
     */
    void deductCash(Long userId, java.math.BigDecimal amount);

    /**
     * 执行卖出入账
     */
    void addCash(Long userId, java.math.BigDecimal amount);
}
