package com.fuli.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.trade.entity.TradeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface TradeRecordMapper extends BaseMapper<TradeRecord> {

    /**
     * 交易统计聚合：单条 SQL 完成 COUNT/SUM/MAX/MIN，避免把区间内全部交易拉到内存再遍历。
     *
     * @return 聚合结果 Map（totalTrades/buyCount/sellCount/totalBuyAmount/totalSellAmount/
     *         totalProfitLoss/winCount/lossCount/totalWinProfit/totalLossAmount/maxProfit/maxLoss）
     */
    @Select("<script>" +
            "SELECT COUNT(*) AS totalTrades, " +
            "COALESCE(SUM(CASE WHEN trade_type = 1 THEN 1 ELSE 0 END), 0) AS buyCount, " +
            "COALESCE(SUM(CASE WHEN trade_type = 2 THEN 1 ELSE 0 END), 0) AS sellCount, " +
            "COALESCE(SUM(CASE WHEN trade_type = 1 THEN total_cost ELSE 0 END), 0) AS totalBuyAmount, " +
            "COALESCE(SUM(CASE WHEN trade_type = 2 THEN trade_amount ELSE 0 END), 0) AS totalSellAmount, " +
            "COALESCE(SUM(profit_loss), 0) AS totalProfitLoss, " +
            "COALESCE(SUM(CASE WHEN trade_type = 2 AND profit_loss &gt; 0 THEN 1 ELSE 0 END), 0) AS winCount, " +
            "COALESCE(SUM(CASE WHEN trade_type = 2 AND profit_loss &lt; 0 THEN 1 ELSE 0 END), 0) AS lossCount, " +
            "COALESCE(SUM(CASE WHEN trade_type = 2 AND profit_loss &gt; 0 THEN profit_loss ELSE 0 END), 0) AS totalWinProfit, " +
            "COALESCE(SUM(CASE WHEN trade_type = 2 AND profit_loss &lt; 0 THEN profit_loss ELSE 0 END), 0) AS totalLossAmount, " +
            "MAX(CASE WHEN trade_type = 2 AND profit_loss &gt; 0 THEN profit_loss END) AS maxProfit, " +
            "MIN(CASE WHEN trade_type = 2 AND profit_loss &lt; 0 THEN profit_loss END) AS maxLoss " +
            "FROM trade_record WHERE deleted = 0 " +
            "<if test='userId != null'> AND user_id = #{userId}</if>" +
            "<if test='stockCode != null and stockCode != \"\"'> AND stock_code = #{stockCode}</if>" +
            "<if test='tradeType != null'> AND trade_type = #{tradeType}</if>" +
            "<if test='startDate != null'> AND trade_date &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND trade_date &lt;= #{endDate}</if>" +
            "</script>")
    Map<String, Object> aggregateStatistics(TradeQueryDTO query);

    /**
     * 月度盈亏聚合：单条 SQL 完成「按月分组 + 计数 + 求和 + 盈利笔数」，
     * 替代「按区间拉全部交易再在内存里 stream 分组」——N 笔交易变成 M 个月（M ≤ 几十）。
     *
     * <p>口径必须与旧的内存实现逐位一致（旧实现在 analysis-service，已改为调用本端点）：
     * <ul>
     *   <li>只统计 {@code profit_loss IS NOT NULL} 的交易（买入行没有盈亏，不计入笔数与胜率分母）；</li>
     *   <li>月份取 {@code yyyy-MM}，与 {@code LocalDate.format("yyyy-MM")} 完全一致（月补零）；</li>
     *   <li>{@code winCount} 是 {@code profit_loss > 0} 的笔数，0 盈亏既不算赢也不算输。</li>
     * </ul>
     *
     * <p>月份用 {@code EXTRACT} + {@code LPAD} 拼，不用 MySQL 专有的 {@code DATE_FORMAT}：
     * 同一句 SQL 要能在 H2（测试，MODE=MySQL）里跑出同样的字符串，否则这条聚合只能靠人工验证。
     * 别名取 {@code monthKey} 而非 {@code month}——{@code MONTH} 在 H2 是保留字。
     *
     * @return 每行 monthKey/tradeCount/profitLoss/winCount，按月份升序
     */
    @Select("<script>" +
            "SELECT CONCAT(EXTRACT(YEAR FROM trade_date), '-', LPAD(EXTRACT(MONTH FROM trade_date), 2, '0')) AS monthKey, " +
            "COUNT(*) AS tradeCount, " +
            "COALESCE(SUM(profit_loss), 0) AS profitLoss, " +
            "COALESCE(SUM(CASE WHEN profit_loss &gt; 0 THEN 1 ELSE 0 END), 0) AS winCount " +
            "FROM trade_record WHERE deleted = 0 AND profit_loss IS NOT NULL " +
            "<if test='userId != null'> AND user_id = #{userId}</if>" +
            "<if test='stockCode != null and stockCode != \"\"'> AND stock_code = #{stockCode}</if>" +
            "<if test='tradeType != null'> AND trade_type = #{tradeType}</if>" +
            "<if test='startDate != null'> AND trade_date &gt;= #{startDate}</if>" +
            "<if test='endDate != null'> AND trade_date &lt;= #{endDate}</if>" +
            "GROUP BY CONCAT(EXTRACT(YEAR FROM trade_date), '-', LPAD(EXTRACT(MONTH FROM trade_date), 2, '0')) " +
            "ORDER BY monthKey" +
            "</script>")
    List<Map<String, Object>> aggregateMonthlyProfit(TradeQueryDTO query);
}
