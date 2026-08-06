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
     * 标记消息为成功
     */
    @Update("UPDATE local_message SET status = #{status}, update_time = NOW() WHERE id = #{id}")
    int markSuccess(@Param("id") Long id, @Param("status") int status);

    /**
     * 标记消息为死信
     */
    @Update("UPDATE local_message SET status = #{status}, last_error = #{lastError}, update_time = NOW() WHERE id = #{id}")
    int markDeadLetter(@Param("id") Long id, @Param("status") int status, @Param("lastError") String lastError);
}
