package com.fuli.trade.service;

import com.fuli.trade.entity.LocalMessage;

/**
 * 死信告警通知器 SPI。
 *
 * <p>当本地消息超过最大重试次数进入死信队列时，由该接口的实现负责通知。
 * 默认实现仅打日志，可替换为 webhook / 邮件 / 钉钉等通道。
 *
 * <p>扩展方式：实现此接口并标注 {@code @Component}，Spring 会自动注入到
 * {@link LocalMessageService} 的告警列表中。
 */
public interface DeadLetterNotifier {

    /**
     * 通知死信事件。
     *
     * @param message 进入死信队列的消息（含 msgId、topic、payload、retryCount、lastError）
     */
    void onDeadLetter(LocalMessage message);
}
