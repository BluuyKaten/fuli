package com.fuli.data.init;

import com.fuli.data.entity.Bar;
import com.fuli.data.entity.Instrument;
import com.fuli.data.entity.StockInfo;
import com.fuli.data.mapper.BarMapper;
import com.fuli.data.mapper.InstrumentMapper;
import com.fuli.data.mapper.StockInfoMapper;
import com.fuli.data.repository.KlineQueryRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 样本行情数据初始化器（仅 local profile）。
 *
 * <p>在本地开发且行情表为空时，插入样本股票基础信息和模拟日线数据，
 * 便于开发调试。生产环境不激活此初始化器。
 */
@Component
@Profile("local")
public class StockDataInitializer implements ApplicationRunner {

    private final BarMapper barMapper;
    private final InstrumentMapper instrumentMapper;
    private final StockInfoMapper stockInfoMapper;

    private static final LocalDate START_DATE = LocalDate.of(2026, 6, 1);
    private static final LocalDate END_DATE = LocalDate.now();

    /** 种子行情的时区，与生产 A 股口径一致（交易日 09:30 CST 开盘）。 */
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    public StockDataInitializer(BarMapper barMapper, InstrumentMapper instrumentMapper,
                                StockInfoMapper stockInfoMapper) {
        this.barMapper = barMapper;
        this.instrumentMapper = instrumentMapper;
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
            instrumentMapper.upsert(toInstrument(stock));
        }
    }

    /**
     * 种子标的也要进 instrument：新代码的 {@code /market/bars} 与 {@code marketCode}
     * 都以 instrument 为准，缺了它本地环境会 404。
     */
    private Instrument toInstrument(StockInfo stock) {
        Instrument instrument = new Instrument();
        instrument.setCode(stock.getStockCode());
        instrument.setMarket(stock.getMarket());
        instrument.setName(stock.getStockName());
        instrument.setCurrency("CNY");
        instrument.setPriceScale(2);
        instrument.setLotSize(new BigDecimal("100"));
        instrument.setTickSize(new BigDecimal("0.01"));
        instrument.setTz("Asia/Shanghai");
        instrument.setSession("DAY");
        instrument.setStatus(1);
        return instrument;
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
        long count = barMapper.countBySymbol(sampleStock, KlineQueryRepository.PERIOD_DAILY);
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
        List<Bar> allData = new ArrayList<>();

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

                    Bar bar = new Bar();
                    bar.setInstrumentCode(stockCode);
                    bar.setPeriod(KlineQueryRepository.PERIOD_DAILY);
                    // 种子数据直接按 bar 口径写入：交易日 09:30 CST = 01:30 UTC
                    bar.setTs(date.atStartOfDay(SHANGHAI).toEpochSecond() + 9 * 3600 + 30 * 60);
                    bar.setOpenPrice(open);
                    bar.setHighPrice(high);
                    bar.setLowPrice(low);
                    bar.setClosePrice(close);
                    bar.setPreClose(lastClose);
                    // 成交量按「股」写（生产口径），成交额按「元」写
                    bar.setVolume(BigDecimal.valueOf((500000 + random.nextInt(2000000)) * 100L));
                    bar.setAmount(close.multiply(BigDecimal.valueOf(100000))
                            .multiply(BigDecimal.valueOf(1000)).setScale(2, RoundingMode.HALF_UP));
                    allData.add(bar);

                    lastClose = close;
                }
                date = date.plusDays(1);
            }
        }

        for (Bar bar : allData) {
            barMapper.upsert(bar);
        }
    }
}
