package com.fuli.trade.event;

import com.fuli.common.api.enums.TradeTypeEnum;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.trade.service.LocalMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 交易事件监听器（异步处理资金变动）
 */
@Slf4j
@Component
public class TradeEventListener {

    private final AuthFeignClient authFeignClient;
    private final LocalMessageService localMessageService;

    public TradeEventListener(AuthFeignClient authFeignClient, LocalMessageService localMessageService) {
        this.authFeignClient = authFeignClient;
        this.localMessageService = localMessageService;
    }

    /**
     * 在事务提交后异步处理资金变动
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTradeCreated(TradeCreatedEvent event) {
        try {
            if (TradeTypeEnum.BUY.getCode().equals(event.getTradeType())) {
                // 买入扣款
                authFeignClient.deductCash(event.getUserId(), event.getAmount());
            } else if (TradeTypeEnum.SELL.getCode().equals(event.getTradeType())) {
                // 卖出入账
                authFeignClient.addCash(event.getUserId(), event.getAmount());
            }
            log.info("资金变动成功: tradeId={}, type={}, amount={}", event.getTradeId(), event.getTradeType(), event.getAmount());
        } catch (Exception e) {
            log.error("资金变动失败，将进入重试队列: tradeId={}, error={}", event.getTradeId(), e.getMessage());
            // 资金变动失败，抛出异常让重试服务处理
            throw e;
        }
    }
}
