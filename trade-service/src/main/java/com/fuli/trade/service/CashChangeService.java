package com.fuli.trade.service;

import com.fuli.trade.dto.CashChangeMessage;

import java.math.BigDecimal;

/**
 * 资金变动服务接口（用于重试）
 */
public interface CashChangeService {

    /**
     * 执行买入扣款
     * @param msgId 消息唯一 ID,用于幂等防重
     */
    void deductCash(Long userId, BigDecimal amount, String msgId);

    /**
     * 执行卖出入账
     * @param msgId 消息唯一 ID,用于幂等防重
     */
    void addCash(Long userId, BigDecimal amount, String msgId);

    /**
     * 处理一条资金变动消息（payload 已反序列化为强类型）。
     *
     * <p>统一入口：根据消息内容决定扣款或入账，供本地消息重试服务调用。
     *
     * @param message 反序列化后的资金变动消息
     */
    void processCashChange(CashChangeMessage message);

    /**
     * 将强类型资金变动消息序列化为本地消息表的 payload JSON。
     *
     * <p>序列化逻辑集中在服务实现层，调用方无需依赖 JSON 库。
     *
     * @param message 强类型消息
     * @return JSON 字符串，可直接存入本地消息表 payload 字段
     */
    String serializePayload(CashChangeMessage message);
}
