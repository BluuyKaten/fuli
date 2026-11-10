package com.fuli.data.okx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.okx.entity.OkxAccount;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * OKX 凭证 Mapper（V11）。
 *
 * <p>所有查询都带 {@code user_id} 条件——「按用户隔离」不靠调用方记得传对参数，
 * 而是这里只提供带用户维度的语句，从接口形状上就查不出别人的凭证。
 */
@Mapper
public interface OkxAccountMapper extends BaseMapper<OkxAccount> {

    @Select("SELECT * FROM okx_account WHERE user_id = #{userId} ORDER BY id ASC")
    List<OkxAccount> selectByUser(@Param("userId") long userId);

    @Select("SELECT * FROM okx_account WHERE user_id = #{userId} AND label = #{label} LIMIT 1")
    OkxAccount selectByUserAndLabel(@Param("userId") long userId, @Param("label") String label);

    /** 交易默认取 enabled=1 的第一条；没有则返回 null，由上层给出「请先配置」的提示。 */
    @Select("SELECT * FROM okx_account WHERE user_id = #{userId} AND enabled = 1 ORDER BY id ASC LIMIT 1")
    OkxAccount selectEnabledByUser(@Param("userId") long userId);
}
