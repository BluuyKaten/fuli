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
import java.util.List;
import java.util.Map;

/**
 * 美股行情同步：解析代码 → 拉日线落 {@code bar} + 注册 {@code instrument}。
 *
 * <p>支持两个源，由 {@code us.market.source} 选择（默认 {@code tencent}）：
 * <ul>
 *   <li><b>tencent</b>：前复权日线，单次 1200 根、自动往回翻页补到 {@code history-years} 年；
 *       <b>不写 adj_factor</b>（价格本身已经是前复权）。</li>
 *   <li><b>eastmoney</b>：原始价 + fqt=0/fqt=1 求相对因子落 {@code adj_factor}（原实现）。</li>
 * </ul>
 *
 * <p>为什么默认腾讯：东财 K 线主机在部分网络直接断连（见 ADR-0019），而腾讯同一台机器直连可用。
 * 两个源口径不同，所以每次同步都是<b>先删后插</b>——只 upsert 会在换源时留下半段原始价、
 * 半段复权价的序列，图上表现为断层，且很难归因。
 *
 * <p>日线 {@code ts} = 美股交易日 09:30（美东）转 UTC 秒，时区用 {@code America/New_York}
 * 由 JDK 自动处理夏令时/冬令时。
 */
@Slf4j
@Service
public class UsSyncService {

    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final String PERIOD_DAILY = "1d";

    private final EastMoneyUsDataSource eastMoney;
    private final TencentUsDataSource tencent;
    private final BarMapper barMapper;
    private final InstrumentMapper instrumentMapper;
    private final AdjFactorMapper adjFactorMapper;
    private final DailyKlineCache klineCache;
    private final UsMarketProperties properties;

    public UsSyncService(EastMoneyUsDataSource eastMoney,
                         TencentUsDataSource tencent,
                         BarMapper barMapper,
                         InstrumentMapper instrumentMapper,
                         AdjFactorMapper adjFactorMapper,
                         DailyKlineCache klineCache,
                         UsMarketProperties properties) {
        this.eastMoney = eastMoney;
        this.tencent = tencent;
        this.barMapper = barMapper;
        this.instrumentMapper = instrumentMapper;
        this.adjFactorMapper = adjFactorMapper;
        this.klineCache = klineCache;
        this.properties = properties;
    }

    /** 本次同步实际使用的数据源名，供接口回显与排障。 */
    public String source() {
        return "eastmoney".equalsIgnoreCase(properties.getSource()) ? "eastmoney" : "tencent";
    }

    /**
     * @param symbolRaw 完整符号，如 {@code AAPL.US}
     * @return 落库的日线根数
     */
    public int sync(String symbolRaw) {
        String symbol = resolveUs(symbolRaw);
        String code = StockCodeUtil.pureCode(symbol);
        return "eastmoney".equals(source())
                ? syncFromEastMoney(symbol, code)
                : syncFromTencent(symbol, code);
    }

    // ---------------- 腾讯（默认） ----------------

    private int syncFromTencent(String symbol, String code) {
        UsMarket.UsStockMatch match = tencent.search(code);
        if (match == null) {
            throw new IllegalArgumentException("腾讯未找到美股：" + code);
        }
        LocalDate since = LocalDate.now().minusYears(Math.max(1, properties.getHistoryYears()));
        UsMarket.UsDailyResult raw = tencent.fetchDaily(match.quoteId(), since);
        if (raw.candles().isEmpty()) {
            throw new IllegalStateException("腾讯未返回 " + code + " 的日线（该标的是否已退市/停牌？）");
        }

        registerInstrument(symbol, match, raw.priceScale());
        // 先删后插：换源时不能把两种复权口径混在一张表里。
        int removedBars = barMapper.deleteBySymbol(symbol, PERIOD_DAILY);
        // 前复权价再叠旧因子会被二次缩放，因子必须一起清掉。
        adjFactorMapper.deleteBySymbol(symbol);

        int count = writeBars(symbol, raw.candles());
        evict(symbol);
        log.info("美股同步完成(tencent/前复权): {} ({}) {} 根，替换旧 {} 根",
                symbol, match.name(), count, removedBars);
        return count;
    }

    // ---------------- 东财（原始价 + 因子） ----------------

    private int syncFromEastMoney(String symbol, String code) {
        UsMarket.UsStockMatch match = eastMoney.search(code);
        if (match == null) {
            throw new IllegalArgumentException("东财未找到美股：" + code);
        }

        UsMarket.UsDailyResult raw = eastMoney.fetchDaily(match.quoteId(), false);
        registerInstrument(symbol, match, raw.priceScale());

        int removedBars = barMapper.deleteBySymbol(symbol, PERIOD_DAILY);
        int count = writeBars(symbol, raw.candles());

        // 复权因子：fqt=1 / fqt=0 求相对因子（消除拆股假阴线）
        try {
            adjFactorMapper.deleteBySymbol(symbol);
            UsMarket.UsDailyResult fwd = eastMoney.fetchDaily(match.quoteId(), true);
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

        evict(symbol);
        log.info("美股同步完成(eastmoney/原始价): {} ({}) {} 根，替换旧 {} 根",
                symbol, match.name(), count, removedBars);
        return count;
    }

    /** 逐根写库；{@code preClose} 用上一根的收盘（首根留空，不臆造）。 */
    private int writeBars(String symbol, List<UsMarket.UsCandle> candles) {
        int count = 0;
        BigDecimal previousClose = null;
        for (UsMarket.UsCandle candle : candles) {
            barMapper.upsert(toBar(symbol, candle, previousClose));
            previousClose = candle.close();
            count++;
        }
        return count;
    }

    private void evict(String symbol) {
        klineCache.evict(symbol, PERIOD_DAILY);
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

    private Bar toBar(String symbol, UsMarket.UsCandle candle, BigDecimal previousClose) {
        Bar bar = new Bar();
        bar.setInstrumentCode(symbol);
        bar.setPeriod(PERIOD_DAILY);
        bar.setTs(toUsTs(candle.date()));
        bar.setOpenPrice(candle.open());
        bar.setHighPrice(candle.high());
        bar.setLowPrice(candle.low());
        bar.setClosePrice(candle.close());
        bar.setPreClose(previousClose);
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
