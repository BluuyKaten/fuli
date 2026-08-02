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

@Slf4j
@Service
public class TushareSyncServiceImpl implements TushareSyncService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TushareClient tushareClient;
    private final StockInfoMapper stockInfoMapper;
    private final StockDailyDataMapper stockDailyDataMapper;

    public TushareSyncServiceImpl(TushareClient tushareClient,
                                  StockInfoMapper stockInfoMapper,
                                  StockDailyDataMapper stockDailyDataMapper) {
        this.tushareClient = tushareClient;
        this.stockInfoMapper = stockInfoMapper;
        this.stockDailyDataMapper = stockDailyDataMapper;
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

    private int syncByDateRangeInBatches(LocalDate startDate, LocalDate endDate) {
        String fields = "ts_code,trade_date,open,high,low,close,pre_close,change,pct_chg,vol,amount";
        int totalCount = 0;
        int batchCount = 0;
        long startTime = System.currentTimeMillis();

        LocalDate batchStart = startDate;
        while (!batchStart.isAfter(endDate)) {
            LocalDate batchEnd = batchStart.plusDays(30);
            if (batchEnd.isAfter(endDate)) {
                batchEnd = endDate;
            }

            String batchStartStr = batchStart.format(DATE_FMT);
            String batchEndStr = batchEnd.format(DATE_FMT);

            batchCount++;
            log.info("同步批次 {}: {} - {}", batchCount, batchStartStr, batchEndStr);

            TushareResponse response = tushareClient.call("daily",
                    Map.of("start_date", batchStartStr, "end_date", batchEndStr), fields);

            if (!response.isSuccess()) {
                log.error("获取日线数据失败: {}", response.getMsg());
                batchStart = batchEnd.plusDays(1);
                continue;
            }

            List<String> fieldNames = response.getData().getFields();
            List<List<Object>> items = response.getData().getItems();

            if (items != null && !items.isEmpty()) {
                int batchSaved = 0;
                for (List<Object> item : items) {
                    try {
                        StockDailyData dailyData = parseDailyData(fieldNames, item);
                        upsertDailyData(dailyData);
                        batchSaved++;
                    } catch (Exception e) {
                        log.warn("保存日线数据失败: {}", item, e);
                    }
                }
                totalCount += batchSaved;
                log.info("批次 {} 完成, 保存 {} 条, 累计 {} 条", batchCount, batchSaved, totalCount);
            } else {
                log.warn("批次 {} 无数据", batchCount);
            }

            if (batchCount % 100 == 0) {
                long elapsed = System.currentTimeMillis() - startTime;
                double avgTime = elapsed / (double) batchCount;
                long remainingBatches = (java.time.temporal.ChronoUnit.DAYS.between(batchStart, endDate) / 30) + 1;
                long remainingTime = (long) (avgTime * remainingBatches / 1000 / 60);
                log.info("进度: {}/约{} 批次, 已用 {}ms, 预计剩余约 {} 分钟", batchCount, batchCount + remainingBatches, elapsed, remainingTime);
            }

            batchStart = batchEnd.plusDays(1);
        }

        long totalTime = System.currentTimeMillis() - startTime;
        log.info("智能增量同步所有股票完成, 共 {} 个批次, {} 条数据, 耗时 {} 秒", batchCount, totalCount, totalTime / 1000);
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
