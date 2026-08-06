package com.fuli.data.tushare;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fuli.trade.entity.StockDailyData;
import com.fuli.trade.entity.StockInfo;
import com.fuli.trade.mapper.StockDailyDataMapper;
import com.fuli.trade.mapper.StockInfoMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
public class TushareSyncServiceImpl implements TushareSyncService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 单个交易日数据量阈值(含):若数据库中该交易日数据 >= 此值,视为已同步过,跳过
     * A 股全市场目前约 5000+ 只股票,考虑退市等因素,4800 条以上即认为已完整
     */
    private static final int SKIP_THRESHOLD = 4800;

    /**
     * 每分钟最大请求次数,Tushare 基础积分限制 500 次/分钟,留有余量
     */
    private static final int MAX_REQUESTS_PER_MINUTE = 400;

    private final TushareClient tushareClient;
    private final StockInfoMapper stockInfoMapper;
    private final StockDailyDataMapper stockDailyDataMapper;
    private final SyncProgressManager progressManager;

    public TushareSyncServiceImpl(TushareClient tushareClient,
                                  StockInfoMapper stockInfoMapper,
                                  StockDailyDataMapper stockDailyDataMapper,
                                  SyncProgressManager progressManager) {
        this.tushareClient = tushareClient;
        this.stockInfoMapper = stockInfoMapper;
        this.stockDailyDataMapper = stockDailyDataMapper;
        this.progressManager = progressManager;
    }

    @Override
    @Transactional
    public int syncStockBasic() {
        log.info("开始同步股票基础信息...");

        Map<String, Object> params = new HashMap<>();
        params.put("list_status", "L");

        String fields = "ts_code,symbol,name,area,industry,market,list_date";

        TushareResponse response = tushareClient.call("stock_basic", params, fields);

        if (!response.isSuccess()) {
            log.error("获取股票基础信息失败: {}", response.getMsg());
            return 0;
        }

        List<String> fieldNames = response.getData().getFields();
        List<List<Object>> items = response.getData().getItems();

        int count = 0;
        for (List<Object> item : items) {
            try {
                StockInfo stockInfo = new StockInfo();
                for (int i = 0; i < fieldNames.size(); i++) {
                    String field = fieldNames.get(i);
                    Object value = item.get(i);
                    if (value == null) continue;
                    switch (field) {
                        case "ts_code" -> stockInfo.setStockCode(value.toString());
                        case "name" -> stockInfo.setStockName(value.toString());
                        case "area" -> stockInfo.setArea(value.toString());
                        case "industry" -> stockInfo.setIndustry(value.toString());
                        case "market" -> stockInfo.setMarket(value.toString());
                        case "list_date" -> stockInfo.setListDate(value.toString());
                    }
                }
                stockInfo.setStatus(1);
                upsertStockInfo(stockInfo);
                count++;
            } catch (Exception e) {
                log.warn("保存股票信息失败: {}", item, e);
            }
        }

        log.info("股票基础信息同步完成, 共 {} 条", count);
        return count;
    }

    @Override
    @Transactional
    public int syncDailyData(String tsCode, String startDate, String endDate) {
        log.info("开始同步日线数据: {}, {} - {}", tsCode, startDate, endDate);

        if (startDate == null || startDate.isEmpty()) {
            String latestDate = getLatestTradeDate(tsCode);
            if (latestDate != null) {
                LocalDate latest = LocalDate.parse(latestDate, DATE_FMT).plusDays(1);
                LocalDate today = LocalDate.now();
                if (latest.isAfter(today)) {
                    log.info("股票 {} 数据已是最新,无需同步", tsCode);
                    return 0;
                }
                startDate = latest.format(DATE_FMT);
                log.info("增量同步: 从 {} 开始", startDate);
            } else {
                startDate = "20200101";
                log.info("全量同步: 从 {} 开始", startDate);
            }
        }

        String fields = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount";

        TushareResponse response = tushareClient.call("daily", createParams(tsCode, startDate, endDate), fields);

        if (!response.isSuccess()) {
            log.error("获取日线数据失败: {}", response.getMsg());
            return 0;
        }

        List<String> fieldNames = response.getData().getFields();
        List<List<Object>> items = response.getData().getItems();

        int count = 0;
        for (List<Object> item : items) {
            try {
                StockDailyData dailyData = parseDailyData(fieldNames, item);
                upsertDailyData(dailyData);
                count++;
            } catch (Exception e) {
                log.warn("保存日线数据失败: {}", item, e);
            }
        }

        log.info("日线数据同步完成: {}, 共 {} 条", tsCode, count);
        return count;
    }

    @Override
    @Transactional
    public int syncDailyDataByTradeDate(String tradeDate) {
        log.info("开始按交易日期同步日线数据: {}", tradeDate);

        String fields = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount";

        TushareResponse response = tushareClient.call("daily", Map.of("trade_date", tradeDate), fields);

        if (!response.isSuccess()) {
            log.error("获取日线数据失败: {}", response.getMsg());
            return 0;
        }

        List<String> fieldNames = response.getData().getFields();
        List<List<Object>> items = response.getData().getItems();

        int count = 0;
        for (List<Object> item : items) {
            try {
                StockDailyData dailyData = parseDailyData(fieldNames, item);
                upsertDailyData(dailyData);
                count++;
            } catch (Exception e) {
                log.warn("保存日线数据失败: {}", item, e);
            }
        }

        log.info("按交易日期同步日线数据完成: {}, 共 {} 条", tradeDate, count);
        return count;
    }

    @Override
    public SyncStatus getSyncStatus(String tsCode) {
        SyncStatus status = new SyncStatus();
        status.setTsCode(tsCode);

        String latestDate = getLatestTradeDate(tsCode);
        status.setLatestTradeDate(latestDate);

        if (latestDate != null) {
            LocalDate latest = LocalDate.parse(latestDate, DATE_FMT);
            LocalDate today = LocalDate.now();
            long missingDays = java.time.temporal.ChronoUnit.DAYS.between(latest, today);
            status.setMissingDays((int) Math.max(0, missingDays));
            status.setStatus(missingDays <= 1 ? "UP_TO_DATE" : "NEEDS_SYNC");
        } else {
            status.setMissingDays(-1);
            status.setStatus("NO_DATA");
        }

        return status;
    }

    @Override
    public int syncAllStocksIncremental(String customStartDate, String customEndDate) {
        log.info("开始智能增量同步所有股票日线数据...");

        LocalDate startDate;
        LocalDate endDate;

        if (customStartDate != null && !customStartDate.isEmpty()) {
            startDate = LocalDate.parse(customStartDate, DATE_FMT);
            log.info("使用自定义起始日期: {}", customStartDate);
        } else {
            String latestDate = getGlobalLatestTradeDate();
            if (latestDate != null) {
                startDate = LocalDate.parse(latestDate, DATE_FMT).plusDays(1);
                log.info("数据库最新日期: {}, 从 {} 开始同步", latestDate, startDate.format(DATE_FMT));
            } else {
                startDate = LocalDate.of(2020, 1, 1);
                log.info("数据库无数据, 从 20200101 开始全量同步");
            }
        }

        if (customEndDate != null && !customEndDate.isEmpty()) {
            endDate = LocalDate.parse(customEndDate, DATE_FMT);
            log.info("使用自定义结束日期: {}", customEndDate);
        } else {
            endDate = LocalDate.now();
        }

        if (startDate.isAfter(endDate)) {
            log.info("起始日期晚于结束日期,无需同步");
            return 0;
        }

        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        log.info("同步日期范围: {} - {} (共 {} 天)", startDate.format(DATE_FMT), endDate.format(DATE_FMT), totalDays);

        return syncByDateRangeInBatches(startDate, endDate);
    }

    @Override
    public String syncDailyDataByDateRange(String startDate, String endDate, boolean forceSync) {
        // 1. 参数校验
        if (startDate == null || startDate.isEmpty() || endDate == null || endDate.isEmpty()) {
            throw new IllegalArgumentException("起始日期和结束日期不能为空");
        }
        if (startDate.length() != 8 || endDate.length() != 8) {
            throw new IllegalArgumentException("日期格式必须为 yyyyMMdd,例如 20250101");
        }

        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(startDate, DATE_FMT);
            end = LocalDate.parse(endDate, DATE_FMT);
        } catch (Exception e) {
            throw new IllegalArgumentException("日期格式错误: " + e.getMessage(), e);
        }

        if (start.isAfter(end)) {
            throw new IllegalArgumentException("起始日期不能晚于结束日期");
        }

        // 2. 限制最大同步范围,避免时间过长(最多 400 天)
        long totalDays = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        if (totalDays > 400) {
            throw new IllegalArgumentException(
                    "同步日期范围过大(共 " + totalDays + " 天),请分批同步,每次不超过 400 天");
        }

        // 3. 创建同步任务并启动异步执行
        String taskId = generateTaskId();
        SyncProgress progress = progressManager.createTask(taskId, startDate, endDate, (int) totalDays);

        // 异步执行同步任务
        LocalDate finalStart = start;
        LocalDate finalEnd = end;
        new Thread(() -> executeSyncTask(taskId, finalStart, finalEnd, progress, forceSync), "sync-task-" + taskId).start();

        log.info("同步任务已创建: taskId={}, 日期范围: {} - {}, 共 {} 天, forceSync={}",
                taskId, startDate, endDate, totalDays, forceSync);
        return taskId;
    }

    /**
     * 执行同步任务(在后台线程中)
     */
    private void executeSyncTask(String taskId, LocalDate start, LocalDate end, SyncProgress progress, boolean forceSync) {
        SyncResult result = new SyncResult();
        result.setStartDate(start.format(DATE_FMT));
        result.setEndDate(end.format(DATE_FMT));
        result.setTotalDays(progress.getTotalDays());

        long startTime = System.currentTimeMillis();
        int successDays = 0;
        int skippedDays = 0;
        AtomicInteger totalCount = new AtomicInteger(0);

        // 用于限速:记录每分钟的请求次数
        AtomicInteger requestCountInMinute = new AtomicInteger(0);
        long minuteStartTime = startTime;

        progressManager.updateTaskStatus(taskId, SyncProgress.SyncStatus.RUNNING, "同步开始...");

        LocalDate current = start;
        while (!current.isAfter(end)) {
            String tradeDate = current.format(DATE_FMT);

            try {
                // 限速控制
                long now = System.currentTimeMillis();
                if (now - minuteStartTime >= 60000) {
                    requestCountInMinute.set(0);
                    minuteStartTime = now;
                }
                if (requestCountInMinute.get() >= MAX_REQUESTS_PER_MINUTE) {
                    long waitMs = 60000 - (now - minuteStartTime);
                    progressManager.updateTaskStatus(taskId, SyncProgress.SyncStatus.RUNNING,
                            "达到频率限制,等待 " + (waitMs / 1000) + " 秒...");
                    log.info("达到每分钟请求上限 {}, 等待 {} ms", MAX_REQUESTS_PER_MINUTE, waitMs);
                    Thread.sleep(waitMs);
                    requestCountInMinute.set(0);
                    minuteStartTime = System.currentTimeMillis();
                }

                // 检查该交易日是否已同步过(forceSync=true 时跳过此检查,强制重新同步)
                if (!forceSync && isTradeDateAlreadySynced(tradeDate)) {
                    skippedDays++;
                    progressManager.updateProgress(taskId, p -> {
                        p.setProcessedDays(p.getProcessedDays() + 1);
                        p.setSkippedDays(p.getSkippedDays() + 1);
                        p.setCurrentDate(tradeDate);
                        p.setMessage("跳过 " + tradeDate + "(已存在)");
                    });
                    current = current.plusDays(1);
                    continue;
                }

                // 同步该交易日数据
                requestCountInMinute.incrementAndGet();
                progressManager.updateProgress(taskId, p -> {
                    p.setCurrentDate(tradeDate);
                    p.setMessage("正在同步 " + tradeDate + "...");
                });

                int count = syncDailyDataByTradeDateInternal(tradeDate);
                successDays++;
                totalCount.addAndGet(count);

                progressManager.updateProgress(taskId, p -> {
                    p.setProcessedDays(p.getProcessedDays() + 1);
                    p.setSuccessDays(p.getSuccessDays() + 1);
                    p.setTotalCount(p.getTotalCount() + count);
                    p.setCurrentDate(tradeDate);
                    p.setMessage("同步 " + tradeDate + " 完成,新增 " + count + " 条");
                });

                log.info("交易日 {} 同步完成, 新增 {} 条, 累计 {} 条", tradeDate, count, totalCount.get());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.addFailedDate(tradeDate, "线程被中断: " + e.getMessage());
                log.error("同步被中断,当前日期: {}", tradeDate, e);
                progressManager.failTask(taskId, "同步被中断", e.getMessage());
                return;
            } catch (Exception e) {
                result.addFailedDate(tradeDate, e.getMessage());
                log.error("交易日 {} 同步失败", tradeDate, e);
                progressManager.updateProgress(taskId, p -> {
                    p.setProcessedDays(p.getProcessedDays() + 1);
                    p.setFailedDays(p.getFailedDays() + 1);
                    p.setCurrentDate(tradeDate);
                    p.setMessage(tradeDate + " 同步失败: " + e.getMessage());
                });
            }

            current = current.plusDays(1);
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        result.setSuccessDays(successDays);
        result.setSkippedDays(skippedDays);
        result.setTotalCount(totalCount.get());
        result.setElapsedMs(elapsedMs);

        log.info("===== 按日期范围同步结束: {} =====", result.getSummary());

        // 更新最终进度
        if (result.isAllSuccess()) {
            progressManager.completeTask(taskId, result.getSummary());
        } else {
            StringBuilder errorDetail = new StringBuilder();
            for (SyncResult.FailedDate failed : result.getFailedDates()) {
                errorDetail.append(failed.getTradeDate()).append(": ").append(failed.getReason()).append("\n");
            }
            progressManager.failTask(taskId, result.getSummary(), errorDetail.toString());
        }
    }

    /**
     * 生成任务 ID
     */
    private String generateTaskId() {
        return "SYNC" + System.currentTimeMillis();
    }

    /**
     * 判断某交易日是否已同步过(数据量达到阈值)
     */
    private boolean isTradeDateAlreadySynced(String tradeDate) {
        LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDailyData::getTradeDate, tradeDate);
        Long count = stockDailyDataMapper.selectCount(wrapper);
        return count != null && count >= SKIP_THRESHOLD;
    }

    /**
     * 内部方法:同步指定交易日数据(不检查限速,由调用方控制)
     */
    private int syncDailyDataByTradeDateInternal(String tradeDate) {
        String fields = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount";

        TushareResponse response = tushareClient.call("daily", Map.of("trade_date", tradeDate), fields);

        if (!response.isSuccess()) {
            throw new RuntimeException("Tushare 接口返回失败: " + response.getMsg());
        }

        List<List<Object>> items = response.getData().getItems();
        if (items == null || items.isEmpty()) {
            log.warn("交易日 {} 无数据", tradeDate);
            return 0;
        }

        List<String> fieldNames = response.getData().getFields();
        int count = 0;
        for (List<Object> item : items) {
            try {
                StockDailyData dailyData = parseDailyData(fieldNames, item);
                upsertDailyData(dailyData);
                count++;
            } catch (Exception e) {
                log.warn("保存日线数据失败: {}", item, e);
            }
        }

        return count;
    }

    private int syncByDateRangeInBatches(LocalDate startDate, LocalDate endDate) {
        int totalCount = 0;
        int batchCount = 0;
        long startTime = System.currentTimeMillis();

        // 按交易日逐个同步,避免单次请求数据量超过 Tushare 接口上限
        // (daily 接口不传 ts_code 时返回全市场数据,但单次调用有条数限制)
        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            String tradeDate = current.format(DATE_FMT);
            batchCount++;
            log.info("同步交易日 {} (第 {} 个)", tradeDate, batchCount);

            int count = syncDailyDataByTradeDateInternal(tradeDate);
            totalCount += count;
            log.info("交易日 {} 完成, 保存 {} 条, 累计 {} 条", tradeDate, count, totalCount);

            current = current.plusDays(1);
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("智能增量同步所有股票完成, 共 {} 个交易日, {} 条数据, 耗时 {} 秒", batchCount, totalCount, totalTime / 1000);
        return totalCount;
    }

    private int syncLatestAvailableDate(String afterDate) {
        log.info("尝试获取最近可用交易日数据...");
        String fields = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount";

        LocalDate tryDate = LocalDate.now().minusDays(1);
        for (int i = 0; i < 10; i++) {
            String tryDateStr = tryDate.format(DATE_FMT);
            if (afterDate != null && tryDateStr.compareTo(afterDate) <= 0) {
                break;
            }
            log.info("尝试获取 {} 的数据", tryDateStr);
            TushareResponse response = tushareClient.call("daily", Map.of("trade_date", tryDateStr), fields);
            if (response.isSuccess() && response.getData() != null
                    && response.getData().getItems() != null && !response.getData().getItems().isEmpty()) {
                log.info("获取到 {} 的数据,共 {} 条", tryDateStr, response.getData().getItems().size());
                int count = 0;
                for (List<Object> item : response.getData().getItems()) {
                    try {
                        StockDailyData dailyData = parseDailyData(response.getData().getFields(), item);
                        upsertDailyData(dailyData);
                        count++;
                    } catch (Exception e) {
                        log.warn("保存日线数据失败: {}", item, e);
                    }
                }
                return count;
            }
            tryDate = tryDate.minusDays(1);
        }
        log.warn("未找到可用数据");
        return 0;
    }

    private String getLatestTradeDate(String tsCode) {
        LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDailyData::getStockCode, tsCode)
                .orderByDesc(StockDailyData::getTradeDate)
                .last("LIMIT 1");
        StockDailyData latest = stockDailyDataMapper.selectOne(wrapper);
        return latest != null ? latest.getTradeDate() : null;
    }

    private String getGlobalLatestTradeDate() {
        LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(StockDailyData::getTradeDate).last("LIMIT 1");
        StockDailyData latest = stockDailyDataMapper.selectOne(wrapper);
        return latest != null ? latest.getTradeDate() : null;
    }

    private Map<String, Object> createParams(String tsCode, String startDate, String endDate) {
        Map<String, Object> params = new HashMap<>();
        if (tsCode != null && !tsCode.isEmpty()) {
            params.put("ts_code", tsCode);
        }
        if (startDate != null && !startDate.isEmpty()) {
            params.put("start_date", startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            params.put("end_date", endDate);
        }
        return params;
    }

    private StockDailyData parseDailyData(List<String> fieldNames, List<Object> item) {
        StockDailyData dailyData = new StockDailyData();
        for (int i = 0; i < fieldNames.size(); i++) {
            String field = fieldNames.get(i);
            Object value = item.get(i);
            if (value == null) continue;
            switch (field) {
                case "ts_code" -> dailyData.setStockCode(value.toString());
                case "trade_date" -> dailyData.setTradeDate(value.toString());
                case "open" -> dailyData.setOpenPrice(new BigDecimal(value.toString()));
                case "high" -> dailyData.setHighPrice(new BigDecimal(value.toString()));
                case "low" -> dailyData.setLowPrice(new BigDecimal(value.toString()));
                case "close" -> dailyData.setClosePrice(new BigDecimal(value.toString()));
                case "pre_close" -> dailyData.setPreClose(new BigDecimal(value.toString()));
                case "change" -> dailyData.setChangeAmount(new BigDecimal(value.toString()));
                case "pct_chg" -> dailyData.setPctChg(new BigDecimal(value.toString()));
                case "vol" -> dailyData.setVol(new BigDecimal(value.toString()));
                case "amount" -> dailyData.setAmount(new BigDecimal(value.toString()));
            }
        }
        return dailyData;
    }

    private void upsertStockInfo(StockInfo stockInfo) {
        StockInfo existing = stockInfoMapper.selectById(stockInfo.getStockCode());
        if (existing != null) {
            stockInfoMapper.updateById(stockInfo);
        } else {
            stockInfoMapper.insert(stockInfo);
        }
    }

    private void upsertDailyData(StockDailyData dailyData) {
        LambdaQueryWrapper<StockDailyData> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StockDailyData::getStockCode, dailyData.getStockCode())
               .eq(StockDailyData::getTradeDate, dailyData.getTradeDate());
        StockDailyData existing = stockDailyDataMapper.selectOne(wrapper);
        if (existing != null) {
            dailyData.setId(existing.getId());
            stockDailyDataMapper.updateById(dailyData);
        } else {
            stockDailyDataMapper.insert(dailyData);
        }
    }
}
