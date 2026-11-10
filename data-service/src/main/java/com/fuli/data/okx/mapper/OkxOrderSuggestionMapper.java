package com.fuli.data.okx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.okx.entity.OkxOrderSuggestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Agent 建议单 Mapper（V11）。 */
@Mapper
public interface OkxOrderSuggestionMapper extends BaseMapper<OkxOrderSuggestion> {

    @Select("SELECT * FROM okx_order_suggestion WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
    List<OkxOrderSuggestion> selectRecentByUser(@Param("userId") long userId, @Param("limit") int limit);
}
