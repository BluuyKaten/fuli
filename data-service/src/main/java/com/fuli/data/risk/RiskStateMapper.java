package com.fuli.data.risk;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 风控状态 Mapper。跨天归零与峰值更新用 {@code RiskControlService} 里的显式 SQL，
 * 这里只继承 BaseMapper 的按主键读写。
 */
@Mapper
public interface RiskStateMapper extends BaseMapper<RiskState> {
}
