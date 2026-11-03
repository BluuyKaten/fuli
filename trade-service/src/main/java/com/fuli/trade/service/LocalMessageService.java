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
     * 按业务消息 ID 标记成功。
     *
     * <p>事务提交后的异步投递（{@code TradeEventListener}）在第一次就成功时调用此方法，
     * 否则消息会停留在 PENDING，被重试任务在 5 分钟后重复投递（虽有幂等兜底，但会放大请求量）。
     * 消息不存在时静默忽略。
     */
    void markSuccessByMsgId(String msgId);

    /**
     * 增加重试次数，失败超过最大次数则标记死信
     */
    void incrementRetryOrDeadLetter(Long messageId, String error);

    /**
     * 扫描待重试消息
     */
    List<LocalMessage> listRetryableMessages(int limit);

    /**
     * 抢占一条待重试消息（多实例部署时防止重复投递）。
     *
     * <p>抢占成功后该消息的 next_retry_time 会被推后一个租约周期，
     * 若处理过程中进程退出，消息会在租约到期后重新可被扫描。
     *
     * @return true 表示本实例获得处理权
     */
    boolean tryClaim(LocalMessage message);
}
