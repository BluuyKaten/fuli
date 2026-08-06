package com.fuli.trade.service.impl;

import com.fuli.trade.entity.LocalMessage;
import com.fuli.trade.service.CashChangeService;
import com.fuli.trade.service.LocalMessageRetryService;
import com.fuli.trade.service.LocalMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 本地消息重试服务（定时扫描并重试失败消息）
 */
@Slf4j
@Service
public class LocalMessageRetryServiceImpl implements LocalMessageRetryService {

    private final LocalMessageService localMessageService;
    private final CashChangeService cashChangeService;

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
     * 根据 topic 解析 payload 并执行对应资金操作(带幂等 msgId)
     */
    private void processMessage(LocalMessage msg) {
        String payload = msg.getPayload();
        if (payload == null || payload.isEmpty()) {
            return;
        }
        Long userId = extractLong(payload, "userId");
        BigDecimal amount = extractBigDecimal(payload, "amount");
        String msgId = extractString(payload, "msgId");
        if (userId == null || amount == null) {
            return;
        }
        if (msgId == null || msgId.isEmpty()) {
            msgId = msg.getMsgId();
        }

        String topic = msg.getTopic();
        if ("TRADE_BUY".equals(topic)) {
            cashChangeService.deductCash(userId, amount, msgId);
        } else if ("TRADE_SELL".equals(topic)) {
            cashChangeService.addCash(userId, amount, msgId);
        }
    }

    private Long extractLong(String json, String key) {
        try {
            String search = "\"" + key + "\":";
            int idx = json.indexOf(search);
            if (idx < 0) return null;
            int start = idx + search.length();
            int end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            return Long.parseLong(json.substring(start, end).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private BigDecimal extractBigDecimal(String json, String key) {
        try {
            String search = "\"" + key + "\":";
            int idx = json.indexOf(search);
            if (idx < 0) return null;
            int start = idx + search.length();
            int end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            return new BigDecimal(json.substring(start, end).trim());
        } catch (Exception e) {
            return null;
        }
    }

    private String extractString(String json, String key) {
        try {
            String search = "\"" + key + "\":\"";
            int idx = json.indexOf(search);
            if (idx < 0) return null;
            int start = idx + search.length();
            int end = json.indexOf("\"", start);
            if (end < 0) return null;
            return json.substring(start, end);
        } catch (Exception e) {
            return null;
        }
    }
}
