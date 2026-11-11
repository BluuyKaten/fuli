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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 腾讯财经美股 K 线（周/月线）。
 *
 * <p>存在理由：东财 K 线主机在部分网络直接断连（ADR-0019），而看图走的就是「现拉」这条路，
 * 断了就是一张空图。腾讯这条链路可用，作为美股备用源。
 *
 * <p>只做<b>美股 + 周/月线</b>：
 * <ul>
 *   <li>日线不走这里——日线由 {@code /stock/daily} 读 data-service 的 {@code bar} 表，同步过就有；</li>
 *   <li>分钟线不走这里——腾讯不提供美股历史分钟线（{@code mkline} 对美股返回 param error，
 *       {@code usMinute} 只有当日分时），硬接会给出「只有今天」的假分钟图。</li>
 *   <li>A 股不走这里——东财图表用 fqt=1（前复权），腾讯 A 股复权口径与除权处理未在此验证，
 *       贸然接管会让老用户的图静默变样。</li>
 * </ul>
 *
 * <p>价格口径与东财图保持一致：前复权；时间轴取「交易日 00:00（Asia/Shanghai）」的 epoch 秒，
 * 与 {@code EastMoneyDataSource} 完全相同，切换源时横轴不会跳。
 */
@Slf4j
@Component
public class TencentDataSource implements StockDataSource {

    private static final String NAME = "Tencent";
    private static final String KLINE_URL =
            "https://web.ifzq.gtimg.cn/appstock/app/usfqkline/get?param=%s,%s,,,%d,qfq";
    private static final String QUOTE_URL = "https://qt.gtimg.cn/q=us%s";

    /** 周线 800 根 ≈ 15 年、月线 800 根 ≈ 66 年；单次上限 1200，留余量。 */
    private static final int WEEK_LIMIT = 800;
    private static final int MONTH_LIMIT = 800;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Charset GBK = Charset.forName("GBK");

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 符号 → 腾讯代码（{@code AAPL.US → AAPL.OQ}）。交易所归属稳定，不做 TTL。 */
    private final Map<String, String> codeCache = new ConcurrentHashMap<>();

    @Value("${datasource.tencent.enabled:true}")
    private boolean enabled;

    @Override
    public List<KlineBarDTO> getMinuteData(String symbol, int period) {
        // 腾讯没有美股历史分钟线，返回空让路由层去试别的源（当前也没有能成的源）。
        return new ArrayList<>();
    }

    @Override
    public List<KlineBarDTO> getWeeklyData(String symbol) {
        return fetch(symbol, "week", WEEK_LIMIT);
    }

    @Override
    public List<KlineBarDTO> getMonthlyData(String symbol) {
        return fetch(symbol, "month", MONTH_LIMIT);
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    private List<KlineBarDTO> fetch(String symbol, String period, int limit) {
        if (!"US".equals(StockCodeUtil.marketOf(symbol))) {
            return new ArrayList<>();
        }
        try {
            String tencentCode = tencentCodeOf(symbol);
            if (tencentCode == null) {
                return new ArrayList<>();
            }
            String url = String.format(KLINE_URL, klineCode(tencentCode), period, limit);
            JsonNode root = objectMapper.readTree(get(url));
            return parse(period, root);
        } catch (Exception e) {
            log.warn("[Tencent] {} {} 拉取失败: {}", symbol, period, e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * K 线的 {@code param} 要带 {@code us} 前缀（{@code usAAPL.OQ}），
     * 行情接口返回的却是 {@code AAPL.OQ}；少这个前缀接口回空数组且不报错。
     */
    static String klineCode(String tencentCode) {
        return tencentCode.startsWith("us") ? tencentCode : "us" + tencentCode;
    }

    /** 解析 {@code data.{code}.qfqweek / qfqmonth}；行格式 {@code [日期, 开, 收, 高, 低, 量]}。 */
    static List<KlineBarDTO> parse(String period, JsonNode root) {
        List<KlineBarDTO> result = new ArrayList<>();
        if (root == null) {
            return result;
        }
        JsonNode data = root.path("data");
        if (!data.isObject() || data.size() == 0) {
            return result;
        }
        JsonNode rows = data.elements().next().path("qfq" + period);
        if (!rows.isArray()) {
            return result;
        }
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() < 6) {
                continue;
            }
            try {
                LocalDate date = LocalDate.parse(row.get(0).asText(), DATE);
                KlineBarDTO bar = new KlineBarDTO();
                bar.setTime(epochOf(date));
                bar.setOpen(new BigDecimal(row.get(1).asText()));
                bar.setClose(new BigDecimal(row.get(2).asText()));
                bar.setHigh(new BigDecimal(row.get(3).asText()));
                bar.setLow(new BigDecimal(row.get(4).asText()));
                bar.setVolume(new BigDecimal(row.get(5).asText()).longValue());
                result.add(bar);
            } catch (RuntimeException e) {
                log.debug("[Tencent] 跳过非法 K 线行: {}", row);
            }
        }
        return result;
    }

    /** 与东财图一致：交易日 00:00（上海时区）的 epoch 秒。 */
    static long epochOf(LocalDate date) {
        return date.atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond();
    }

    /** 解析 {@code v_usAAPL="200~苹果~AAPL.OQ~..."}，取第 3 段作为 K 线代码。 */
    static String parseCode(String body) {
        if (body == null) {
            return null;
        }
        int start = body.indexOf('"');
        int end = body.lastIndexOf('"');
        if (start < 0 || end <= start) {
            return null;
        }
        String[] f = body.substring(start + 1, end).split("~", -1);
        if (f.length < 3 || !"200".equals(f[0].trim())) {
            return null;
        }
        String code = f[2].trim();
        return code.contains(".") ? code : null;
    }

    private String tencentCodeOf(String symbol) throws Exception {
        String cached = codeCache.get(symbol);
        if (cached != null) {
            return cached;
        }
        String body = new String(httpClient.send(
                HttpRequest.newBuilder(URI.create(String.format(QUOTE_URL, StockCodeUtil.pureCode(symbol))))
                        .timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray()).body(), GBK);
        String code = parseCode(body);
        if (code != null) {
            codeCache.put(symbol, code);
        }
        return code;
    }

    private String get(String url) throws Exception {
        HttpResponse<byte[]> response = httpClient.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new String(response.body(), StandardCharsets.UTF_8);
    }
}
