package com.fuli.trade.service;

import java.math.BigDecimal;

/**
 * 资金补偿服务：交易本地事务未能提交时，把已经同步扣除的买入资金退回。
 *
 * <p>补偿动作以本地消息表的形式持久化，由既有的重试任务投递，
 * 因此补偿调用本身失败也不会丢失，可以重试到成功或进入死信告警。
 */
public interface CashCompensationService {

    /**
     * 登记一笔退款补偿。
     *
     * @param userId      用户 ID
     * @param amount      需要退回的金额
     * @param originMsgId 原始扣款消息 ID，用于推导补偿消息 ID 并保证幂等
     */
    void scheduleRefund(Long userId, BigDecimal amount, String originMsgId);
}
