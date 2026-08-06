package com.fuli.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.auth.entity.IdempotentMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 幂等消息 Mapper
 */
@Mapper
public interface IdempotentMessageMapper extends BaseMapper<IdempotentMessage> {

    /**
     * 更新幂等记录状态为成功
     */
    @Update("UPDATE idempotent_message SET status = #{status}, update_time = NOW() WHERE msg_id = #{msgId}")
    int updateStatus(@Param("msgId") String msgId, @Param("status") int status);

    /**
     * 更新幂等记录状态为失败
     */
    @Update("UPDATE idempotent_message SET status = #{status}, error_msg = #{errorMsg}, update_time = NOW() WHERE msg_id = #{msgId}")
    int updateFailed(@Param("msgId") String msgId, @Param("status") int status, @Param("errorMsg") String errorMsg);
}
