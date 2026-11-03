package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuli.common.api.enums.TradeTypeEnum;
import com.fuli.trade.dto.CashChangeMessage;
import com.fuli.trade.entity.LocalMessage;
import com.fuli.trade.mapper.LocalMessageMapper;
import com.fuli.trade.service.CashChangeService;
import com.fuli.trade.service.CashCompensationService;
import com.fuli.trade.service.LocalMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 买入资金补偿：把扣款方向反转成入账，交给本地消息表异步投递。
 *
 * <p>必须使用 REQUIRES_NEW：调用时机是原事务回滚之后的 afterCompletion，
 * 若加入原事务，补偿记录会随回滚一起丢失。
 */
@Slf4j
@Service
public class CashCompensationServiceImpl implements CashCompensationService {

    /** 补偿消息主题，与普通交易消息区分，便于排查 */
    static final String TOPIC_COMPENSATE = "TRADE_COMPENSATE";

    private final LocalMessageService localMessageService;
    private final LocalMessageMapper localMessageMapper;
    private final CashChangeService cashChangeService;

    public CashCompensationServiceImpl(LocalMessageService localMessageService,
                                       LocalMessageMapper localMessageMapper,
                                       CashChangeService cashChangeService) {
        this.localMessageService = localMessageService;
        this.localMessageMapper = localMessageMapper;
        this.cashChangeService = cashChangeService;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void scheduleRefund(Long userId, BigDecimal amount, String originMsgId) {
        if (userId == null || amount == null || amount.signum() <= 0 || originMsgId == null) {
            throw new IllegalArgumentException("退款补偿参数不完整: userId=" + userId
                    + ", amount=" + amount + ", originMsgId=" + originMsgId);
        }
        String refundMsgId = "REF-" + originMsgId;
        if (localMessageMapper.selectCount(new LambdaQueryWrapper<LocalMessage>()
                .eq(LocalMessage::getMsgId, refundMsgId)) > 0) {
            log.info("退款补偿已存在，跳过: msgId={}", refundMsgId);
            return;
        }

        // 方向取 SELL，重试任务解析后执行入账（addCash）
        String payload = cashChangeService.serializePayload(CashChangeMessage.builder()
                .userId(userId)
                .amount(amount)
                .msgId(refundMsgId)
                .direction(TradeTypeEnum.SELL.getCode())
                .build());
        localMessageService.createPendingMessage(refundMsgId, TOPIC_COMPENSATE, payload);
        log.warn("登记买入退款补偿: msgId={}, userId={}, amount={}", refundMsgId, userId, amount);
    }
}
