package com.fuli.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.trade.entity.LocalMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface LocalMessageMapper extends BaseMapper<LocalMessage> {

    /**
     * 增加重试次数并设置下次重试时间
     */
    @Update("UPDATE local_message SET retry_count = retry_count + 1, " +
            "next_retry_time = #{nextRetryTime}, status = #{status}, " +
            "last_error = #{lastError}, update_time = NOW() " +
            "WHERE id = #{id}")
    int incrementRetry(@Param("id") Long id,
                       @Param("status") int status,
                       @Param("nextRetryTime") LocalDateTime nextRetryTime,
                       @Param("lastError") String lastError);

    /**
     * 抢占一条待重试消息：把 next_retry_time 推后作为租约，只有抢占成功的实例才处理。
     *
     * <p>利用 update 的条件匹配做 CAS：第一个实例把 next_retry_time 推到未来后，
     * 其他实例的同名条件不再命中，从而避免多实例重复投递。
     *
     * @return 1 表示抢占成功，0 表示已被其他实例抢占或状态已变化
     */
    @Update("UPDATE local_message SET next_retry_time = #{claimUntil}, update_time = NOW() " +
            "WHERE id = #{id} AND status IN (0, 2) " +
            "AND (next_retry_time IS NULL OR next_retry_time <= #{now})")
    int claimMessage(@Param("id") Long id,
                     @Param("now") LocalDateTime now,
                     @Param("claimUntil") LocalDateTime claimUntil);

    /**
     * 标记消息为成功
     */
    @Update("UPDATE local_message SET status = #{status}, update_time = NOW() WHERE id = #{id}")
    int markSuccess(@Param("id") Long id, @Param("status") int status);

    /**
     * 按业务消息 ID 标记成功（用于首次投递即成功的场景，避免消息被重试任务重复扫描）
     *
     * @return 受影响行数；0 表示该消息不存在或已经是成功状态
     */
    @Update("UPDATE local_message SET status = #{status}, update_time = NOW() " +
            "WHERE msg_id = #{msgId} AND status <> #{status}")
    int markSuccessByMsgId(@Param("msgId") String msgId, @Param("status") int status);

    /**
     * 标记消息为死信
     */
    @Update("UPDATE local_message SET status = #{status}, last_error = #{lastError}, update_time = NOW() WHERE id = #{id}")
    int markDeadLetter(@Param("id") Long id, @Param("status") int status, @Param("lastError") String lastError);
}
