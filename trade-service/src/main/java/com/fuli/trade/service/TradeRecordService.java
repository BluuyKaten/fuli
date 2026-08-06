package com.fuli.trade.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.IService;
import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.dto.TradeQueryDTO;
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

    boolean clearAllByUserId(Long userId);
}
