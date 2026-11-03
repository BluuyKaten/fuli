package com.fuli.auth.service.impl;

import com.fuli.auth.entity.IdempotentMessage;
import com.fuli.auth.mapper.IdempotentMessageMapper;
import com.fuli.auth.service.IdempotentMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 幂等消息服务实现
 *
 * 通过 idempotent_message 表的 msg_id 唯一索引 + 状态,保证同一消息只执行一次资金变动。
 */
@Slf4j
@Service
public class IdempotentMessageServiceImpl implements IdempotentMessageService {

    private final IdempotentMessageMapper idempotentMessageMapper;

    public IdempotentMessageServiceImpl(IdempotentMessageMapper idempotentMessageMapper) {
        this.idempotentMessageMapper = idempotentMessageMapper;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean executeIdempotent(String msgId, Long userId, BigDecimal amount,
                                     int cashDirection, Runnable action) {
        if (msgId == null || msgId.isEmpty()) {
            // 无 msgId 时降级为直接执行(向后兼容)
            action.run();
            return true;
        }

        // 1. 尝试插入 PROCESSING 记录
        if (!insertProcessing(msgId, userId, amount, cashDirection)) {
            // 插入失败,说明 msg_id 已存在,查询原状态
            return handleDuplicate(msgId);
        }

        // 2. 执行业务操作
        try {
            action.run();
        } catch (RuntimeException e) {
            // 业务失败：本方法 @Transactional 会随异常整体回滚，第 1 步的幂等记录 INSERT
            // 一并撤销，不会落库 FAILED 状态，因此下次携带同一 msgId 可重新执行（天然支持重试）。
            // 注意：不要在事务内更新 FAILED 状态——它会被随后的回滚一起撤销，是无效操作。
            log.info("幂等操作业务失败，事务回滚，允许重试: msgId={}, error={}", msgId, e.getMessage());
            throw e;
        }

        // 3. 标记成功
        idempotentMessageMapper.updateStatus(msgId, IdempotentMessage.STATUS_SUCCESS);
        return true;
    }

    /**
     * 尝试插入 PROCESSING 记录。返回 false 表示 msg_id 已存在(唯一索引冲突)。
     */
    private boolean insertProcessing(String msgId, Long userId, BigDecimal amount, int cashDirection) {
        try {
            IdempotentMessage record = new IdempotentMessage();
            record.setMsgId(msgId);
            record.setUserId(userId);
            record.setAmount(amount);
            record.setCashDirection(cashDirection);
            record.setStatus(IdempotentMessage.STATUS_PROCESSING);
            record.setCreateTime(LocalDateTime.now());
            record.setUpdateTime(LocalDateTime.now());
            idempotentMessageMapper.insert(record);
            return true;
        } catch (DuplicateKeyException e) {
            // 标准唯一索引冲突
            log.info("幂等记录已存在: msgId={}", msgId);
            return false;
        } catch (RuntimeException e) {
            // MyBatis-Plus 包装的异常,通过消息判断是否唯一索引冲突
            String msg = e.getMessage();
            if (msg != null && (msg.contains("Duplicate") || msg.contains("uk_msg_id")
                    || msg.contains("unique") || msg.contains("DuplicateKey"))) {
                log.info("幂等记录已存在(wrapped): msgId={}, err={}", msgId, msg);
                return false;
            }
            throw e;
        }
    }

    /**
     * 处理重复请求
     */
    private boolean handleDuplicate(String msgId) {
        IdempotentMessage existing = idempotentMessageMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IdempotentMessage>()
                        .eq(IdempotentMessage::getMsgId, msgId));
        if (existing == null) {
            // 极端并发:插入与 select 之间被删除,视为未处理,返回 false 允许重试
            return false;
        }
        Integer status = existing.getStatus();
        if (status == null || status == IdempotentMessage.STATUS_PROCESSING) {
            // 处理中,视为失败(让调用方重试)
            log.info("幂等记录处理中: msgId={}", msgId);
            return false;
        }
        if (status == IdempotentMessage.STATUS_SUCCESS) {
            // 已成功,幂等返回
            log.info("幂等重复成功: msgId={}", msgId);
            return true;
        }
        // STATUS_FAILED: 当前实现不会产生 FAILED 记录（失败即回滚删除），此分支仅为
        // 兼容历史数据或未来可能的设计，返回 false 由调用方决定是否重试。
        log.info("幂等记录上次失败,允许重试: msgId={}", msgId);
        return false;
    }
}
