package com.fuli.data.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 腾讯财经美股数据源。
 *
 * <p>为什么要多这一个源：东方财富的 K 线主机（{@code push2his.eastmoney.com}）在部分网络下
 * 直接 TLS 断连（实测本机 `schannel: server closed abruptly`，Java HttpClient 同样 EOF），
 * 而它的搜索接口 {@code searchapi.eastmoney.com} 又是通的——表现为「搜得到、拉不到日线」。
 * 腾讯这条链路在同一台机器上直连可用，作为美股的主源。
 *
 * <p>两个接口：
 * <ul>
 *   <li>代码解析 {@code https://qt.gtimg.cn/q=usAAPL}：返回 GBK 文本
 *       {@code v_usAAPL="200~苹果~AAPL.OQ~353.00~..."}，第 3 段是「代码.交易所」
 *       （{@code .OQ}=NASDAQ、{@code .N}=NYSE、{@code .AM}=AMEX），K 线请求必须带这个后缀。</li>
 *   <li>K 线 {@code https://web.ifzq.gtimg.cn/appstock/app/usfqkline/get?param=代码,周期,起,止,条数,qfq}：
 *       单次最多 1200 根（传 2000 返回空数组，不是报错），行格式
 *       {@code [日期, 开, 收, 高, 低, 成交量]}。</li>
 * </ul>
 *
 * <p><b>只有前复权（qfq）</b>：实测传 {@code bfq} 或留空都返回空数组，腾讯不提供美股不复权日线。
 * 所以本源的 bar 存的是「同步时点的前复权价」，不产出 {@code adj_factor}
 * （口径见 docs/adr/0019-us-market-data-source.md）。
 */
@Slf4j
@Component
public class TencentUsDataSource {

    private static final String QUOTE_URL = "https://qt.gtimg.cn/q=us%s";
    private static final String KLINE_URL =
            "https://web.ifzq.gtimg.cn/appstock/app/usfqkline/get?param=%s,%s,%s,%s,%d,qfq";

    /** 单次上限：实测 1200 正常、2000 返回空。留 1200 不去赌边界。 */
    static final int PAGE_SIZE = 1200;

    /** 往回翻页的安全上限（6 页 ≈ 25 年），防止源异常时无限循环。 */
    private static final int MAX_PAGES = 6;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Charset GBK = Charset.forName("GBK");

    private final HttpGet http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TencentUsDataSource(HttpGet http) {
        this.http = http;
    }

    /**
     * 解析代码，返回 K 线要用的 {@code 代码.交易所}（如 {@code AAPL.OQ}）。
     *
     * @return 找不到返回 {@code null}
     */
    public UsMarket.UsStockMatch search(String code) {
        String url = String.format(QUOTE_URL, code.toUpperCase());
        try {
            String body = new String(http.get(url), GBK);
            return parseQuote(code, body);
        } catch (IOException e) {
            throw new RuntimeException("腾讯美股代码解析失败: " + code, e);
        }
    }

    /** 纯解析，便于单测钉住字段位次（GBK 文本，字段用 {@code ~} 分隔）。 */
    static UsMarket.UsStockMatch parseQuote(String code, String body) {
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) {
            return null;
        }
        String[] f = body.substring(start + 1, end).split("~", -1);
        if (f.length < 3 || !"200".equals(f[0].trim())) {
            return null;
        }
        String tencentCode = f[2].trim();
        if (!tencentCode.startsWith(code.toUpperCase() + ".")) {
            return null;
        }
        String exchange = exchangeName(tencentCode.substring(tencentCode.indexOf('.') + 1));
        String name = f.length > 1 && !f[1].isBlank() ? f[1].trim() : code.toUpperCase();
        return new UsMarket.UsStockMatch(code.toUpperCase(), name, exchange, tencentCode);
    }

    static String exchangeName(String suffix) {
        return switch (suffix.toUpperCase()) {
            case "OQ" -> "NASDAQ";
            case "N" -> "NYSE";
            case "AM" -> "AMEX";
            default -> suffix.toUpperCase();
        };
    }

    /**
     * 拉日线（前复权），从 {@code since} 到最近交易日，自动往回翻页。
     *
     * <p>翻页是必须的：单次 1200 根只有约 4.7 年，10 年历史要 3 页。翻页用「上一页最早日期 - 1 天」
     * 作为下一页的 {@code end}，因为接口在给定区间里返回的是**最后** N 根。
     */
    public UsMarket.UsDailyResult fetchDaily(String tencentCode, LocalDate since) {
        Map<LocalDate, UsMarket.UsCandle> merged = new LinkedHashMap<>();
        LocalDate end = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            List<UsMarket.UsCandle> rows = fetchPage(tencentCode, end, PAGE_SIZE);
            if (rows.isEmpty()) {
                break;
            }
            for (UsMarket.UsCandle c : rows) {
                merged.putIfAbsent(c.date(), c);
            }
            LocalDate earliest = rows.get(0).date();
            if (!earliest.isAfter(since)) {
                break;
            }
            end = earliest.minusDays(1);
        }
        List<UsMarket.UsCandle> candles = new ArrayList<>(merged.values());
        candles.sort(Comparator.comparing(UsMarket.UsCandle::date));
        return new UsMarket.UsDailyResult(priceScale(candles), candles);
    }

    /** 周线/月线（K 线图备用源用；同步只落日线）。 */
    public List<UsMarket.UsCandle> fetchCandles(String tencentCode, String period, int count) {
        String url = String.format(KLINE_URL, klineCode(tencentCode), period, "", "", Math.min(count, PAGE_SIZE));
        return parseCandles(period, readJson(url));
    }

    /** {@code end} 为空表示「取到最新一根」；翻页时传上一页最早日期的前一天。 */
    private List<UsMarket.UsCandle> fetchPage(String tencentCode, LocalDate end, int count) {
        String url = String.format(KLINE_URL, klineCode(tencentCode), "day", "",
                end == null ? "" : end.format(DATE), count);
        return parseCandles("day", readJson(url));
    }

    /**
     * K 线的 {@code param} 必须带 {@code us} 前缀（{@code usAAPL.OQ}），
     * 而行情接口返回的第 3 段是 {@code AAPL.OQ}——少了这个前缀，接口回空数组且**不报错**。
     */
    static String klineCode(String tencentCode) {
        return tencentCode.startsWith("us") ? tencentCode : "us" + tencentCode;
    }

    private JsonNode readJson(String url) {
        try {
            return objectMapper.readTree(new String(http.get(url), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RuntimeException("腾讯美股 K 线请求失败: " + stripQuery(url), e);
        }
    }

    /** 解析 {@code data.{code}.qfqday/qfqweek/qfqmonth}；无数据返回空列表。 */
    static List<UsMarket.UsCandle> parseCandles(String period, JsonNode root) {
        if (root == null) {
            return List.of();
        }
        JsonNode data = root.path("data");
        if (!data.isObject() || data.size() == 0) {
            return List.of();
        }
        JsonNode rows = data.elements().next().path("qfq" + period);
        List<UsMarket.UsCandle> result = new ArrayList<>();
        if (!rows.isArray()) {
            return result;
        }
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 6) {
                continue;
            }
            try {
                result.add(new UsMarket.UsCandle(
                        LocalDate.parse(row.get(0).asText(), DATE),
                        new BigDecimal(row.get(1).asText()),   // open
                        new BigDecimal(row.get(3).asText()),   // high
                        new BigDecimal(row.get(4).asText()),   // low
                        new BigDecimal(row.get(2).asText()),   // close（腾讯是「开收高低」）
                        new BigDecimal(row.get(5).asText()),   // volume
                        null));                                // 腾讯不给成交额
            } catch (RuntimeException e) {
                log.debug("跳过非法美股 K 线行: {}", row);
            }
        }
        return result;
    }

    /** 价格精度：按收盘价实际小数位推断（AAPL 两位、低价股/ETF 可能三位），上限 4 位。 */
    static int priceScale(List<UsMarket.UsCandle> candles) {
        int scale = 2;
        for (UsMarket.UsCandle c : candles) {
            if (c.close() != null) {
                scale = Math.max(scale, Math.min(4, c.close().stripTrailingZeros().scale()));
            }
        }
        return scale;
    }

    private static String stripQuery(String url) {
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
