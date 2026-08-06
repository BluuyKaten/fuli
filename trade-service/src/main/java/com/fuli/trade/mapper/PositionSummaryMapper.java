package com.fuli.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.trade.entity.PositionSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface PositionSummaryMapper extends BaseMapper<PositionSummary> {

    /**
     * 原子扣减持仓：仅当 total_quantity >= quantity 时才扣减。
     * @return 受影响行数：1-成功，0-持仓不足
     */
    @Update("UPDATE position_summary " +
            "SET total_quantity = total_quantity - #{quantity}, update_time = NOW() " +
            "WHERE user_id = #{userId} AND stock_code = #{stockCode} AND total_quantity >= #{quantity}")
    int decreaseQuantity(@Param("userId") Long userId,
                         @Param("stockCode") String stockCode,
                         @Param("quantity") int quantity);

    /**
     * 原子增加持仓数量(均价由调用方计算后通过 updateById 写入)
     */
    @Update("UPDATE position_summary " +
            "SET total_quantity = total_quantity + #{quantity}, update_time = NOW() " +
            "WHERE user_id = #{userId} AND stock_code = #{stockCode}")
    int increaseQuantity(@Param("userId") Long userId,
                         @Param("stockCode") String stockCode,
                         @Param("quantity") int quantity);
}
