package com.fuli.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.trade.entity.PositionSummary;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;

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
     * 原子增加持仓数量(均价由调用方计算后通过 updateAvgCost 写入)。
     * 与 selectForUpdate 行锁配合，串行化同一股票并发加仓的读改写。
     */
    @Update("UPDATE position_summary " +
            "SET total_quantity = total_quantity + #{quantity}, update_time = NOW() " +
            "WHERE user_id = #{userId} AND stock_code = #{stockCode}")
    int increaseQuantity(@Param("userId") Long userId,
                         @Param("stockCode") String stockCode,
                         @Param("quantity") int quantity);

    /**
     * 写入加权平均成本（配合 selectForUpdate 行锁，均价基于锁内读到的旧值计算）。
     */
    @Update("UPDATE position_summary " +
            "SET avg_cost = #{avgCost}, update_time = NOW() " +
            "WHERE user_id = #{userId} AND stock_code = #{stockCode}")
    int updateAvgCost(@Param("userId") Long userId,
                      @Param("stockCode") String stockCode,
                      @Param("avgCost") BigDecimal avgCost);

    /**
     * 行锁读取持仓：仅用于 increasePosition 内的读改写串行化，必须在事务内调用。
     * 注意：手写 SQL 不会自动附加 @TableLogic 的 deleted 条件，这里显式过滤。
     */
    @Select("SELECT * FROM position_summary " +
            "WHERE user_id = #{userId} AND stock_code = #{stockCode} AND deleted = 0 FOR UPDATE")
    PositionSummary selectForUpdate(@Param("userId") Long userId,
                                    @Param("stockCode") String stockCode);

    /**
     * 幂等建仓：并发首次建仓时，唯一键 uk_user_stock 冲突会走到 ON DUPLICATE KEY UPDATE，
     * 而不是抛出 DuplicateKeyException。仅更新 stock_name 并复活可能被逻辑删除的行，
     * 不改变 total_quantity / avg_cost。
     */
    @Insert("INSERT INTO position_summary (user_id, stock_code, stock_name, total_quantity, avg_cost, deleted) " +
            "VALUES (#{userId}, #{stockCode}, #{stockName}, 0, 0, 0) " +
            "ON DUPLICATE KEY UPDATE stock_name = VALUES(stock_name), deleted = 0")
    int insertIfAbsent(@Param("userId") Long userId,
                       @Param("stockCode") String stockCode,
                       @Param("stockName") String stockName);
}
