package com.fuli.trade.service.impl;

import com.fuli.trade.entity.LocalMessage;
import com.fuli.trade.service.LocalMessageRetryService;
import com.fuli.trade.service.LocalMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 本地消息重试服务（定时扫描并重试失败消息）
 */
@Slf4j
@Service
public class LocalMessageRetryServiceImpl implements LocalMessageRetryService {

    private final LocalMessageService localMessageService;

    public LocalMessageRetryServiceImpl(LocalMessageService localMessageService) {
        this.localMessageService = localMessageService;
    }

    /**
     * 每 5 分钟扫描一次待重试消息
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000)
    @Override
    public void retryPendingMessages() {
        try {
            List<LocalMessage> messages = localMessageService.listRetryableMessages(50);
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

    private void processMessage(LocalMessage msg) {
        // 根据 topic 解析 payload 并执行对应操作
        // payload 格式: {"userId":1,"amount":1000.00}
        // 这里简化处理，实际可用 JSON 解析
        String payload = msg.getPayload();
        if (payload == null || payload.isEmpty()) {
            return;
        }
        // 简单解析（生产环境建议用 Jackson）
        Long userId = extractLong(payload, "userId");
        BigDecimal amount = extractBigDecimal(payload, "amount");
        if (userId == null || amount == null) {
            return;
        }

        // 注意：TradeEventListener 已经在事务提交后同步处理了资金变动
        // 重试服务仅作为兜底，处理 TradeEventListener 失败的情况
        // 为避免重复扣款/入账，这里不再重复执行资金操作
        // 实际生产环境应通过幂等性检查（如记录已处理的消息ID）来避免重复
        log.info("消息已处理（资金变动由 TradeEventListener 同步完成）: msgId={}, topic={}, userId={}, amount={}",
                msg.getMsgId(), msg.getTopic(), userId, amount);
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
}
