package com.fuli.trade.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.common.api.util.StockCodeUtil;
import com.fuli.trade.dto.KlineBarDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 东方财富数据源（备用）。
 *
 * <p>接口文档：https://quote.eastmoney.com/
 *
 * <h3>周期参数（klt）</h3>
 * 实测校准过，别再凭印象写：{@code 101=日、102=周、103=月}。
 * 早期实现把周线写成 {@code 101}、月线写成 {@code 102}，整体错一档——
 * 用户点「周线」看到的是日线、点「月线」看到的是周线。这种错很难被肉眼发现
 * （都有数据、形态也像），所以用 {@link #KLT_WEEKLY} 这类常量 + 单测钉住。
 *
 * <h3>美股 secid 前缀</h3>
 * {@code 105=NASDAQ、106=NYSE、107=AMEX}，且<b>必须与真实交易所一致</b>：
 * 拿 {@code 106.AAPL}（NYSE）去查 NASDAQ 的苹果，东财返回的不是报错，是
 * {@code data:null}——看起来像「没这只票」，最容易误判成数据源故障。
 * 所以不能按代码字面猜，要用 suggest 接口解析出真实 {@code QuoteID}。
 *
 * <h3>时间口径</h3>
 * 东财 kline 的时间<b>统一是北京时间</b>，不分市场：实测美股分钟线最后一根是
 * {@code 05:00}，对应美东 16:00 收盘（冬令时 UTC-5）。所以这里用
 * {@code Asia/Shanghai} 解析是正确口径，<b>不要</b>按标的时区改成 America/New_York——
 * 那会把美股时间整体推后 13 小时。
 */
@Slf4j
@Component
public class EastMoneyDataSource implements StockDataSource {

    /**
     * 单次请求超时。
     *
     * <p>实测上游响应在 249–1013 ms 之间抖动（3 倍），3 秒足以覆盖正常波动，
     * 又不至于让用户对着空白图干等。只设 connectTimeout 是不够的——
     * 连接能建上但服务端不返回时，请求会一直挂到 10 秒的连接超时上限。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);

    /** 东财 klt 参数：日线。 */
    static final int KLT_DAILY = 101;

    /** 东财 klt 参数：周线（102，不是 101）。 */
    static final int KLT_WEEKLY = 102;

    /** 东财 klt 参数：月线（103，不是 102）。 */
    static final int KLT_MONTHLY = 103;

    /**
     * K 线条数上限。
     *
     * <p>分钟线 5000 根：美股一天 390 根，约 13 个交易日；A 股一天 240 根，约 20 天。
     * 再长用户也不会在分钟图上翻，且响应体会大到影响首屏。
     */
    private static final int KLINE_LIMIT = 5000;

    private static final String KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
            + "?secid=%s&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
            + "&klt=%d&fqt=1&end=20500101&lmt=%d";

    /** 东财 suggest 接口的公开 token（网页端固定值，非敏感）。 */
    private static final String SUGGEST_TOKEN = "D43BF722C8E33BDC906FB84D85E326E8";

    private static final String SUGGEST_URL = "https://searchapi.eastmoney.com/api/suggest/get"
            + "?input=%s&type=14&token=" + SUGGEST_TOKEN + "&count=10";

    /** 美股 secid 缓存：交易所归属基本不变，24 小时足够，避免每根 K 线都打一次搜索。 */
    private static final long US_SECID_TTL_MS = 24L * 60 * 60 * 1000;

    private static final int US_SECID_CACHE_MAX = 500;

    private record CachedSecid(String secid, long expireAt) {}

    private final Map<String, CachedSecid> usSecidCache = new ConcurrentHashMap<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${datasource.eastmoney.enabled:true}")
    private boolean enabled;

    /**
     * 东财 {@code secid}：{@code 600519.SH → 1.600519}、{@code AAPL.US → 105.AAPL}。
     *
     * <p>这是<b>适配器内部协议</b>，不是标的身份规则：市场判断统一走
     * {@link StockCodeUtil#marketOf}（裸 6 位按 A 股推断），这里只负责把市场映射成东财要的形式。
     *
     * <p>⚠️ 东财对北交所目前用 {@code 0} 前缀（与深市同号），这是既有实现，<b>不要擅自改成 1</b>。
     *
     * @throws IllegalArgumentException 市场不受支持，或美股代码搜不到
     */
    String secidOf(String symbol) {
        String market = StockCodeUtil.marketOf(symbol);
        String code = StockCodeUtil.pureCode(symbol);
        return switch (market) {
            case "SH" -> "1." + code;
            case "SZ", "BJ" -> "0." + code;
            case "US" -> resolveUsSecid(code);
            default -> throw new IllegalArgumentException("东财暂不支持的市场: " + market);
        };
    }

    /**
     * 美股 secid 解析：调 suggest 接口拿真实 {@code QuoteID}（{@code 105.AAPL}）。
     *
     * <p>为什么不用 {@code instrument.exchange}：trade-service 访问不到 data_db，
     * 且 K 线是从东财实时拉的、不依赖 bar 表——没同步过的标的也应该能看图，
     * 用 instrument 反而会把「没同步」变成「看不了」。
     */
    private String resolveUsSecid(String code) {
        String key = code.toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CachedSecid cached = usSecidCache.get(key);
        if (cached != null && cached.expireAt() > now) {
            return cached.secid();
        }

        String secid = searchUsSecid(key);
        if (secid == null) {
            throw new IllegalArgumentException("东财搜不到美股标的：" + code + "（请确认代码，如 AAPL.US）");
        }
        if (usSecidCache.size() >= US_SECID_CACHE_MAX) {
            usSecidCache.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
        }
        usSecidCache.put(key, new CachedSecid(secid, now + US_SECID_TTL_MS));
        log.info("[EastMoney] 美股 secid 解析 {} → {}", code, secid);
        return secid;
    }

    private String searchUsSecid(String code) {
        String url = String.format(SUGGEST_URL, URLEncoder.encode(code, StandardCharsets.UTF_8));
        try {
            JsonNode root = objectMapper.readTree(get(url));
            return pickUsQuoteId(root.path("QuotationCodeTable").path("Data"), code);
        } catch (Exception e) {
            log.warn("[EastMoney] 美股 secid 搜索失败 {}: {}", code, e.getMessage());
            return null;
        }
    }

    /**
     * 从 suggest 结果里挑出精确匹配的美股 {@code QuoteID}（{@code 105.AAPL}）。
     *
     * <p>两个过滤条件都不能省：
     * <ul>
     *   <li>{@code Classify} 必须是 {@code UsStock}——同一个关键词会同时命中港股、基金、债券；</li>
     *   <li>{@code Code} 必须<b>精确</b>匹配（忽略大小写）——搜 {@code AAPL} 会带回
     *       {@code AAPL.WS}、{@code AAPLL} 之类的近似项，取第一条就会拿到错的交易所前缀。</li>
     * </ul>
     *
     * @return 形如 {@code 106.BABA}；无匹配返回 {@code null}
     */
    static String pickUsQuoteId(JsonNode items, String code) {
        if (items == null || !items.isArray()) {
            return null;
        }
        for (JsonNode item : items) {
            if (!"UsStock".equals(item.path("Classify").asText())) {
                continue;
            }
            if (!code.equalsIgnoreCase(item.path("Code").asText())) {
                continue;
            }
            String quoteId = item.path("QuoteID").asText("");
            if (quoteId.contains(".")) {
                return quoteId;
            }
        }
        return null;
    }

    @Override
    public List<KlineBarDTO> getMinuteData(String symbol, int period) {
        // period 直接就是 klt：1/5/15/30/60 与东财分钟参数一一对应
        return fetchKlines(symbol, period, "分钟线 " + period + "分", KLINE_LIMIT);
    }

    @Override
    public List<KlineBarDTO> getWeeklyData(String symbol) {
        return fetchKlines(symbol, KLT_WEEKLY, "周线", KLINE_LIMIT);
    }

    @Override
    public List<KlineBarDTO> getMonthlyData(String symbol) {
        return fetchKlines(symbol, KLT_MONTHLY, "月线", KLINE_LIMIT);
    }

    /** 构造 K 线请求 URL（包级可见，便于单测钉住 klt 映射）。 */
    String klineUrl(String secid, int klt, int limit) {
        return String.format(KLINE_URL, secid, klt, limit);
    }

    private List<KlineBarDTO> fetchKlines(String symbol, int klt, String label, int limit) {
        String secid;
        try {
            secid = secidOf(symbol);
        } catch (IllegalArgumentException e) {
            // 符号侧问题（搜不到/市场不支持）不是上游故障，如实记 WARN 就够，不必 ERROR 刷屏
            log.warn("[EastMoney] {} {} 无法解析 secid: {}", symbol, label, e.getMessage());
            return new ArrayList<>();
        }

        try {
            JsonNode root = objectMapper.readTree(get(klineUrl(secid, klt, limit)));
            JsonNode klines = root.path("data").path("klines");

            List<KlineBarDTO> result = new ArrayList<>();
            if (klines.isArray()) {
                for (JsonNode line : klines) {
                    // 字段序：日期,开盘,收盘,最高,最低,成交量,成交额,振幅,涨跌幅,涨跌额,换手率
                    String[] parts = line.asText().split(",");
                    if (parts.length < 6) {
                        continue;
                    }
                    KlineBarDTO bar = new KlineBarDTO();
                    bar.setTime(parseToTimestamp(parts[0]));
                    bar.setOpen(new BigDecimal(parts[1]));
                    bar.setClose(new BigDecimal(parts[2]));
                    bar.setHigh(new BigDecimal(parts[3]));
                    bar.setLow(new BigDecimal(parts[4]));
                    bar.setVolume(Long.parseLong(parts[5]));
                    result.add(bar);
                }
            }
            log.info("[EastMoney] 获取 {} {} 数据 {} 条", symbol, label, result.size());
            return result;
        } catch (Exception e) {
            log.error("[EastMoney] 获取{}失败 {}: {}", label, symbol, e.getMessage());
            return new ArrayList<>();
        }
    }

    private String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /**
     * 东财时间 → epoch 秒。
     *
     * <p>入参可能是 {@code 2024-01-02}（日/周/月线）或 {@code 2024-01-02 10:30}（分钟线）。
     * 按 {@code Asia/Shanghai} 解析是刻意的：东财对美股也返回北京时间（见类注释）。
     */
    private long parseToTimestamp(String datetime) {
        try {
            java.time.LocalDateTime dt;
            if (datetime.contains(":")) {
                dt = java.time.LocalDateTime.parse(datetime.replace(" ", "T"));
            } else {
                dt = java.time.LocalDate.parse(datetime).atStartOfDay();
            }
            return dt.atZone(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond();
        } catch (Exception e) {
            // 解析不出来时返回 0 而不是"现在"，否则脏数据会被当成最新一根混进图里
            log.warn("[EastMoney] 无法解析时间: {}", datetime);
            return 0L;
        }
    }

    @Override
    public String getName() {
        return "EastMoney";
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }
}
