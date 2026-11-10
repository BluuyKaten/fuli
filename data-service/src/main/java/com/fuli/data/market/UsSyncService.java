package com.fuli.data.market;

import com.fuli.common.api.util.StockCodeUtil;
import com.fuli.data.cache.DailyKlineCache;
import com.fuli.data.entity.AdjFactor;
import com.fuli.data.entity.Bar;
import com.fuli.data.entity.Instrument;
import com.fuli.data.mapper.AdjFactorMapper;
import com.fuli.data.mapper.BarMapper;
import com.fuli.data.mapper.InstrumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * 美股行情同步（阶段 1）：东财搜索校验 → 拉日线落 {@code bar} + 注册 {@code instrument} +
 * 计算复权因子落 {@code adj_factor}。
 *
 * <p>只同步手工录入清单内的标的（不拉全市场，见 {@code multi-market-rollout.md} 1.2/1.3）。
 * 日线 {@code ts} = 美股交易日 09:30（美东）转 UTC 秒，时区用 {@code America/New_York}
 * 由 JDK 自动处理夏令时/冬令时。
 */
@Slf4j
@Service
public class UsSyncService {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private final EastMoneyUsDataSource dataSource;
    private final BarMapper barMapper;
    private final InstrumentMapper instrumentMapper;
    private final AdjFactorMapper adjFactorMapper;
    private final DailyKlineCache klineCache;
    private final UsMarketProperties properties;

    public UsSyncService(EastMoneyUsDataSource dataSource,
                         BarMapper barMapper,
                         InstrumentMapper instrumentMapper,
                         AdjFactorMapper adjFactorMapper,
                         DailyKlineCache klineCache,
                         UsMarketProperties properties) {
        this.dataSource = dataSource;
        this.barMapper = barMapper;
        this.instrumentMapper = instrumentMapper;
        this.adjFactorMapper = adjFactorMapper;
        this.klineCache = klineCache;
        this.properties = properties;
    }

    /**
     * @param symbolRaw 完整符号，如 {@code AAPL.US}
     * @return 落库的日线根数
     */
    public int sync(String symbolRaw) {
        String symbol = resolveUs(symbolRaw);
        String code = StockCodeUtil.pureCode(symbol);

        UsMarket.UsStockMatch match = dataSource.search(code);
        if (match == null) {
            throw new IllegalArgumentException("东财未找到美股：" + code);
        }

        UsMarket.UsDailyResult raw = dataSource.fetchDaily(match.quoteId(), false);
        registerInstrument(symbol, match, raw.priceScale());

        int count = 0;
        for (UsMarket.UsCandle candle : raw.candles()) {
            barMapper.upsert(toBar(symbol, candle));
            count++;
        }

        // 复权因子：fqt=1 / fqt=0 求相对因子（消除拆股假阴线）
        try {
            UsMarket.UsDailyResult fwd = dataSource.fetchDaily(match.quoteId(), true);
            Map<LocalDate, BigDecimal> factors = UsAdjustFactor.compute(raw.candles(), fwd.candles());
            for (Map.Entry<LocalDate, BigDecimal> e : factors.entrySet()) {
                AdjFactor af = new AdjFactor();
                af.setInstrumentCode(symbol);
                af.setTs(toUsTs(e.getKey()));
                af.setFactor(e.getValue());
                adjFactorMapper.upsert(af);
            }
        } catch (Exception e) {
            // 复权因子是派生数据，失败不影响不复权日线可用
            log.warn("美股复权因子计算失败: {} ({})", symbol, e.getMessage());
        }

        if (count > 0) {
            klineCache.evict(symbol, "1d");
        }
        log.info("美股同步完成: {} ({}) {} 根", symbol, match.name(), count);
        return count;
    }

    private void registerInstrument(String symbol, UsMarket.UsStockMatch match, int priceScale) {
        Instrument ins = new Instrument();
        ins.setCode(symbol);
        ins.setMarket("US");
        ins.setName(match.name().isBlank() ? StockCodeUtil.pureCode(symbol) : match.name());
        ins.setCurrency("USD");
        ins.setPriceScale(priceScale > 0 ? priceScale : 2);
        ins.setLotSize(properties.getLotSize());
        ins.setTickSize(properties.getTickSize());
        ins.setTz("America/New_York");
        ins.setSession("DAY");
        ins.setStatus(1);
        ins.setExchange(match.exchange().isBlank() ? null : match.exchange());
        instrumentMapper.upsert(ins);
    }

    private Bar toBar(String symbol, UsMarket.UsCandle candle) {
        Bar bar = new Bar();
        bar.setInstrumentCode(symbol);
        bar.setPeriod("1d");
        bar.setTs(toUsTs(candle.date()));
        bar.setOpenPrice(candle.open());
        bar.setHighPrice(candle.high());
        bar.setLowPrice(candle.low());
        bar.setClosePrice(candle.close());
        bar.setPreClose(null);
        bar.setVolume(candle.volume());
        bar.setAmount(candle.amount());
        return bar;
    }

    /** 美股交易日 → 该日 09:30 美东的 UTC 秒。 */
    static long toUsTs(java.time.LocalDate date) {
        return date.atTime(9, 30).atZone(NEW_YORK).toEpochSecond();
    }

    private static String resolveUs(String symbolRaw) {
        String resolved = StockCodeUtil.resolve(symbolRaw);
        if (!"US".equals(StockCodeUtil.marketOf(resolved))) {
            throw new IllegalArgumentException("不是美股标的：" + symbolRaw + "（需带 .US 后缀）");
        }
        return resolved;
    }
}
