package com.fuli.data.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 东方财富美股数据源（阶段 1）。
 *
 * <p>两个接口：
 * <ul>
 *   <li>搜索 {@code searchapi.eastmoney.com/api/suggest/get?input=AAPL&type=14}：
 *       返回 {@code JYS}（NASDAQ/NYSE/AMEX）、{@code MktNum}（105/106/107）、
 *       {@code QuoteID}（如 {@code 105.AAPL}）、{@code Name}。</li>
 *   <li>日线 {@code push2his.eastmoney.com/api/qt/stock/kline/get?secid=105.AAPL&klt=101&fqt=0}：
 *       返回 {@code data.decimal}（价格精度）与 {@code data.klines}，
 *       每行字段序 {@code 日期,开盘,收盘,最高,最低,成交量,成交额,振幅,涨跌幅,涨跌额,换手率}。</li>
 * </ul>
 */
@Slf4j
@Component
public class EastMoneyUsDataSource {

    /** 东财 suggest 接口公开 token（网页端固定值，非敏感）。 */
    private static final String SUGGEST_TOKEN = "D43BF722C8E33BDC906FB84D85E326E8";

    private static final String SEARCH_URL = "https://searchapi.eastmoney.com/api/suggest/get?input=%s&type=14&token="
            + SUGGEST_TOKEN + "&count=10";

    private static final String DAILY_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get"
            + "?secid=%s&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61"
            + "&klt=101&fqt=%d&end=20500101&lmt=10000";

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final HttpGet http;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EastMoneyUsDataSource(HttpGet http) {
        this.http = http;
    }

    /** 搜索美股，返回按 code 精确匹配的条目（无匹配返回 null）。 */
    public UsMarket.UsStockMatch search(String code) {
        try {
            String url = String.format(SEARCH_URL, URLEncoder.encode(code, StandardCharsets.UTF_8));
            JsonNode root = objectMapper.readTree(new String(http.get(url), StandardCharsets.UTF_8));
            JsonNode items = root.path("QuotationCodeTable").path("Data");
            if (!items.isArray()) {
                return null;
            }
            for (JsonNode item : items) {
                if (!"UsStock".equals(item.path("Classify").asText())) {
                    continue;
                }
                if (!code.equalsIgnoreCase(item.path("Code").asText())) {
                    continue;
                }
                return new UsMarket.UsStockMatch(
                        item.path("Code").asText(),
                        item.path("Name").asText(),
                        item.path("JYS").asText(),
                        item.path("QuoteID").asText());
            }
            return null;
        } catch (IOException e) {
            throw new RuntimeException("东财美股搜索失败: " + code, e);
        }
    }

    /**
     * 拉取日线。
     *
     * @param quoteId       完整 secid，如 {@code 105.AAPL}
     * @param forwardAdjust true = 前复权（fqt=1），false = 不复权（fqt=0）
     */
    public UsMarket.UsDailyResult fetchDaily(String quoteId, boolean forwardAdjust) {
        String url = String.format(DAILY_URL, quoteId, forwardAdjust ? 1 : 0);
        try {
            JsonNode root = objectMapper.readTree(new String(http.get(url), StandardCharsets.UTF_8));
            JsonNode data = root.path("data");
            int priceScale = data.path("decimal").asInt(0);
            List<UsMarket.UsCandle> candles = parseKlines(data.path("klines"));
            return new UsMarket.UsDailyResult(priceScale, candles);
        } catch (IOException e) {
            throw new RuntimeException("东财美股日线拉取失败: " + quoteId, e);
        }
    }

    /** 解析 klines 数组，字段序 {@code 日期,开盘,收盘,最高,最低,成交量,成交额,...}。 */
    static List<UsMarket.UsCandle> parseKlines(JsonNode klines) {
        List<UsMarket.UsCandle> result = new ArrayList<>();
        if (klines.isArray()) {
            for (JsonNode line : klines) {
                String[] p = line.asText().split(",");
                if (p.length < 6) {
                    continue;
                }
                try {
                    LocalDate date = LocalDate.parse(p[0], DATE);
                    result.add(new UsMarket.UsCandle(
                            date,
                            new BigDecimal(p[1]), // open
                            new BigDecimal(p[3]), // high
                            new BigDecimal(p[4]), // low
                            new BigDecimal(p[2]), // close
                            new BigDecimal(p[5]), // volume
                            new BigDecimal(p[6]))); // amount
                } catch (Exception e) {
                    log.debug("跳过非法 K 线行: {}", line.asText());
                }
            }
        }
        return result;
    }
}
