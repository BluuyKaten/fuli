package com.fuli.trade.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.enums.TradeTypeEnum;
import com.fuli.common.api.exception.BizCode;
import com.fuli.common.api.exception.BusinessException;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.feign.DataFeignClient;
import com.fuli.common.api.Result;
import com.fuli.common.api.vo.PositionVO;
import com.fuli.common.api.vo.StatisticsVO;
import com.fuli.common.api.vo.TradeVO;
import com.fuli.common.api.util.StockCodeUtil;
import com.fuli.trade.config.FuliProperties;
import com.fuli.trade.dto.CashChangeMessage;
import com.fuli.trade.entity.LocalMessage;
import com.fuli.trade.entity.PositionSummary;
import com.fuli.trade.entity.TradeRecord;
import com.fuli.trade.event.TradeCreatedEvent;
import com.fuli.trade.event.TradeDeletedEvent;
import com.fuli.trade.mapper.TradeRecordMapper;
import com.fuli.trade.service.CashChangeService;
import com.fuli.trade.service.CashCompensationService;
import com.fuli.trade.service.LocalMessageService;
import com.fuli.trade.service.PositionSummaryService;
import com.fuli.trade.service.TradeRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class TradeRecordServiceImpl extends com.baomidou.mybatisplus.spring.service.impl.ServiceImpl<TradeRecordMapper, TradeRecord> implements TradeRecordService {

    private final PositionSummaryService positionSummaryService;
    private final LocalMessageService localMessageService;
    private final ApplicationEventPublisher eventPublisher;
    private final DataFeignClient dataFeignClient;
    private final AuthFeignClient authFeignClient;
    private final FuliProperties fuliProperties;
    private final CashChangeService cashChangeService;
    private final CashCompensationService cashCompensationService;

    public TradeRecordServiceImpl(PositionSummaryService positionSummaryService,
                                  LocalMessageService localMessageService,
                                  ApplicationEventPublisher eventPublisher,
                                  DataFeignClient dataFeignClient,
                                  AuthFeignClient authFeignClient,
                                  FuliProperties fuliProperties,
                                  CashChangeService cashChangeService,
                                  CashCompensationService cashCompensationService) {
        this.positionSummaryService = positionSummaryService;
        this.localMessageService = localMessageService;
        this.eventPublisher = eventPublisher;
        this.dataFeignClient = dataFeignClient;
        this.authFeignClient = authFeignClient;
        this.fuliProperties = fuliProperties;
        this.cashChangeService = cashChangeService;
        this.cashCompensationService = cashCompensationService;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTrade(TradeDTO tradeDTO) {
        // 资金一致性模型（三个方向分别处理）：
        //   买入 —— 在本地事务内【先同步原子扣款】，余额不足当场拒单，交易不落库；
        //            若随后本地事务回滚，注册补偿消息退款，避免"钱扣了、单没落"。
        //   卖出 —— 只增不减，本地事务内写消息表，提交后异步入账，失败由重试补投。
        //   删除 —— 本地事务内写反向消息，提交后异步回冲。
        boolean isSell = TradeTypeEnum.SELL.getCode().equals(tradeDTO.getTradeType());

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

        // 幂等去重：同一 requestId 的重复提交（双击/网络重试）直接返回已存在的交易，
        // 在扣款之前拦截，避免重复扣款 + 重复落单。
        if (StringUtils.hasText(tradeDTO.getRequestId())) {
            TradeRecord exist = getByRequestId(tradeDTO.getUserId(), tradeDTO.getRequestId());
            if (exist != null) {
                log.info("重复提交已拦截，返回既有交易: requestId={}, tradeId={}",
                        tradeDTO.getRequestId(), exist.getId());
                return exist.getId();
            }
        }

        TradeRecord record = new TradeRecord();
        BeanUtils.copyProperties(tradeDTO, record);

        // 2. 计算成交金额
        BigDecimal tradeAmount = tradeDTO.getTradePrice().multiply(BigDecimal.valueOf(tradeDTO.getTradeQuantity()));
        record.setTradeAmount(tradeAmount);
        record.setCommission(tradeDTO.getCommission() != null ? tradeDTO.getCommission() : BigDecimal.ZERO);
        record.setTax(tradeDTO.getTax() != null ? tradeDTO.getTax() : BigDecimal.ZERO);

        BigDecimal cashChangeAmount; // 需要变动的资金金额

        if (isSell) {
            // 3. 卖出：按【可卖数量】校验（总持仓扣除当日买入部分，A 股 T+1）
            int availableQuantity = getAvailableQuantity(tradeDTO.getUserId(), tradeDTO.getStockCode());
            if (availableQuantity <= 0) {
                throw new BusinessException(BizCode.POSITION_INSUFFICIENT,
                        "当前无可卖持仓，无法卖出（当日买入部分需下一交易日才可卖出）");
            }
            if (tradeDTO.getTradeQuantity() > availableQuantity) {
                throw new BusinessException(BizCode.POSITION_INSUFFICIENT,
                        "卖出数量超过可卖数量，当前可卖 " + availableQuantity
                                + " 股（当日买入部分受 T+1 限制）");
            }

            // 4. 校验卖出价格是否在交易当天的实际成交区间内
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
        }

        if (tradeDTO.getTradeTime() == null) {
            record.setTradeTime(LocalDateTime.now());
        }

        // 8. 买入：先同步原子扣款（auth 侧条件更新，余额不足即拒单），
        //    并注册回滚补偿，防止"扣款成功但本地事务失败"导致资金凭空消失。
        if (!isSell) {
            String buyMsgId = "BUY-" + UUID.randomUUID();
            if (deductBuyCash(tradeDTO.getUserId(), cashChangeAmount, buyMsgId)) {
                registerRefundOnRollback(tradeDTO.getUserId(), cashChangeAmount, buyMsgId);
            }
        }

        // 9. 保存交易记录。并发重复提交时唯一索引 uk_user_request 兜底：
        //    命中即抛出幂等异常让事务回滚，买入的同步扣款会由 registerRefundOnRollback
        //    退款补偿，不会造成资金多扣。
        try {
            save(record);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            if (StringUtils.hasText(tradeDTO.getRequestId())) {
                throw new BusinessException(BizCode.DUPLICATE_REQUEST, "该笔交易已提交，请勿重复操作");
            }
            throw e;
        }

        // 10. 更新持仓汇总
        if (isSell) {
            positionSummaryService.decreasePositionAtomic(tradeDTO.getUserId(), tradeDTO.getStockCode(), tradeDTO.getTradeQuantity());
        } else {
            positionSummaryService.increasePosition(tradeDTO.getUserId(), tradeDTO.getStockCode(),
                    tradeDTO.getStockName(), tradeDTO.getTradeQuantity(), tradeDTO.getTradePrice());
        }

        // 11. 卖出资金入账走本地消息表 + 事务提交后事件（只增不减，失败可重试）。
        //     买入已在上方完成同步扣款，不再重复投递扣款消息。
        if (isSell) {
            String msgId = UUID.randomUUID().toString();
            String payload = cashChangeService.serializePayload(CashChangeMessage.builder()
                    .userId(tradeDTO.getUserId())
                    .amount(cashChangeAmount)
                    .msgId(msgId)
                    .direction(tradeDTO.getTradeType())
                    .build());
            localMessageService.createPendingMessage(msgId, "TRADE_SELL", payload);

            eventPublisher.publishEvent(new TradeCreatedEvent(
                    this, record.getId(), tradeDTO.getUserId(), tradeDTO.getTradeType(),
                    tradeDTO.getStockCode(), tradeDTO.getStockName(),
                    cashChangeAmount, tradeDTO.getTradePrice(), tradeDTO.getTradeQuantity(), msgId));
        }

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

        // 先删除再按剩余交易重建；任何后续失败会回滚整个本地事务。
        if (!removeById(id)) {
            return false;
        }
        positionSummaryService.rebuildPosition(trade.getUserId(), trade.getStockCode(), trade.getStockName());

        // 反向操作与删除同事务持久化，进程在提交后退出也不会丢失补偿。
        String reverseMsgId = "REV-" + UUID.randomUUID();
        Integer reverseDirection = TradeTypeEnum.BUY.getCode().equals(trade.getTradeType())
                ? TradeTypeEnum.SELL.getCode() : TradeTypeEnum.BUY.getCode();
        String reversePayload = cashChangeService.serializePayload(CashChangeMessage.builder()
                .userId(trade.getUserId())
                .amount(trade.getTotalCost())
                .msgId(reverseMsgId)
                .direction(reverseDirection)
                .build());
        localMessageService.createPendingMessage(reverseMsgId, "TRADE_REVERSE", reversePayload);
        eventPublisher.publishEvent(new TradeDeletedEvent(
                this, trade.getId(), trade.getUserId(), trade.getTradeType(),
                trade.getStockCode(), trade.getStockName(),
                trade.getTotalCost(), trade.getTradeQuantity(), reverseMsgId));

        log.info("删除交易并回滚: tradeId={}, userId={}, stockCode={}, type={}, qty={}",
                id, trade.getUserId(), trade.getStockCode(), trade.getTradeType(), trade.getTradeQuantity());
        return true;
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
        // 单条 SQL 聚合，避免把区间内全部交易拉到内存再遍历
        Map<String, Object> agg = baseMapper.aggregateStatistics(queryDTO);

        StatisticsVO statistics = new StatisticsVO();
        statistics.setUserId(queryDTO.getUserId());
        statistics.setTotalTrades(toInt(pick(agg, "totalTrades")));
        statistics.setBuyCount(toInt(pick(agg, "buyCount")));
        statistics.setSellCount(toInt(pick(agg, "sellCount")));
        statistics.setTotalBuyAmount(toDecimal(pick(agg, "totalBuyAmount")));
        statistics.setTotalSellAmount(toDecimal(pick(agg, "totalSellAmount")));
        statistics.setTotalProfitLoss(toDecimal(pick(agg, "totalProfitLoss")));

        int winCount = toInt(pick(agg, "winCount"));
        int lossCount = toInt(pick(agg, "lossCount"));
        int totalSellWithProfit = winCount + lossCount;
        if (totalSellWithProfit > 0) {
            BigDecimal totalWinProfit = toDecimal(pick(agg, "totalWinProfit"));
            BigDecimal totalLossAmount = toDecimal(pick(agg, "totalLossAmount"));
            BigDecimal maxProfit = toDecimal(pick(agg, "maxProfit"));
            BigDecimal maxLoss = toDecimal(pick(agg, "maxLoss"));

            statistics.setWinRate(BigDecimal.valueOf(winCount)
                    .divide(BigDecimal.valueOf(totalSellWithProfit), 4, RoundingMode.HALF_UP));
            statistics.setAvgProfit(winCount > 0
                    ? totalWinProfit.divide(BigDecimal.valueOf(winCount), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            statistics.setAvgLoss(lossCount > 0
                    ? totalLossAmount.divide(BigDecimal.valueOf(lossCount), 4, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO);
            statistics.setMaxProfit(maxProfit);
            statistics.setMaxLoss(maxLoss);

            if (statistics.getAvgLoss().compareTo(BigDecimal.ZERO) != 0) {
                statistics.setProfitLossRatio(statistics.getAvgProfit().abs()
                        .divide(statistics.getAvgLoss().abs(), 4, RoundingMode.HALF_UP));
            }
        }

        return statistics;
    }

    private int toInt(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 大小写不敏感取聚合结果列：H2 返回大写列名，MySQL 保留原样，这里统一兜底。 */
    private Object pick(Map<String, Object> agg, String key) {
        Object v = agg.get(key);
        if (v != null) return v;
        for (Map.Entry<String, Object> e : agg.entrySet()) {
            if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private BigDecimal toDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try {
            return new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
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

    /** 按幂等键查重：仅用于 createTrade 的重复提交去重（含逻辑删除过滤）。 */
    private TradeRecord getByRequestId(Long userId, String requestId) {
        return getOne(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId)
                .eq(TradeRecord::getRequestId, requestId)
                .last("LIMIT 1"));
    }

    /**
     * 查询用户所有持仓，复用 position_summary 的加权平均成本口径（而非用历史总买入重算），
     * 并带上 T+1 可卖数量，供 analysis-service 的 Dashboard 使用。
     */
    @Override
    public List<PositionVO> getUserPositions(Long userId) {
        List<PositionSummary> positions = positionSummaryService.listByUser(userId);
        List<PositionVO> result = new ArrayList<>();
        for (PositionSummary p : positions) {
            PositionVO vo = new PositionVO();
            vo.setStockCode(p.getStockCode());
            vo.setStockName(p.getStockName());
            vo.setHoldingQuantity(p.getTotalQuantity());
            vo.setAvailableQuantity(getAvailableQuantity(userId, p.getStockCode()));
            vo.setCostPrice(p.getAvgCost());
            result.add(vo);
        }
        return result;
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
     * 可卖数量 = 总持仓 − 当日买入数量（A 股 T+1）。
     *
     * <p>判断依据是交易的 trade_date 而不是录入时间，因此补录历史交易时
     * 不会被误判为冻结；只有"交易日为今天"的买入才计入冻结。
     */
    private int getAvailableQuantity(Long userId, String stockCode) {
        int totalQuantity = positionSummaryService.getHoldingQuantity(userId, stockCode);
        if (totalQuantity <= 0) {
            return Math.max(totalQuantity, 0);
        }
        List<TradeRecord> todayBuys = list(new LambdaQueryWrapper<TradeRecord>()
                .eq(TradeRecord::getUserId, userId)
                .eq(TradeRecord::getStockCode, stockCode)
                .eq(TradeRecord::getTradeType, TradeTypeEnum.BUY.getCode())
                .eq(TradeRecord::getTradeDate, LocalDate.now()));
        int frozenQuantity = todayBuys.stream().mapToInt(TradeRecord::getTradeQuantity).sum();
        return Math.max(0, totalQuantity - frozenQuantity);
    }

    /**
     * 买入扣款：直接调用 auth 的幂等扣款接口（服务端为条件更新 cash >= amount），
     * 把"校验余额"和"扣减余额"合并成一次原子操作，消除并发透支窗口。
     *
     * <p>该远程调用发生在本地事务内部：正常情况下只有毫秒级网络往返，
     * auth 不可用时由 Feign 超时兜底并按 {@code fuli.cash-validation-fail-strategy} 决定拒单或放行。
     *
     * @return true 表示已实际扣款（需要注册回滚补偿）；false 表示未扣款（降级放行场景）
     */
    private boolean deductBuyCash(Long userId, BigDecimal amount, String msgId) {
        Result<Boolean> result;
        try {
            result = authFeignClient.deductCash(userId, amount, msgId);
        } catch (Exception e) {
            handleCashValidationFailure("调用资金服务失败: userId=" + userId + ", error=" + e.getMessage());
            return false;
        }
        if (result == null) {
            handleCashValidationFailure("资金服务无响应: userId=" + userId + ", 金额 ¥" + formatMoney(amount));
            return false;
        }
        if (Integer.valueOf(BizCode.CASH_INSUFFICIENT).equals(result.getCode())) {
            throw new BusinessException(BizCode.CASH_INSUFFICIENT,
                    "可用资金不足！买入总成本 ¥" + formatMoney(amount) + "（含手续费），" + result.getMessage());
        }
        if (Integer.valueOf(200).equals(result.getCode())) {
            if (Boolean.TRUE.equals(result.getData())) {
                return true;
            }
            throw new BusinessException(BizCode.CASH_VALIDATION_FAILED,
                    "资金扣款未获确认，已拒绝交易以防止重复扣款。原因: " + result.getMessage());
        }
        handleCashValidationFailure("资金扣款失败: userId=" + userId + ", 金额 ¥" + formatMoney(amount)
                + ", 返回: " + result.getMessage());
        return false;
    }

    /**
     * 注册事务回滚补偿：本地事务若未提交，则把已扣的资金退回。
     *
     * <p>补偿动作写入本地消息表（独立事务），由既有重试任务投递，
     * 因此补偿本身失败也不会丢失。提交成功时补偿不会触发。
     */
    private void registerRefundOnRollback(Long userId, BigDecimal amount, String originMsgId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("无活动事务，买入扣款无法登记回滚补偿: userId={}, msgId={}", userId, originMsgId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    return;
                }
                try {
                    cashCompensationService.scheduleRefund(userId, amount, originMsgId);
                    log.warn("交易落库失败，已登记资金退款补偿: userId={}, amount={}, originMsgId={}",
                            userId, amount, originMsgId);
                } catch (Exception e) {
                    log.error("登记退款补偿失败，需人工对账: userId={}, amount={}, originMsgId={}",
                            userId, amount, originMsgId, e);
                }
            }
        });
    }

    private static String formatMoney(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
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
     * 复盘录入按交易当天未复权的实际成交区间校验，避免用最新价格或统一涨跌幅推算历史范围。
     * 这验证录入价格的合理性，不保证指定价位一定能撮合成交。
     */
    private void validateSellPrice(String stockCode, BigDecimal sellPrice, LocalDate tradeDate) {
        if (sellPrice == null || sellPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException(400, "卖出价格必须大于 0");
        }

        Map<String, Object> daily = getTradeDayData(stockCode, tradeDate);
        BigDecimal low = null;
        BigDecimal high = null;
        try {
            if (daily != null && daily.get("lowPrice") != null && daily.get("highPrice") != null) {
                low = new BigDecimal(daily.get("lowPrice").toString());
                high = new BigDecimal(daily.get("highPrice").toString());
            }
        } catch (NumberFormatException invalidPrice) {
            log.warn("股票 {} 在 {} 的行情价格格式错误", stockCode, tradeDate);
        }
        if (low == null || high == null || low.signum() <= 0 || high.compareTo(low) < 0) {
            throw new BusinessException(BizCode.MISSING_MARKET_DATA,
                    "无法获取股票 " + stockCode + " 在 " + tradeDate + " 的完整行情,请先同步行情数据后再录入交易(同步页面:/sync)");
        }
        if (sellPrice.compareTo(high) > 0 || sellPrice.compareTo(low) < 0) {
            throw new BusinessException(BizCode.PRICE_OUT_OF_LIMIT,
                    "卖出价格 " + sellPrice + " 不在 " + tradeDate + " 的实际成交区间 [" + low + ", " + high + "] 内");
        }
    }

    /**
     * 取交易日行情：行情库统一使用带市场后缀的代码（如 600519.SH），
     * 而交易记录可能只存了 6 位裸代码，因此首次未命中时补后缀重查一次，
     * 否则裸代码录入的持仓永远无法校验卖出价。
     */
    private Map<String, Object> getTradeDayData(String stockCode, LocalDate tradeDate) {
        if (tradeDate == null || stockCode == null) {
            return null;
        }
        String date = tradeDate.format(DateTimeFormatter.BASIC_ISO_DATE);
        Map<String, Object> row = queryTradeDay(stockCode, date);
        if (row == null && !stockCode.contains(".")) {
            String resolved = StockCodeUtil.resolveOrNull(stockCode);
            if (resolved != null && !resolved.equals(stockCode)) {
                row = queryTradeDay(resolved, date);
                if (row != null) {
                    log.debug("裸代码 {} 补全为 {} 后命中行情", stockCode, resolved);
                }
            }
        }
        return row;
    }

    private Map<String, Object> queryTradeDay(String stockCode, String date) {
        try {
            List<Map<String, Object>> rows = dataFeignClient.getDailyData(stockCode, date, date, null);
            if (rows != null) {
                for (Map<String, Object> row : rows) {
                    if (row != null && date.equals(String.valueOf(row.get("tradeDate")))) {
                        return row;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("获取股票 {} 在 {} 的行情失败: {}", stockCode, date, e.getMessage());
            return null;
        }
    }
}
