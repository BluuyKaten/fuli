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

    TradeVO getTradeById(Long id);

    List<TradeVO> listTrades(TradeQueryDTO queryDTO);

    Page<TradeVO> pageTrades(TradeQueryDTO queryDTO);

    List<TradeVO> queryByCondition(TradeQueryDTO queryDTO);

    StatisticsVO getStatistics(TradeQueryDTO queryDTO);
}
