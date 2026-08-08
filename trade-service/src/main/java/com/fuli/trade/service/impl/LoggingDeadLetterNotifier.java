package com.fuli.trade.service.impl;

import com.fuli.trade.entity.LocalMessage;
import com.fuli.trade.service.DeadLetterNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 默认死信告警实现：仅输出错误日志。
 *
 * <p>生产环境可替换为带 webhook / 邮件通道的实现。
 * 告警内容包含 msgId、topic、重试次数、错误信息，便于定位。
 */
@Slf4j
@Component
public class LoggingDeadLetterNotifier implements DeadLetterNotifier {

    @Override
    public void onDeadLetter(LocalMessage message) {
        log.error("【死信告警】消息超过最大重试次数进入死信队列，需人工介入！"
                        + "msgId={}, topic={}, retryCount={}, maxRetry={}, lastError={}, payload={}",
                message.getMsgId(),
                message.getTopic(),
                message.getRetryCount(),
                message.getMaxRetry(),
                message.getLastError(),
                message.getPayload());
    }
}
