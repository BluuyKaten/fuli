package com.fuli.trade.service.impl;

import com.fuli.trade.entity.LocalMessage;
import com.fuli.trade.mapper.LocalMessageMapper;
import com.fuli.trade.service.DeadLetterNotifier;
import com.fuli.trade.service.LocalMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class LocalMessageServiceImpl implements LocalMessageService {

    private final LocalMessageMapper localMessageMapper;
    private final List<DeadLetterNotifier> deadLetterNotifiers;

    public LocalMessageServiceImpl(LocalMessageMapper localMessageMapper,
                                   List<DeadLetterNotifier> deadLetterNotifiers) {
        this.localMessageMapper = localMessageMapper;
        // 若无告警器，使用空列表，避免 NPE
        this.deadLetterNotifiers = deadLetterNotifiers != null ? deadLetterNotifiers : Collections.emptyList();
    }

    @Override
    public LocalMessage createPendingMessage(String msgId, String topic, String payload) {
        LocalMessage message = new LocalMessage();
        message.setMsgId(msgId != null ? msgId : UUID.randomUUID().toString());
        message.setTopic(topic);
        message.setPayload(payload);
        message.setStatus(LocalMessage.STATUS_PENDING);
        message.setRetryCount(0);
        message.setMaxRetry(3);
        message.setNextRetryTime(LocalDateTime.now().plusMinutes(1));
        localMessageMapper.insert(message);
        return message;
    }

    @Override
    public void markSuccess(Long messageId) {
        localMessageMapper.markSuccess(messageId, LocalMessage.STATUS_SUCCESS);
    }

    @Override
    public void incrementRetryOrDeadLetter(Long messageId, String error) {
        LocalMessage msg = localMessageMapper.selectById(messageId);
        if (msg == null) {
            return;
        }
        int newRetryCount = msg.getRetryCount() + 1;
        if (newRetryCount >= msg.getMaxRetry()) {
            // 超过最大重试次数，标记死信
            String deadLetterMsg = "超过最大重试次数(" + msg.getMaxRetry() + "): " + error;
            localMessageMapper.markDeadLetter(messageId, LocalMessage.STATUS_DEAD_LETTER, deadLetterMsg);
            log.error("消息进入死信队列: msgId={}, topic={}, retryCount={}", msg.getMsgId(), msg.getTopic(), newRetryCount);
            // 触发死信告警通知（可插拔：日志 / webhook / 邮件等）
            notifyDeadLetter(msg);
        } else {
            // 指数退避：1分钟、2分钟、4分钟...
            long delayMinutes = (long) Math.pow(2, newRetryCount - 1);
            LocalDateTime nextRetry = LocalDateTime.now().plusMinutes(delayMinutes);
            localMessageMapper.incrementRetry(messageId, LocalMessage.STATUS_FAILED, nextRetry, error);
        }
    }

    @Override
    public List<LocalMessage> listRetryableMessages(int limit) {
        // 查询 PENDING 或 FAILED 且 next_retry_time <= 当前时间的消息
        return localMessageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LocalMessage>()
                        .in(LocalMessage::getStatus, LocalMessage.STATUS_PENDING, LocalMessage.STATUS_FAILED)
                        .le(LocalMessage::getNextRetryTime, LocalDateTime.now())
                        .orderByAsc(LocalMessage::getNextRetryTime)
                        .last("LIMIT " + limit)
        );
    }

    /** 触发所有注册的死信告警通知器，单个通知器异常不影响其他 */
    private void notifyDeadLetter(LocalMessage message) {
        for (DeadLetterNotifier notifier : deadLetterNotifiers) {
            try {
                notifier.onDeadLetter(message);
            } catch (Exception e) {
                log.warn("死信告警通知器执行异常: notifier={}, error={}",
                        notifier.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
