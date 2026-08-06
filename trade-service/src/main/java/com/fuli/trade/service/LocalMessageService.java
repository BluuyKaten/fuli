package com.fuli.trade.service;

import com.fuli.trade.entity.LocalMessage;

import java.util.List;

/**
 * 本地消息服务接口
 */
public interface LocalMessageService {

    /**
     * 保存待处理消息（需在调用方事务内执行）
     * @param msgId   消息唯一 ID（由调用方生成并同时用于幂等）
     * @param topic   消息主题
     * @param payload 消息体
     * @return 已持久化的 LocalMessage 实体
     */
    LocalMessage createPendingMessage(String msgId, String topic, String payload);

    /**
     * 标记消息成功
     */
    void markSuccess(Long messageId);

    /**
     * 增加重试次数，失败超过最大次数则标记死信
     */
    void incrementRetryOrDeadLetter(Long messageId, String error);

    /**
     * 扫描待重试消息
     */
    List<LocalMessage> listRetryableMessages(int limit);
}
