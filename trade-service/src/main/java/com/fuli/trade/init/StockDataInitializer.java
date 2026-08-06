package com.fuli.trade.init;

import com.fuli.trade.entity.StockDailyData;
import com.fuli.trade.entity.StockInfo;
import com.fuli.trade.mapper.StockDailyDataMapper;
import com.fuli.trade.mapper.StockInfoMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Component
public class StockDataInitializer implements ApplicationRunner {

    private final StockDailyDataMapper stockDailyDataMapper;
    private final StockInfoMapper stockInfoMapper;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final LocalDate START_DATE = LocalDate.of(2026, 6, 1);
    // 动态计算结束日期为今天
    private static final LocalDate END_DATE = LocalDate.now();

    public StockDataInitializer(StockDailyDataMapper stockDailyDataMapper, StockInfoMapper stockInfoMapper) {
        this.stockDailyDataMapper = stockDailyDataMapper;
        this.stockInfoMapper = stockInfoMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        initStockInfo();
        initStockDailyData();
    }

    private void initStockInfo() {
        if (stockInfoMapper.selectCount(null) > 0) {
            return;
        }
        List<StockInfo> stocks = new ArrayList<>();
        stocks.add(createStock("000001.SZ", "平安银行", "深圳", "银行", "19910403", "SZ"));
        stocks.add(createStock("000002.SZ", "万科A", "深圳", "房地产", "19910129", "SZ"));
        stocks.add(createStock("600000.SH", "浦发银行", "上海", "银行", "19991110", "SH"));
        stocks.add(createStock("600519.SH", "贵州茅台", "贵州", "白酒", "20010827", "SH"));
        stocks.add(createStock("000858.SZ", "五粮液", "四川", "白酒", "19980427", "SZ"));
        for (StockInfo stock : stocks) {
            stockInfoMapper.insert(stock);
        }
    }

    private StockInfo createStock(String code, String name, String area, String industry, String listDate, String market) {
        StockInfo stock = new StockInfo();
        stock.setStockCode(code);
        stock.setStockName(name);
        stock.setArea(area);
        stock.setIndustry(industry);
        stock.setListDate(listDate);
        stock.setMarket(market);
        stock.setStatus(1);
        return stock;
    }

    private void initStockDailyData() {
        String sampleStock = "000001.SZ";
        long count = stockDailyDataMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<StockDailyData>()
                        .eq(StockDailyData::getStockCode, sampleStock)
        );
        if (count > 10) {
            return;
        }

        String[] stockCodes = {"000001.SZ", "000002.SZ", "600000.SH", "600519.SH", "000858.SZ"};
        BigDecimal[] basePrices = new BigDecimal[]{
                new BigDecimal("12.50"),
                new BigDecimal("18.30"),
                new BigDecimal("9.80"),
                new BigDecimal("1680.00"),
                new BigDecimal("156.00")
        };

        Random random = new Random(42);
        List<StockDailyData> allData = new ArrayList<>();

        for (int s = 0; s < stockCodes.length; s++) {
            String stockCode = stockCodes[s];
            BigDecimal basePrice = basePrices[s];
            LocalDate date = START_DATE;
            BigDecimal lastClose = basePrice;

            while (!date.isAfter(END_DATE)) {
                if (date.getDayOfWeek().getValue() <= 5) {
                    BigDecimal changeRate = BigDecimal.valueOf((random.nextDouble() - 0.48) * 0.06);
                    BigDecimal close = lastClose.multiply(BigDecimal.ONE.add(changeRate))
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal open = lastClose.multiply(BigDecimal.ONE
                            .add(BigDecimal.valueOf((random.nextDouble() - 0.5) * 0.02)))
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal high = open.max(close).multiply(BigDecimal.ONE
                            .add(BigDecimal.valueOf(random.nextDouble() * 0.03)))
                            .setScale(2, RoundingMode.HALF_UP);
                    BigDecimal low = open.min(close).multiply(BigDecimal.ONE
                            .subtract(BigDecimal.valueOf(random.nextDouble() * 0.03)))
                            .setScale(2, RoundingMode.HALF_UP);

                    StockDailyData data = new StockDailyData();
                    data.setStockCode(stockCode);
                    data.setTradeDate(date.format(DATE_FORMAT));
                    data.setOpenPrice(open);
                    data.setHighPrice(high);
                    data.setLowPrice(low);
                    data.setClosePrice(close);
                    data.setPreClose(lastClose);
                    data.setChangeAmount(close.subtract(lastClose).setScale(2, RoundingMode.HALF_UP));
                    data.setPctChg(changeRate.multiply(new BigDecimal("100")).setScale(2, RoundingMode.HALF_UP));
                    data.setVol(BigDecimal.valueOf(500000 + random.nextInt(2000000)));
                    data.setAmount(close.multiply(BigDecimal.valueOf(100000)).setScale(2, RoundingMode.HALF_UP));
                    allData.add(data);

                    lastClose = close;
                }
                date = date.plusDays(1);
            }
        }

        for (StockDailyData data : allData) {
            stockDailyDataMapper.insert(data);
        }
    }
}
