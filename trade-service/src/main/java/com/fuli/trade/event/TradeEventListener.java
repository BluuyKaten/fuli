package com.fuli.trade.event;

import com.fuli.common.api.enums.TradeTypeEnum;
import com.fuli.trade.service.CashChangeService;
import com.fuli.trade.service.LocalMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 交易事件监听器（事务提交后异步处理资金变动 / 删除回滚）
 */
@Slf4j
@Component
public class TradeEventListener {

    private final CashChangeService cashChangeService;
    private final LocalMessageService localMessageService;

    public TradeEventListener(CashChangeService cashChangeService, LocalMessageService localMessageService) {
        this.cashChangeService = cashChangeService;
        this.localMessageService = localMessageService;
    }

    /**
     * 在事务提交后异步处理资金变动。
     * 注意：买入已改为本地事务内同步扣款，TradeCreatedEvent 仅由卖出路径发布，
     * 因此这里只处理卖出入账（BUY 分支不再可达，已移除）。
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTradeCreated(TradeCreatedEvent event) {
        try {
            if (TradeTypeEnum.SELL.getCode().equals(event.getTradeType())) {
                cashChangeService.addCash(event.getUserId(), event.getAmount(), event.getMsgId());
            }
            // 首次投递即成功：回写消息状态，避免 5 分钟后被重试任务重复投递
            localMessageService.markSuccessByMsgId(event.getMsgId());
            log.info("资金变动成功: tradeId={}, type={}, amount={}, msgId={}",
                    event.getTradeId(), event.getTradeType(), event.getAmount(), event.getMsgId());
        } catch (Exception e) {
            log.error("资金变动失败，将进入重试队列: tradeId={}, msgId={}, error={}",
                    event.getTradeId(), event.getMsgId(), e.getMessage());
            throw e;
        }
    }

    /**
     * 在事务提交后异步处理删除回滚(反向资金操作)
     * 买入 → 反向入账;卖出 → 反向扣款
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleTradeDeleted(TradeDeletedEvent event) {
        try {
            // 事件自带反向 msgId，保证幂等且与正向消息隔离
            if (TradeTypeEnum.BUY.getCode().equals(event.getTradeType())) {
                // 买入被删除 → 反向入账(退回资金)
                cashChangeService.addCash(event.getUserId(), event.getAmount(), event.getMsgId());
            } else if (TradeTypeEnum.SELL.getCode().equals(event.getTradeType())) {
                // 卖出被删除 → 反向扣款(收回资金)
                cashChangeService.deductCash(event.getUserId(), event.getAmount(), event.getMsgId());
            }
            localMessageService.markSuccessByMsgId(event.getMsgId());
            log.info("删除回滚资金变动成功: tradeId={}, type={}, amount={}, msgId={}",
                    event.getTradeId(), event.getTradeType(), event.getAmount(), event.getMsgId());
        } catch (Exception e) {
            log.error("删除回滚资金变动失败: tradeId={}, msgId={}, error={}",
                    event.getTradeId(), event.getMsgId(), e.getMessage());
            throw e;
        }
    }
}
