package com.fuli.data.service;

import com.fuli.common.api.util.StockCodeUtil;
import com.fuli.data.cache.DailyKlineCache;
import com.fuli.data.entity.AdjFactor;
import com.fuli.data.entity.Bar;
import com.fuli.data.entity.Instrument;
import com.fuli.data.mapper.AdjFactorMapper;
import com.fuli.data.mapper.InstrumentMapper;
import com.fuli.data.repository.KlineQueryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code GET /market/bars} 的实现：多市场统一的 K 线读取契约。
 *
 * <p>与旧 Feign 端点的区别有两点：<b>带 meta</b>（价格精度/币种/交易单位，前端不靠猜）、
 * <b>时间不是 epoch</b>（日线及以上给 {@code yyyy-MM-dd}，分钟类才给 UTC 秒）。
 * 见 ADR-0007 与 docs/multi-market-stage0.md §11。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketBarsService {

    /** 受控周期集合，与 {@code bar.chk_bar_period} 一致。 */
    public static final Set<String> PERIODS =
            Set.of("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w", "1mo");

    private static final Set<String> ADJUSTS = Set.of("none", "forward");

    /** 不复权时不改写价格，forward 时按 factor / factor_latest 缩放，保留 8 位。 */
    private static final MathContext PRICE_CONTEXT = new MathContext(20, RoundingMode.HALF_UP);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final KlineQueryRepository repository;

    private final DailyKlineCache cache;

    private final InstrumentMapper instrumentMapper;

    private final AdjFactorMapper adjFactorMapper;

    /**
     * @throws IllegalArgumentException 符号/周期/adjust/日期非法（由 controller 转 400）
     */
    public Map<String, Object> query(String symbolRaw, String period, String from, String to,
                                     Integer limit, String adjustRaw) {
        String symbol = StockCodeUtil.resolve(symbolRaw);
        String p = period == null ? "" : period.trim();
        if (!PERIODS.contains(p)) {
            throw new IllegalArgumentException("不支持的周期：" + period
                    + "；可选 " + String.join("/", PERIODS.stream().sorted().toList()));
        }
        String adjust = (adjustRaw == null || adjustRaw.isBlank()) ? "none" : adjustRaw.trim();
        if (!ADJUSTS.contains(adjust)) {
            throw new IllegalArgumentException("不支持的复权方式：" + adjustRaw + "；可选 none/forward");
        }

        Instrument instrument = instrumentMapper.selectById(symbol);
        if (instrument == null) {
            return null; // controller 转 404
        }

        // 日期 → ts 必须按**该标的的时区**换算，不能写死 Asia/Shanghai：
        // A 股是 Asia/Shanghai，加密是 UTC（24H 交易，日线开在 00:00 UTC）。
        // 写死的话加密标的的 from/to 会整体挪 8 小时，窗口首尾各错一根。
        ZoneId zone = zoneOf(instrument.getTz());
        Long fromTs = dayStart(from, zone);
        Long toTs = dayEndExclusive(to, zone);
        if (fromTs != null && toTs != null && fromTs >= toTs) {
            throw new IllegalArgumentException("起始日期不能晚于结束日期");
        }

        List<Bar> bars = load(symbol, p, fromTs, toTs, limit);
        if ("forward".equals(adjust)) {
            bars = applyForward(symbol, bars);
        }
        return render(symbol, p, adjust, instrument, bars);
    }

    private List<Bar> load(String symbol, String period, Long fromTs, Long toTs, Integer limit) {
        if (fromTs == null && toTs == null) {
            int want = (limit == null || limit <= 0) ? KlineQueryService.DEFAULT_LIMIT
                    : Math.min(limit, KlineQueryService.MAX_LIMIT);
            return cache.getRecent(symbol, period, "none", want, () -> repository.selectRecent(
                    symbol, period, Math.max(want, DailyKlineCache.CACHE_SIZE)));
        }
        Integer capped = (limit == null || limit <= 0) ? null
                : Math.min(limit, KlineQueryService.MAX_LIMIT);
        return repository.selectRange(symbol, period, fromTs, toTs, capped);
    }

    /**
     * 前复权：{@code price × factor / factor_latest}。
     *
     * <p>只改 OHLC，不改成交量/成交额（复权是价格口径，不是量口径）。
     * 无因子数据（加密、或尚未回填的美股）时原样返回，即等价 {@code none}。
     */
    private List<Bar> applyForward(String symbol, List<Bar> bars) {
        if (bars.isEmpty()) {
            return bars;
        }
        Long firstTs = bars.get(0).getTs();
        Long lastTs = bars.get(bars.size() - 1).getTs();
        // selectRange 的 toTs 是**开区间**，而这里要的正是末端那根 bar 的因子
        // （它就是 factor_latest 的分母），所以上界必须 +1 秒把它包进来。
        // 踩过的坑：传 lastTs 会把末端 bar 排除，导致「窗口止于除权日」时
        // 分母退化为前一根的因子，除权日之前的 bar 全部不缩放。
        List<AdjFactor> factors = adjFactorMapper.selectRange(symbol, firstTs, lastTs + 1);
        if (factors.isEmpty()) {
            return bars;
        }
        Map<Long, BigDecimal> byTs = new HashMap<>(factors.size() * 2);
        for (AdjFactor f : factors) {
            byTs.put(f.getTs(), f.getFactor());
        }
        // 分母取窗口末端那根 bar 的因子；末端缺因子时退回整段的最后一个
        BigDecimal latest = byTs.get(lastTs);
        if (latest == null) {
            latest = factors.get(factors.size() - 1).getFactor();
        }
        if (latest == null || latest.signum() == 0) {
            return bars;
        }

        List<Bar> adjusted = new ArrayList<>(bars.size());
        for (Bar bar : bars) {
            BigDecimal factor = byTs.get(bar.getTs());
            if (factor == null) {
                adjusted.add(bar);
                continue;
            }
            Bar copy = new Bar();
            copy.setInstrumentCode(bar.getInstrumentCode());
            copy.setPeriod(bar.getPeriod());
            copy.setTs(bar.getTs());
            copy.setOpenPrice(scale(bar.getOpenPrice(), factor, latest));
            copy.setHighPrice(scale(bar.getHighPrice(), factor, latest));
            copy.setLowPrice(scale(bar.getLowPrice(), factor, latest));
            copy.setClosePrice(scale(bar.getClosePrice(), factor, latest));
            copy.setPreClose(scale(bar.getPreClose(), factor, latest));
            copy.setVolume(bar.getVolume());
            copy.setAmount(bar.getAmount());
            adjusted.add(copy);
        }
        return adjusted;
    }

    private static BigDecimal scale(BigDecimal price, BigDecimal factor, BigDecimal latest) {
        if (price == null) {
            return null;
        }
        return price.multiply(factor).divide(latest, PRICE_CONTEXT).setScale(8, RoundingMode.HALF_UP);
    }

    private Map<String, Object> render(String symbol, String period, String adjust,
                                       Instrument instrument, List<Bar> bars) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("name", instrument.getName());
        meta.put("market", instrument.getMarket());
        meta.put("currency", instrument.getCurrency());
        meta.put("priceScale", instrument.getPriceScale());
        meta.put("lotSize", instrument.getLotSize());
        meta.put("tickSize", instrument.getTickSize());
        meta.put("tz", instrument.getTz());

        List<Map<String, Object>> items = new ArrayList<>(bars.size());
        for (Bar bar : bars) {
            Map<String, Object> item = new HashMap<>(8);
            item.put("time", renderTime(bar.getTs(), instrument.getTz(), period));
            item.put("open", bar.getOpenPrice());
            item.put("high", bar.getHighPrice());
            item.put("low", bar.getLowPrice());
            item.put("close", bar.getClosePrice());
            item.put("volume", bar.getVolume());
            item.put("amount", bar.getAmount());
            items.add(item);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("symbol", symbol);
        data.put("period", period);
        data.put("adjust", adjust);
        data.put("meta", meta);
        data.put("bars", items);
        return data;
    }

    /**
     * 日线及以上给 {@code yyyy-MM-dd}（lightweight-charts 原生 business day），
     * 分钟类给 UTC 秒。epoch 不进日线载荷——前端拿到 1756863000 还要自己减时区，
     * 一日线一夏令时就错位。
     */
    private static Object renderTime(Long ts, String tz, String period) {
        if (ts == null) {
            return null;
        }
        if (KlineQueryService.isDailyOrAbove(period)) {
            ZoneId zone = zoneOf(tz);
            return LocalDate.ofInstant(Instant.ofEpochSecond(ts), zone).format(DATE);
        }
        return ts;
    }

    private static ZoneId zoneOf(String tz) {
        try {
            return tz == null || tz.isBlank() ? ZoneId.of("Asia/Shanghai") : ZoneId.of(tz);
        } catch (Exception e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    /** {@code yyyyMMdd} / {@code yyyy-MM-dd} → 该时区当日 00:00 的 epoch 秒。 */
    private static Long dayStart(String raw, ZoneId zone) {
        LocalDate date = parseDate(raw);
        return date == null ? null : date.atStartOfDay(zone).toEpochSecond();
    }

    /** 结束日 → 次日 00:00 的 epoch 秒（开区间上界，闭区间语义由调用方约定）。 */
    private static Long dayEndExclusive(String raw, ZoneId zone) {
        LocalDate date = parseDate(raw);
        return date == null ? null : date.plusDays(1).atStartOfDay(zone).toEpochSecond();
    }

    private static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().replace("-", "");
        if (s.length() != 8 || !s.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("日期格式应为 yyyyMMdd 或 yyyy-MM-dd: " + raw);
        }
        try {
            return LocalDate.parse(s, DateTimeFormatter.BASIC_ISO_DATE);
        } catch (java.time.DateTimeException e) {
            // DateTimeParseException 不是 IllegalArgumentException，不转一下会漏成 500
            throw new IllegalArgumentException("非法日期：" + raw);
        }
    }
}
