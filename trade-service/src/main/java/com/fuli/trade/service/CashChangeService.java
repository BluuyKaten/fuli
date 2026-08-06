package com.fuli.trade.service;

import java.math.BigDecimal;

/**
 * 资金变动服务接口（用于重试）
 */
public interface CashChangeService {

    /**
     * 执行买入扣款
     * @param msgId 消息唯一 ID,用于幂等防重
     */
    void deductCash(Long userId, BigDecimal amount, String msgId);

    /**
     * 执行卖出入账
     * @param msgId 消息唯一 ID,用于幂等防重
     */
    void addCash(Long userId, BigDecimal amount, String msgId);
}
