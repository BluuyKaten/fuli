package com.fuli.trade.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.trade.dto.CashChangeMessage;
import com.fuli.trade.entity.LocalMessage;
import com.fuli.trade.service.CashChangeService;
import com.fuli.trade.service.LocalMessageRetryService;
import com.fuli.trade.service.LocalMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 本地消息重试服务（定时扫描并重试失败消息）
 */
@Slf4j
@Service
public class LocalMessageRetryServiceImpl implements LocalMessageRetryService {

    private final LocalMessageService localMessageService;
    private final CashChangeService cashChangeService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LocalMessageRetryServiceImpl(LocalMessageService localMessageService,
                                       CashChangeService cashChangeService) {
        this.localMessageService = localMessageService;
        this.cashChangeService = cashChangeService;
    }

    /**
     * 每 5 分钟扫描一次待重试消息
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @Override
    public void retryPendingMessages() {
        try {
            java.util.List<LocalMessage> messages = localMessageService.listRetryableMessages(50);
            if (messages.isEmpty()) {
                return;
            }
            log.info("扫描到 {} 条待重试消息", messages.size());
            for (LocalMessage msg : messages) {
                try {
                    processMessage(msg);
                    localMessageService.markSuccess(msg.getId());
                    log.info("消息重试成功: msgId={}, topic={}", msg.getMsgId(), msg.getTopic());
                } catch (Exception e) {
                    log.warn("消息重试失败: msgId={}, retryCount={}, error={}",
                            msg.getMsgId(), msg.getRetryCount(), e.getMessage());
                    localMessageService.incrementRetryOrDeadLetter(msg.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("重试任务执行异常", e);
        }
    }

    /**
     * 解析 payload 为强类型并执行对应资金操作（带幂等 msgId）。
     *
     * <p>payload 使用 Jackson 反序列化为 {@link CashChangeMessage}，
     * 消息自包含方向（direction），无需依赖 topic 判断。
     */
    private void processMessage(LocalMessage msg) {
        String payload = msg.getPayload();
        if (payload == null || payload.isEmpty()) {
            return;
        }
        CashChangeMessage message;
        try {
            message = objectMapper.readValue(payload, CashChangeMessage.class);
        } catch (Exception e) {
            log.warn("消息 payload 解析失败: msgId={}, payload={}, error={}",
                    msg.getMsgId(), payload, e.getMessage());
            return;
        }
        try {
            cashChangeService.processCashChange(message);
        } catch (IllegalArgumentException e) {
            log.warn("消息处理被拒绝: msgId={}, error={}", msg.getMsgId(), e.getMessage());
        }
    }
}
