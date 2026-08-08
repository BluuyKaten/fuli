package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.enums.TradeTypeEnum;
import com.fuli.common.api.exception.BizCode;
import com.fuli.common.api.exception.BusinessException;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.Result;
import com.fuli.common.api.vo.StatisticsVO;
import com.fuli.common.api.vo.TradeVO;
import com.fuli.trade.config.FuliProperties;
import com.fuli.trade.dto.CashChangeMessage;
import com.fuli.trade.entity.LocalMessage;
import com.fuli.trade.entity.StockDailyData;
import com.fuli.trade.entity.TradeRecord;
import com.fuli.trade.event.TradeCreatedEvent;
import com.fuli.trade.event.TradeDeletedEvent;
import com.fuli.trade.mapper.StockDailyDataMapper;
import com.fuli.trade.mapper.TradeRecordMapper;
import com.fuli.trade.service.CashChangeService;
import com.fuli.trade.service.LocalMessageService;
import com.fuli.trade.service.PositionSummaryService;
import com.fuli.trade.service.TradeRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TradeRecordServiceImpl extends com.baomidou.mybatisplus.spring.service.impl.ServiceImpl<TradeRecordMapper, TradeRecord> implements TradeRecordService {

    private final PositionSummaryService positionSummaryService;
    private final LocalMessageService localMessageService;
    private final ApplicationEventPublisher eventPublisher;
    private final StockDailyDataMapper stockDailyDataMapper;
    private final AuthFeignClient authFeignClient;
    private final FuliProperties fuliProperties;
    private final CashChangeService cashChangeService;

    public TradeRecordServiceImpl(PositionSummaryService positionSummaryService,
                                  LocalMessageService localMessageService,
                                  ApplicationEventPublisher eventPublisher,
                                  StockDailyDataMapper stockDailyDataMapper,
                                  AuthFeignClient authFeignClient,
                                  FuliProperties fuliProperties,
                                  CashChangeService cashChangeService) {
        this.positionSummaryService = positionSummaryService;
        this.localMessageService = localMessageService;
        this.eventPublisher = eventPublisher;
        this.stockDailyDataMapper = stockDailyDataMapper;
        this.authFeignClient = authFeignClient;
        this.fuliProperties = fuliProperties;
        this.cashChangeService = cashChangeService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTrade(TradeDTO tradeDTO) {
        // 1. 基础校验
        if (tradeDTO.getUserId() == null) {
            throw new BusinessException(400, "用户ID不能为空");
        }
        if (tradeDTO.getTradePrice() == null || tradeDTO.getTradePrice().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "成交价格必须大于0");
        }
        if (tradeDTO.getTradeQuantity() == null || tradeDTO.getTradeQuantity() <= 0) {
            throw new BusinessException(400, "成交数量必须大于0");
        }

        TradeRecord record = new TradeRecord();
        BeanUtils.copyProperties(tradeDTO, record);

        // 2. 计算成交金额
        BigDecimal tradeAmount = tradeDTO.getTradePrice().multiply(BigDecimal.valueOf(tradeDTO.getTradeQuantity()));
        record.setTradeAmount(tradeAmount);
        record.setCommission(tradeDTO.getCommission() != null ? tradeDTO.getCommission() : BigDecimal.ZERO);
        record.setTax(tradeDTO.getTax() != null ? tradeDTO.getTax() : BigDecimal.ZERO);

        BigDecimal cashChangeAmount; // 需要变动的资金金额

        if (TradeTypeEnum.SELL.getCode().equals(tradeDTO.getTradeType())) {
            // 3. 卖出：先校验持仓是否足够，并获取可卖数量
            int holdingQuantity = positionSummaryService.getHoldingQuantity(tradeDTO.getUserId(), tradeDTO.getStockCode());
            if (holdingQuantity <= 0) {
                throw new BusinessException(BizCode.POSITION_INSUFFICIENT, "当前无持仓，无法卖出");
            }
            if (tradeDTO.getTradeQuantity() > holdingQuantity) {
                throw new BusinessException(BizCode.POSITION_INSUFFICIENT,
                        "卖出数量超过持仓，当前可卖 " + holdingQuantity + " 股");
            }

            // 4. 校验卖出价格是否在涨跌停范围内
            validateSellPrice(tradeDTO.getStockCode(), tradeDTO.getTradePrice(), tradeDTO.getTradeDate());

            // 5. 获取加权平均成本
            BigDecimal avgCost = positionSummaryService.getAvgCost(tradeDTO.getUserId(), tradeDTO.getStockCode());
            if (avgCost == null) {
                avgCost = tradeDTO.getTradePrice(); // 兜底
            }

            // 6. 计算卖出盈亏
            BigDecimal totalCost = tradeAmount.subtract(record.getCommission()).subtract(record.getTax());
            record.setTotalCost(totalCost);

            BigDecimal profitLoss = tradeDTO.getTradePrice().subtract(avgCost)
                    .multiply(BigDecimal.valueOf(tradeDTO.getTradeQuantity()))
                    .subtract(record.getCommission()).subtract(record.getTax());
            record.setProfitLoss(profitLoss);

            if (avgCost.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal ratio = profitLoss.divide(
                        avgCost.multiply(BigDecimal.valueOf(tradeDTO.getTradeQuantity())),
                        4, RoundingMode.HALF_UP);
                record.setProfitLossRatio(ratio);
            }

            // 7. 卖出入账金额 = 卖出金额 - 手续费 - 印花税
            cashChangeAmount = totalCost;

        } else {
            // 7. 买入：总成本 = 成交金额 + 手续费 + 印花税
            cashChangeAmount = tradeAmount.add(record.getCommission()).add(record.getTax());
            record.setTotalCost(cashChangeAmount);

            // 8. 校验买入资金是否充足
            validateBuyCash(tradeDTO.getUserId(), cashChangeAmount);
        }

        if (tradeDTO.getTradeTime() == null) {
            record.setTradeTime(LocalDateTime.now());
        }

        // 8. 保存交易记录
        save(record);

        // 9. 更新持仓汇总
        if (TradeTypeEnum.SELL.getCode().equals(tradeDTO.getTradeType())) {
            positionSummaryService.decreasePositionAtomic(tradeDTO.getUserId(), tradeDTO.getStockCode(), tradeDTO.getTradeQuantity());
        } else {
            positionSummaryService.increasePosition(tradeDTO.getUserId(), tradeDTO.getStockCode(),
                    tradeDTO.getStockName(), tradeDTO.getTradeQuantity(), tradeDTO.getTradePrice());
        }

        // 10. 写入本地消息表（用于跨服务事务最终一致性）
        String topic = TradeTypeEnum.SELL.getCode().equals(tradeDTO.getTradeType()) ? "TRADE_SELL" : "TRADE_BUY";
        String msgId = UUID.randomUUID().toString();
        String payload = cashChangeService.serializePayload(CashChangeMessage.builder()
                .userId(tradeDTO.getUserId())
                .amount(cashChangeAmount)
                .msgId(msgId)
                .direction(tradeDTO.getTradeType())
                .build());
        LocalMessage message = localMessageService.createPendingMessage(msgId, topic, payload);
        if (tradeDTO.getRemark() != null && !tradeDTO.getRemark().isEmpty()) {
            record.setRemark(tradeDTO.getRemark() + " [msgId=" + msgId + "]");
        } else {
            record.setRemark("[msgId=" + msgId + "]");
        }

        // 11. 发布事件（事务提交后异步处理资金变动,携带 msgId 走幂等）
        eventPublisher.publishEvent(new TradeCreatedEvent(
                this, record.getId(), tradeDTO.getUserId(), tradeDTO.getTradeType(),
                tradeDTO.getStockCode(), tradeDTO.getStockName(),
                cashChangeAmount, tradeDTO.getTradePrice(), tradeDTO.getTradeQuantity(), msgId));

        return record.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateTrade(Long id, TradeDTO tradeDTO) {
        TradeRecord existing = getById(id);
        if (existing == null) {
            return false;
        }
        // 编辑交易不允许修改数量/类型/价格/日期,避免破坏持仓与资金一致性
        // 仅允许修改备注。如需修改核心字段,请删除后重新录入。
        if (tradeDTO.getRemark() != null) {
            existing.setRemark(tradeDTO.getRemark());
        }
        return updateById(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTrade(Long id) {
        // 所有删除统一走回滚逻辑，避免裸删除导致持仓与资金不一致
        return deleteTradeWithRollback(id);
    }

    @Override
    public boolean isLastTrade(Long id) {
        TradeRecord trade = getById(id);
        if (trade == null) {
            return false;
        }
        // 查找该用户该股票在 trade 之后是否还有交易(按 trade_date 降,id 降,取最大一笔与当前比较)
        LambdaQueryWrapper<TradeRecord> latestWrapper = new LambdaQueryWrapper<>();
        latestWrapper.eq(TradeRecord::getUserId, trade.getUserId())
                .eq(TradeRecord::getStockCode, trade.getStockCode())
                .orderByDesc(TradeRecord::getTradeDate)
                .orderByDesc(TradeRecord::getId)
                .last("LIMIT 1");
        TradeRecord latest = getOne(latestWrapper);
        return latest != null && latest.getId() != null && latest.getId().equals(trade.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTradeWithRollback(Long id) {
        TradeRecord trade = getById(id);
        if (trade == null) {
            return false;
        }
        if (!isLastTrade(id)) {
            throw new BusinessException(BizCode.NOT_LAST_TRADE, "仅允许删除某股票的最后一笔交易,请先删除后续交易");
        }

        // 1. 回滚持仓
        if (TradeTypeEnum.BUY.getCode().equals(trade.getTradeType())) {
            // 买入 → 原子减仓
            positionSummaryService.decreasePositionAtomic(trade.getUserId(), trade.getStockCode(), trade.getTradeQuantity());
        } else if (TradeTypeEnum.SELL.getCode().equals(trade.getTradeType())) {
            // 卖出 → 加仓(按卖出价加回)
            positionSummaryService.increasePosition(trade.getUserId(), trade.getStockCode(),
                    trade.getStockName(), trade.getTradeQuantity(), trade.getTradePrice());
        }

        // 2. 反向资金事件（事务提交后异步扣/入），msgId 保证幂等且与正向消息隔离
        String reverseMsgId = "REV-" + trade.getId() + "-" + trade.getTradeType() + "-" + UUID.randomUUID();
        eventPublisher.publishEvent(new TradeDeletedEvent(
                this, trade.getId(), trade.getUserId(), trade.getTradeType(),
                trade.getStockCode(), trade.getStockName(),
                trade.getTotalCost(), trade.getTradeQuantity(), reverseMsgId));

        // 3. 软删除交易记录
        boolean removed = removeById(id);
        log.info("删除交易并回滚: tradeId={}, userId={}, stockCode={}, type={}, qty={}",
                id, trade.getUserId(), trade.getStockCode(), trade.getTradeType(), trade.getTradeQuantity());
        return removed;
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

    @Override
    @Transactional
    public boolean clearAllByUserId(Long userId) {
        LambdaQueryWrapper<TradeRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(TradeRecord::getUserId, userId);
        remove(wrapper);
        return true;
    }

    /**
     * 校验买入资金是否充足
     * 规则：买入总成本(成交金额 + 手续费 + 印花税)不能超过用户可用现金
     * 资金查询失败时,根据 fuli.cash-validation-fail-strategy 决定拒绝或放行
     */
    private void validateBuyCash(Long userId, BigDecimal cashChangeAmount) {
        try {
            Result<BigDecimal> result = authFeignClient.getUserCash(userId);
            if (result == null || result.getCode() != 200 || result.getData() == null) {
                handleCashValidationFailure("查询用户 " + userId + " 资金失败(result 异常),买入总成本 ¥" + cashChangeAmount.setScale(2, RoundingMode.HALF_UP));
                return;
            }
            BigDecimal userCash = result.getData();
            if (cashChangeAmount.compareTo(userCash) > 0) {
                throw new BusinessException(BizCode.CASH_INSUFFICIENT,
                        "买入金额超过可用资金！买入总成本 ¥" + cashChangeAmount.setScale(2, RoundingMode.HALF_UP)
                                + "(含手续费),当前可用现金 ¥" + userCash.setScale(2, RoundingMode.HALF_UP));
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            handleCashValidationFailure("资金校验异常: userId=" + userId + ", error=" + e.getMessage());
        }
    }

    private void handleCashValidationFailure(String reason) {
        if ("allow".equalsIgnoreCase(fuliProperties.getCashValidationFailStrategy())) {
            log.warn("资金校验失败但策略为 allow,放行: {}", reason);
            return;
        }
        // 默认 reject: 拒绝交易,防止透支
        throw new BusinessException(BizCode.CASH_VALIDATION_FAILED,
                "资金校验失败,已拒绝交易以防止透支。原因: " + reason);
    }

    /**
     * 校验卖出价格是否在涨跌停范围内
     * 规则：
     * 1. 卖出价格不能高于涨停价(前收盘价 * 1.1)
     * 2. 卖出价格不能低于跌停价(前收盘价 * 0.9)
     * 3. 卖出价格不能为负数或零
     * 4. 无法获取前收盘价时阻塞(提示同步行情),避免复盘时乱填价格
     */
    private void validateSellPrice(String stockCode, BigDecimal sellPrice, LocalDate tradeDate) {
        if (sellPrice == null || sellPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "卖出价格必须大于 0");
        }

        BigDecimal preClosePrice = getPreClosePrice(stockCode, tradeDate);
        if (preClosePrice == null || preClosePrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(BizCode.MISSING_MARKET_DATA,
                    "无法获取股票 " + stockCode + " 的前收盘价,请先同步行情数据后再录入交易(同步页面:/sync)");
        }

        // 涨停价 = 前收盘价 * 1.1(向上取整到分)
        BigDecimal limitUp = preClosePrice.multiply(new BigDecimal("1.1")).setScale(2, RoundingMode.UP);
        // 跌停价 = 前收盘价 * 0.9(向下取整到分)
        BigDecimal limitDown = preClosePrice.multiply(new BigDecimal("0.9")).setScale(2, RoundingMode.DOWN);

        if (sellPrice.compareTo(limitUp) > 0) {
            throw new BusinessException(BizCode.PRICE_OUT_OF_LIMIT, "卖出价格 " + sellPrice + " 超过涨停价 " + limitUp);
        }
        if (sellPrice.compareTo(limitDown) < 0) {
            throw new BusinessException(BizCode.PRICE_OUT_OF_LIMIT, "卖出价格 " + sellPrice + " 低于跌停价 " + limitDown);
        }
    }

    /**
     * 获取前收盘价
     */
    private BigDecimal getPreClosePrice(String stockCode, LocalDate tradeDate) {
        try {
            // 查询交易日期当天或最近的行情数据
            LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(StockDailyData::getStockCode, stockCode)
                    .le(StockDailyData::getTradeDate, tradeDate.format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                    .orderByDesc(StockDailyData::getTradeDate)
                    .last("LIMIT 1");
            StockDailyData dailyData = stockDailyDataMapper.selectOne(wrapper);
            return dailyData != null ? dailyData.getPreClose() : null;
        } catch (Exception e) {
            log.warn("获取股票 {} 前收盘价失败", stockCode);
            return null;
        }
    }
}
