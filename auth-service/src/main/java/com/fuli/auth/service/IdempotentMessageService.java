package com.fuli.auth.service;

import java.math.BigDecimal;

/**
 * 幂等消息服务(防止 Feign 重试导致重复资金变动)
 */
public interface IdempotentMessageService {

    /**
     * 处理带幂等的资金变动。
     *
     * @param msgId          消息唯一 ID
     * @param userId         用户 ID
     * @param amount         变动金额
     * @param cashDirection  资金方向(扣款/入账)
     * @param action         实际执行的资金操作(在事务内调用)
     * @return true-本次处理成功 / 重复请求且原处理成功;
     *         false-重复请求但原处理失败(可重试)
     * @throws RuntimeException 业务异常(如现金不足)
     */
    boolean executeIdempotent(String msgId, Long userId, BigDecimal amount,
                              int cashDirection, Runnable action);
}
