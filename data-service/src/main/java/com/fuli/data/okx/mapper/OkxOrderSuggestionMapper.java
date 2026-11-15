package com.fuli.data.okx.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.data.okx.entity.OkxOrderSuggestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** Agent 建议单 Mapper（V11）。 */
@Mapper
public interface OkxOrderSuggestionMapper extends BaseMapper<OkxOrderSuggestion> {

    @Select("SELECT * FROM okx_order_suggestion WHERE user_id = #{userId} ORDER BY id DESC LIMIT #{limit}")
    List<OkxOrderSuggestion> selectRecentByUser(@Param("userId") long userId, @Param("limit") int limit);

    /** 原子抢占待确认建议单，只有一条请求可以把 new 改为 executing。 */
    @Update("UPDATE okx_order_suggestion SET status = 'executing', update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 'new'")
    int claimNew(@Param("id") long id, @Param("userId") long userId);

    /** 原子完成建议单，仅允许 executing 改为 executed。 */
    @Update("UPDATE okx_order_suggestion SET status = 'executed', update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 'executing'")
    int markExecuted(@Param("id") long id, @Param("userId") long userId);

    /** 已知业务失败时原子回退建议单，仅允许 executing 改回 new。 */
    @Update("UPDATE okx_order_suggestion SET status = 'new', update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 'executing'")
    int resetExecuting(@Param("id") long id, @Param("userId") long userId);

    /** 原子拒绝待确认建议单，仅允许 new 改为 rejected。 */
    @Update("UPDATE okx_order_suggestion SET status = 'rejected', update_time = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND user_id = #{userId} AND status = 'new'")
    int rejectNew(@Param("id") long id, @Param("userId") long userId);
}
