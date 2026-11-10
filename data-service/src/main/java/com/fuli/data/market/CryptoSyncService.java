package com.fuli.data.market;

import com.fuli.data.cache.DailyKlineCache;
import com.fuli.data.entity.Bar;
import com.fuli.data.entity.Instrument;
import com.fuli.data.mapper.BarMapper;
import com.fuli.data.mapper.InstrumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 加密行情同步：拉取指定标的/周期的 K 线，注册 instrument 并落 {@code bar} 表。
 *
 * <p>主源 Binance Vision，失败/空自动切 Gate（{@code multi-market-rollout.md} 阶段 2.1）。
 * 加密无复权，不写 {@code adj_factor}；{@code ts} = bar 开盘时刻（UTC 秒），与数据源原值一致。
 */
@Slf4j
@Service
public class CryptoSyncService {

    /** 本期只支持日线与 4 小时线（ADR-0011 决策 1）。 */
    private static final Set<String> INTERVALS = Set.of("1d", "4h");

    private static final String PRIMARY = "BinanceVision";
    private static final String SECONDARY = "Gate";

    private final List<CryptoDataSource> dataSources;
    private final BarMapper barMapper;
    private final InstrumentMapper instrumentMapper;
    private final DailyKlineCache klineCache;
    private final CryptoMarketProperties properties;

    public CryptoSyncService(List<CryptoDataSource> dataSources,
                             BarMapper barMapper,
                             InstrumentMapper instrumentMapper,
                             DailyKlineCache klineCache,
                             CryptoMarketProperties properties) {
        this.dataSources = dataSources;
        this.barMapper = barMapper;
        this.instrumentMapper = instrumentMapper;
        this.klineCache = klineCache;
        this.properties = properties;
    }

    /**
     * @param symbolRaw 完整符号，如 {@code BTC-USDT.CRYPTO}
     * @param interval  {@code 1d} / {@code 4h}
     * @param fromStr   可选，{@code yyyy-MM-dd} / {@code yyyyMMdd}，缺省 = 全历史起点
     * @param toStr     可选，同上，缺省 = 当前时刻
     * @return 落库的 K 线根数
     */
    public int sync(String symbolRaw, String interval, String fromStr, String toStr) {
        String symbol = CryptoSymbol.resolveCrypto(symbolRaw);
        if (!INTERVALS.contains(interval)) {
            throw new IllegalArgumentException("不支持的周期：" + interval + "；可选 1d/4h");
        }
        long fromTs = fromStr == null || fromStr.isBlank() ? 0 : parseDate(fromStr);
        long toTs = toStr == null || toStr.isBlank() ? Instant.now().getEpochSecond() : parseDate(toStr);
        if (fromTs >= toTs) {
            throw new IllegalArgumentException("起始时间不能晚于结束时间");
        }

        registerInstrument(symbol);

        List<CryptoCandle> candles = fetch(symbol, interval, fromTs, toTs);
        int count = 0;
        for (CryptoCandle candle : candles) {
            barMapper.upsert(toBar(symbol, interval, candle));
            count++;
        }
        if (count > 0) {
            klineCache.evict(symbol, interval);
        }
        log.info("加密行情同步完成: {} {} 区间 {}~{} 共 {} 根", symbol, interval, fromTs, toTs, count);
        return count;
    }

    /** 注册 instrument（幂等 upsert）。加密：CRYPTO / 24H / UTC / USDT 计价。 */
    private void registerInstrument(String symbol) {
        Instrument ins = new Instrument();
        ins.setCode(symbol);
        ins.setMarket("CRYPTO");
        ins.setName(CryptoSymbol.base(symbol) + "/" + CryptoSymbol.quote(symbol));
        ins.setCurrency(CryptoSymbol.quote(symbol));
        ins.setPriceScale(properties.getPriceScale());
        ins.setLotSize(properties.getLotSize());
        ins.setTickSize(properties.getTickSize());
        ins.setTz("UTC");
        ins.setSession("24H");
        ins.setStatus(1);
        instrumentMapper.upsert(ins);
    }

    private Bar toBar(String symbol, String interval, CryptoCandle c) {
        Bar bar = new Bar();
        bar.setInstrumentCode(symbol);
        bar.setPeriod(interval);
        bar.setTs(c.ts());
        bar.setOpenPrice(c.open());
        bar.setHighPrice(c.high());
        bar.setLowPrice(c.low());
        bar.setClosePrice(c.close());
        bar.setPreClose(null); // 加密无复权，pre_close 留空
        bar.setVolume(c.volume());
        bar.setAmount(c.amount());
        return bar;
    }

    private List<CryptoCandle> fetch(String symbol, String interval, long fromTs, long toTs) {
        CryptoDataSource primary = find(PRIMARY);
        if (primary != null && primary.isAvailable()) {
            try {
                List<CryptoCandle> data = primary.fetch(symbol, interval, fromTs, toTs);
                if (!data.isEmpty()) {
                    log.debug("[{}] 拉取 {} 根", primary.getName(), data.size());
                    return data;
                }
            } catch (Exception e) {
                log.warn("[{}] 拉取失败: {}", primary.getName(), e.getMessage());
            }
        }
        CryptoDataSource secondary = find(SECONDARY);
        if (secondary != null && secondary.isAvailable()) {
            try {
                List<CryptoCandle> data = secondary.fetch(symbol, interval, fromTs, toTs);
                if (!data.isEmpty()) {
                    log.info("[备用源-{}] 拉取 {} 根", secondary.getName(), data.size());
                    return data;
                }
            } catch (Exception e) {
                log.warn("[{}] 拉取失败: {}", secondary.getName(), e.getMessage());
            }
        }
        log.warn("加密行情源均无数据: {} {}", symbol, interval);
        return new ArrayList<>();
    }

    private CryptoDataSource find(String name) {
        return dataSources.stream()
                .filter(ds -> ds.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private static long parseDate(String raw) {
        String s = raw.trim().replace("-", "");
        if (s.length() != 8) {
            throw new IllegalArgumentException("日期格式应为 yyyy-MM-dd 或 yyyyMMdd: " + raw);
        }
        LocalDate date = LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
        return date.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }
}
