package com.fuli.trade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.trade.entity.TradeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

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
}
