package com.fuli.data.okx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.okx.entity.OkxAuditLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 审计日志 Mapper（V11）。
 *
 * <p>删除分两处约束：{@code create_time < cutoff} 控制保留窗口，
 * {@code LIMIT} 控制单次事务大小——一次删几百万行会长时间持锁并把 undo 撑爆，
 * 分批删能让主库在清理期间保持可写。{@code ORDER BY create_time} 保证优先删最旧的，
 * 万一被中断也不会留下「中间挖空」的数据。
 */
@Mapper
public interface OkxAuditLogMapper extends BaseMapper<OkxAuditLog> {

    @Delete("DELETE FROM okx_audit_log WHERE create_time < #{cutoff} ORDER BY create_time LIMIT #{limit}")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** 仅用于测试与运维核对。 */
    @org.apache.ibatis.annotations.Select("SELECT COUNT(*) FROM okx_audit_log WHERE create_time < #{cutoff}")
    long countOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
