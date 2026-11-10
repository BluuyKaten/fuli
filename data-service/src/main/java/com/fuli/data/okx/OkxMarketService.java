package com.fuli.data.okx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fuli.data.cache.DailyKlineCache;
import com.fuli.data.entity.Bar;
import com.fuli.data.entity.Instrument;
import com.fuli.data.mapper.BarMapper;
import com.fuli.data.mapper.InstrumentMapper;
import com.fuli.data.config.OkxConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OKX 行情接入：标的基础信息 + K 线。见 docs/adr/0008。
 *
 * <p>两个关键取舍：
 * <ul>
 *   <li><b>只落 confirm=1</b>：未完结那根的 OHLC 还会变，写进事实表就等于把瞬时快照当历史；</li>
 *   <li><b>不做倍数换算</b>：×100/×1000 是 Tushare 口径，加密的 vol 是基础币个数、
 *       volCcyQuote 是计价币成交额，原样落库。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OkxMarketService {

    /** 单页上限：candles 300、history-candles 100（见 ADR-0008 决策 6）。 */
    private static final int LATEST_LIMIT = 300;

    private static final int HISTORY_LIMIT = 100;

    /** history-candles 限频 20 次/2s，留余量。 */
    private static final long PAGE_INTERVAL_MS = 120L;

    /**
     * instrument.code / bar.instrument_code / adj_factor.instrument_code 都是 varchar(20)。
     * 实测最长 OKX SPOT instId 是 13 字符（BABYDOGE-USDT），加后缀 ".CRYPTO" 正好 20。
     * 零余量：以后出更长的 ID 若直接写会被截断（非严格模式）或直接报错，
     * 所以同步时显式跳过并告警，而不是让它悄悄坏掉。
     */
    private static final int MAX_SYMBOL_LENGTH = 20;

    private final OkxConfig config;

    private final OkxClient client;

    private final InstrumentMapper instrumentMapper;

    private final BarMapper barMapper;

    private final DailyKlineCache cache;

    /** 同步 SPOT 标的基础信息到 {@code instrument}（market=CRYPTO）。 */
    public int syncInstruments() {
        Map<String, String> q = new HashMap<>();
        q.put("instType", "SPOT");
        JsonNode data = client.publicGet("/api/v5/public/instruments", q);

        int count = 0;
        for (JsonNode row : data) {
            String quote = row.path("quoteCcy").asText("");
            if (!config.getQuoteCcy().equalsIgnoreCase(quote)) {
                continue;
            }
            String instId = row.path("instId").asText("");
            if (instId.isEmpty()) {
                continue;
            }
            String symbol = OkxSymbols.toSymbol(instId);
            if (symbol.length() > MAX_SYMBOL_LENGTH) {
                log.warn("[OKX] 跳过超长标的 {}（{} 字符 > 列宽 {}）",
                        symbol, symbol.length(), MAX_SYMBOL_LENGTH);
                continue;
            }
            Instrument inst = new Instrument();
            inst.setCode(symbol);
            inst.setMarket(OkxSymbols.MARKET);
            inst.setName(instId);
            inst.setCurrency(quote);
            inst.setPriceScale(scaleOf(row.path("tickSz").asText("0.01")));
            inst.setLotSize(decimal(row.path("lotSz").asText("")));
            inst.setTickSize(decimal(row.path("tickSz").asText("")));
            // 加密 24H 连续交易：时区固定 UTC，session 用 24H 而不是 DAY
            inst.setTz("UTC");
            inst.setSession("24H");
            inst.setStatus("live".equalsIgnoreCase(row.path("state").asText("")) ? 1 : 0);
            inst.setExchange("OKX");
            instrumentMapper.upsert(inst);
            count++;
        }
        log.info("[OKX] 标的基础信息同步完成：{} 个 {} 现货对", count, config.getQuoteCcy());
        return count;
    }

    /** 拉某标的最新一页 K 线（近 1440 根内），只写已完结的。 */
    public int syncLatest(String instId, String period) {
        String okxBar = OkxPeriods.toOkxBar(period);
        Map<String, String> q = new LinkedHashMap<>();
        q.put("instId", OkxSymbols.toInstId(instId));
        q.put("bar", okxBar);
        q.put("limit", String.valueOf(LATEST_LIMIT));
        JsonNode data = client.publicGet("/api/v5/market/candles", q);
        int n = persist(instId, period, data);
        cache.evictAll(List.of(OkxSymbols.toSymbol(instId)), period);
        return n;
    }

    /**
     * 往回翻页补历史。
     *
     * <p>分页语义容易写反：{@code after} 取**更早**，取本批最旧一根的 ts 减 1ms 再作为
     * {@code after}，减 1ms 是为了让边界那根不出现在下一批里（否则每批都会重复一根）。
     *
     * @param startTs  补到这个 ts（含）为止；传 0 表示能补多远补多远
     * @param maxPages 安全上限，防止一次把几年数据拉爆
     */
    public int backfill(String instId, String period, long startTs, int maxPages) {
        String okxBar = OkxPeriods.toOkxBar(period);
        String pureId = OkxSymbols.toInstId(instId);
        long cursor = 0;
        int total = 0;
        int pages = 0;

        while (pages < Math.max(1, maxPages)) {
            Map<String, String> q = new LinkedHashMap<>();
            q.put("instId", pureId);
            q.put("bar", okxBar);
            q.put("limit", String.valueOf(HISTORY_LIMIT));
            if (cursor > 0) {
                q.put("after", String.valueOf(cursor));
            }
            JsonNode data = client.publicGet("/api/v5/market/history-candles", q);
            if (data == null || data.isEmpty()) {
                break;
            }
            total += persist(instId, period, data);
            pages++;

            long oldestMs = oldestTsMs(data);
            if (oldestMs <= 0 || oldestMs / 1000 <= startTs) {
                break;
            }
            cursor = oldestMs - 1;
            sleep();
        }
        cache.evictAll(List.of(OkxSymbols.toSymbol(instId)), period);
        log.info("[OKX] {} {} 回补完成：{} 页 / {} 根已完结", pureId, period, pages, total);
        return total;
    }

    /** 按配置把「所有标的 × 所有周期」拉一遍最新页，供定时任务调用。 */
    public int syncConfigured() {
        int total = 0;
        for (String instId : config.getSymbols()) {
            for (String period : config.getBars()) {
                try {
                    total += syncLatest(instId, period);
                } catch (Exception e) {
                    log.warn("[OKX] {} {} 同步失败: {}", instId, period, e.getMessage());
                }
            }
        }
        return total;
    }

    /**
     * 把一页 K 线写入 {@code bar}，返回写入根数。
     *
     * <p>OKX 返回是时间倒序（最新在前），这里按原顺序写，主键冲突走 upsert，顺序不影响结果。
     */
    private int persist(String instId, String period, JsonNode data) {
        String symbol = OkxSymbols.toSymbol(instId);
        int written = 0;
        for (JsonNode row : data) {
            Bar bar = toBar(symbol, period, row);
            if (bar == null) {
                continue;
            }
            barMapper.upsert(bar);
            written++;
        }
        return written;
    }

    /**
     * 单根 K 线 → {@code Bar}；未完结的返回 null。
     *
     * <p>列顺序：0 开盘时间(ms) / 1 开 / 2 高 / 3 低 / 4 收 / 5 vol(基础币) /
     * 6 volCcy(计价币) / 7 volCcyQuote(计价币) / 8 confirm。
     */
    static Bar toBar(String symbol, String period, JsonNode row) {
        if (row == null || row.size() < 9) {
            return null;
        }
        if (!"1".equals(row.get(8).asText())) {
            return null;
        }
        Bar bar = new Bar();
        bar.setInstrumentCode(symbol);
        bar.setPeriod(period);
        // OKX 给毫秒，bar.ts 统一存 UTC 秒
        bar.setTs(row.get(0).asLong() / 1000);
        bar.setOpenPrice(decimal(row.get(1).asText()));
        bar.setHighPrice(decimal(row.get(2).asText()));
        bar.setLowPrice(decimal(row.get(3).asText()));
        bar.setClosePrice(decimal(row.get(4).asText()));
        // OKX 不提供 pre_close：它不是源数据，硬算出来的前收会在除权/跳空时误导指标
        bar.setPreClose(null);
        bar.setVolume(decimal(row.get(5).asText()));
        // 优先 volCcyQuote（现货即计价币成交额），空则退 volCcy
        String quoteVol = row.get(7).asText("");
        bar.setAmount(decimal(quoteVol.isEmpty() ? row.get(6).asText("") : quoteVol));
        return bar;
    }

    /** 本批里最旧一根的毫秒时间戳；空批返回 0。 */
    static long oldestTsMs(JsonNode data) {
        long oldest = 0;
        for (JsonNode row : data) {
            long ts = row.get(0).asLong();
            if (oldest == 0 || ts < oldest) {
                oldest = ts;
            }
        }
        return oldest;
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** tickSz 的小数位数即价格精度：0.1 → 1，0.00000001 → 8。 */
    static int scaleOf(String tickSz) {
        BigDecimal d = decimal(tickSz);
        if (d == null || d.signum() == 0) {
            return 2;
        }
        return Math.max(0, d.stripTrailingZeros().scale());
    }

    private static void sleep() {
        try {
            Thread.sleep(PAGE_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 供 controller 展示当前配置的标的与周期。 */
    public List<String> configuredSymbols() {
        List<String> list = new ArrayList<>();
        for (String instId : config.getSymbols()) {
            list.add(OkxSymbols.toSymbol(instId));
        }
        return list;
    }
}
