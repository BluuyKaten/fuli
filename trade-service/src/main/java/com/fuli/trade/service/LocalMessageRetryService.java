package com.fuli.trade.service;

/**
 * 本地消息重试服务接口
 */
public interface LocalMessageRetryService {

    /**
     * 扫描并重试待处理消息
     */
    void retryPendingMessages();
}
