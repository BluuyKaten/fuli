package com.fuli.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.vo.MonthlyProfitVO;
import com.fuli.common.api.vo.PositionVO;
import com.fuli.common.api.vo.StatisticsVO;
import com.fuli.common.api.vo.TradeVO;
import com.fuli.trade.entity.TradeRecord;

import java.util.List;

public interface TradeRecordService extends IService<TradeRecord> {

    Long createTrade(TradeDTO tradeDTO);

    boolean updateTrade(Long id, TradeDTO tradeDTO);

    boolean deleteTrade(Long id);

    /**
     * 删除交易并回滚持仓与资金（仅允许删除某股票最后一笔交易）
     * @return true-删除成功；false-不是最后一笔或不存在
     */
    boolean deleteTradeWithRollback(Long id);

    /**
     * 判断某笔交易是否是该股票最后一笔
     */
    boolean isLastTrade(Long id);

    TradeVO getTradeById(Long id);

    List<TradeVO> listTrades(TradeQueryDTO queryDTO);

    Page<TradeVO> pageTrades(TradeQueryDTO queryDTO);

    List<TradeVO> queryByCondition(TradeQueryDTO queryDTO);

    StatisticsVO getStatistics(TradeQueryDTO queryDTO);

    /**
     * 月度盈亏（SQL 分组聚合）：按月返回笔数、盈亏合计与胜率，供 analysis-service 直接转发。
     *
     * <p>此前由 analysis-service 拉全量交易后在内存里 stream 分组；区间一长就是几千行 Feign 载荷，
     * 而结果最多几十行。口径与旧实现一致：只统计 {@code profit_loss} 非空的交易。
     */
    List<MonthlyProfitVO> getMonthlyProfit(TradeQueryDTO queryDTO);

    boolean clearAllByUserId(Long userId);

    /** 查询用户所有持仓（复用 position_summary 成本口径，含 T+1 可卖数量）。 */
    List<PositionVO> getUserPositions(Long userId);
}
