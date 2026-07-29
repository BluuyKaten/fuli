package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.enums.TradeTypeEnum;
import com.fuli.common.api.vo.StatisticsVO;
import com.fuli.common.api.vo.TradeVO;
import com.fuli.trade.entity.TradeRecord;
import com.fuli.trade.feign.AuthFeignClient;
import com.fuli.trade.mapper.TradeRecordMapper;
import com.fuli.trade.service.TradeRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TradeRecordServiceImpl extends com.baomidou.mybatisplus.spring.service.impl.ServiceImpl<TradeRecordMapper, TradeRecord> implements TradeRecordService {

    private final AuthFeignClient authFeignClient;

    public TradeRecordServiceImpl(AuthFeignClient authFeignClient) {
        this.authFeignClient = authFeignClient;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTrade(TradeDTO tradeDTO) {
        TradeRecord record = new TradeRecord();
        BeanUtils.copyProperties(tradeDTO, record);

        record.setTradeAmount(tradeDTO.getTradePrice().multiply(BigDecimal.valueOf(tradeDTO.getTradeQuantity())));
        record.setCommission(tradeDTO.getCommission() != null ? tradeDTO.getCommission() : BigDecimal.ZERO);
        record.setTax(tradeDTO.getTax() != null ? tradeDTO.getTax() : BigDecimal.ZERO);

        if (TradeTypeEnum.SELL.getCode().equals(tradeDTO.getTradeType())) {
            record.setTotalCost(record.getTradeAmount().subtract(record.getCommission()).subtract(record.getTax()));
            // 计算卖出盈亏：查询持仓均价
            BigDecimal avgCost = getAvgCost(tradeDTO.getUserId(), tradeDTO.getStockCode());
            if (avgCost != null && avgCost.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal profitLoss = tradeDTO.getTradePrice().subtract(avgCost)
                        .multiply(BigDecimal.valueOf(tradeDTO.getTradeQuantity()))
                        .subtract(record.getCommission()).subtract(record.getTax());
                record.setProfitLoss(profitLoss);
                BigDecimal ratio = profitLoss.divide(
                        avgCost.multiply(BigDecimal.valueOf(tradeDTO.getTradeQuantity())),
                        4, RoundingMode.HALF_UP);
                record.setProfitLossRatio(ratio);
            }
            // 卖出入账
            BigDecimal sellIncome = record.getTradeAmount().subtract(record.getCommission()).subtract(record.getTax());
            authFeignClient.addCash(tradeDTO.getUserId(), sellIncome);
        } else {
            record.setTotalCost(record.getTradeAmount().add(record.getCommission()).add(record.getTax()));
            // 买入扣款
            authFeignClient.deductCash(tradeDTO.getUserId(), record.getTotalCost());
        }

        if (tradeDTO.getTradeTime() == null) {
            record.setTradeTime(LocalDateTime.now());
        }

        save(record);
        return record.getId();
    }

    private BigDecimal getAvgCost(Long userId, String stockCode) {
        LambdaQueryWrapper<TradeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TradeRecord::getUserId, userId)
                .eq(TradeRecord::getStockCode, stockCode)
                .eq(TradeRecord::getTradeType, TradeTypeEnum.BUY.getCode())
                .gt(TradeRecord::getProfitLoss, BigDecimal.ZERO)
                .orderByDesc(TradeRecord::getTradeDate)
                .last("LIMIT 1");
        TradeRecord lastBuy = getOne(wrapper);
        if (lastBuy != null) {
            return lastBuy.getTradePrice();
        }
        // 如果没有盈利的买入记录，取最近一笔买入
        wrapper.clear();
        wrapper.eq(TradeRecord::getUserId, userId)
                .eq(TradeRecord::getStockCode, stockCode)
                .eq(TradeRecord::getTradeType, TradeTypeEnum.BUY.getCode())
                .orderByDesc(TradeRecord::getTradeDate)
                .last("LIMIT 1");
        TradeRecord recentBuy = getOne(wrapper);
        return recentBuy != null ? recentBuy.getTradePrice() : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateTrade(Long id, TradeDTO tradeDTO) {
        TradeRecord existing = getById(id);
        if (existing == null) {
            return false;
        }
        BeanUtils.copyProperties(tradeDTO, existing, "id", "createTime", "updateTime");
        return updateById(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTrade(Long id) {
        return removeById(id);
    }

    @Override
    public TradeVO getTradeById(Long id) {
        TradeRecord record = getById(id);
        return record != null ? convertToVO(record) : null;
    }

    @Override
    public List<TradeVO> listTrades(TradeQueryDTO queryDTO) {
        LambdaQueryWrapper<TradeRecord> wrapper = buildQueryWrapper(queryDTO);
        List<TradeRecord> records = list(wrapper);
        return records.stream().map(this::convertToVO).collect(Collectors.toList());
    }

    @Override
    public Page<TradeVO> pageTrades(TradeQueryDTO queryDTO) {
        Page<TradeRecord> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<TradeRecord> wrapper = buildQueryWrapper(queryDTO);
        wrapper.orderByDesc(TradeRecord::getTradeDate);

        Page<TradeRecord> recordPage = page(page, wrapper);
        Page<TradeVO> voPage = new Page<>(recordPage.getCurrent(), recordPage.getSize(), recordPage.getTotal());
        voPage.setRecords(recordPage.getRecords().stream().map(this::convertToVO).collect(Collectors.toList()));
        return voPage;
    }

    @Override
    public List<TradeVO> queryByCondition(TradeQueryDTO queryDTO) {
        return listTrades(queryDTO);
    }

    @Override
    public StatisticsVO getStatistics(TradeQueryDTO queryDTO) {
        LambdaQueryWrapper<TradeRecord> wrapper = buildQueryWrapper(queryDTO);
        List<TradeRecord> records = list(wrapper);

        StatisticsVO statistics = new StatisticsVO();
        statistics.setUserId(queryDTO.getUserId());
        statistics.setTotalTrades(records.size());

        long buyCount = records.stream().filter(r -> TradeTypeEnum.BUY.getCode().equals(r.getTradeType())).count();
        long sellCount = records.stream().filter(r -> TradeTypeEnum.SELL.getCode().equals(r.getTradeType())).count();
        statistics.setBuyCount((int) buyCount);
        statistics.setSellCount((int) sellCount);

        BigDecimal totalBuyAmount = records.stream()
                .filter(r -> TradeTypeEnum.BUY.getCode().equals(r.getTradeType()))
                .map(TradeRecord::getTotalCost)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        statistics.setTotalBuyAmount(totalBuyAmount);

        BigDecimal totalSellAmount = records.stream()
                .filter(r -> TradeTypeEnum.SELL.getCode().equals(r.getTradeType()))
                .map(TradeRecord::getTradeAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        statistics.setTotalSellAmount(totalSellAmount);

        BigDecimal totalProfitLoss = records.stream()
                .filter(r -> r.getProfitLoss() != null)
                .map(TradeRecord::getProfitLoss)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        statistics.setTotalProfitLoss(totalProfitLoss);

        List<TradeRecord> sellRecords = records.stream()
                .filter(r -> TradeTypeEnum.SELL.getCode().equals(r.getTradeType()))
                .filter(r -> r.getProfitLoss() != null)
                .toList();

        if (!sellRecords.isEmpty()) {
            long winCount = sellRecords.stream().filter(r -> r.getProfitLoss().compareTo(BigDecimal.ZERO) > 0).count();
            BigDecimal winRate = BigDecimal.valueOf(winCount).divide(BigDecimal.valueOf(sellRecords.size()), 4, RoundingMode.HALF_UP);
            statistics.setWinRate(winRate);

            BigDecimal avgProfit = sellRecords.stream()
                    .filter(r -> r.getProfitLoss().compareTo(BigDecimal.ZERO) > 0)
                    .map(TradeRecord::getProfitLoss)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(Math.max(winCount, 1)), 4, RoundingMode.HALF_UP);
            statistics.setAvgProfit(avgProfit);

            BigDecimal avgLoss = sellRecords.stream()
                    .filter(r -> r.getProfitLoss().compareTo(BigDecimal.ZERO) < 0)
                    .map(TradeRecord::getProfitLoss)
                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(Math.max(sellRecords.size() - winCount, 1)), 4, RoundingMode.HALF_UP);
            statistics.setAvgLoss(avgLoss);

            BigDecimal maxProfit = sellRecords.stream()
                    .map(TradeRecord::getProfitLoss)
                    .max(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            statistics.setMaxProfit(maxProfit);

            BigDecimal maxLoss = sellRecords.stream()
                    .map(TradeRecord::getProfitLoss)
                    .min(BigDecimal::compareTo)
                    .orElse(BigDecimal.ZERO);
            statistics.setMaxLoss(maxLoss);

            if (avgLoss.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal profitLossRatio = avgProfit.abs().divide(avgLoss.abs(), 4, RoundingMode.HALF_UP);
                statistics.setProfitLossRatio(profitLossRatio);
            }
        }

        return statistics;
    }

    private LambdaQueryWrapper<TradeRecord> buildQueryWrapper(TradeQueryDTO queryDTO) {
        LambdaQueryWrapper<TradeRecord> wrapper = new LambdaQueryWrapper<>();
        if (queryDTO.getUserId() != null) {
            wrapper.eq(TradeRecord::getUserId, queryDTO.getUserId());
        }
        if (StringUtils.hasText(queryDTO.getStockCode())) {
            wrapper.eq(TradeRecord::getStockCode, queryDTO.getStockCode());
        }
        if (queryDTO.getTradeType() != null) {
            wrapper.eq(TradeRecord::getTradeType, queryDTO.getTradeType());
        }
        if (queryDTO.getStartDate() != null) {
            wrapper.ge(TradeRecord::getTradeDate, queryDTO.getStartDate());
        }
        if (queryDTO.getEndDate() != null) {
            wrapper.le(TradeRecord::getTradeDate, queryDTO.getEndDate());
        }
        return wrapper;
    }

    private TradeVO convertToVO(TradeRecord record) {
        TradeVO vo = new TradeVO();
        BeanUtils.copyProperties(record, vo);
        TradeTypeEnum typeEnum = TradeTypeEnum.of(record.getTradeType());
        if (typeEnum != null) {
            vo.setTradeTypeName(typeEnum.getName());
        }
        return vo;
    }
}
