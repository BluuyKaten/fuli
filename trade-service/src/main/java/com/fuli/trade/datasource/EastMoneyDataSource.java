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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 东方财富数据源（备用）
 * 接口文档：https://quote.eastmoney.com/
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

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${datasource.eastmoney.enabled:true}")
    private boolean enabled;

    /**
     * 东财 {@code secid} 的市场前缀。
     *
     * <p>这是<b>适配器内部协议</b>，不是标的身份规则：市场判断统一走
     * {@link StockCodeUtil#marketOf}（裸 6 位按 A 股推断），这里只负责把市场映射成数字前缀。
     *
     * <p>⚠️ 东财对北交所目前用 {@code 0} 前缀（与深市同号），这是既有实现，<b>不要擅自改成 1</b>；
     * 阶段 1 接入美股时在部署环境实测校准。
     */
    private String getMarketPrefix(String stockCode) {
        String market = StockCodeUtil.marketOf(stockCode);
        return switch (market) {
            case "SH" -> "1";
            case "SZ", "BJ" -> "0";   // BJ：东财现状用 0
            case "US" -> "105";       // 阶段 1 按 instrument.exchange 选 105(NASDAQ)/106(NYSE)
            default -> throw new IllegalArgumentException("东财暂不支持的市场: " + market);
        };
    }

    /**
     * 东方财富分钟线接口
     * secid = 市场.代码，如 0.300750
     */
    @Override
    public List<KlineBarDTO> getMinuteData(String stockCode, int period) {
        String secid = getMarketPrefix(stockCode) + "." + stockCode;
        // klt: 1=1分钟, 5=5分钟, 15=15分钟, 30=30分钟, 60=60分钟
        int klt = period;
        String url = String.format(
                "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=%s&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61&klt=%d&fqt=1&end=20500101&lmt=5000",
                secid, klt
        );

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode klines = root.path("data").path("klines");

            List<KlineBarDTO> result = new ArrayList<>();
            if (klines.isArray()) {
                for (JsonNode line : klines) {
                    // 格式：日期,开盘,收盘,最高,最低,成交量,成交额,振幅,涨跌幅,涨跌额,换手率
                    String[] parts = line.asText().split(",");
                    if (parts.length < 6) continue;

                    KlineBarDTO bar = new KlineBarDTO();
                    // 解析日期时间
                    String datetime = parts[0]; // 2024-01-02 10:30
                    bar.setTime(parseToTimestamp(datetime));
                    bar.setOpen(new BigDecimal(parts[1]));
                    bar.setClose(new BigDecimal(parts[2]));
                    bar.setHigh(new BigDecimal(parts[3]));
                    bar.setLow(new BigDecimal(parts[4]));
                    bar.setVolume(Long.parseLong(parts[5]));
                    result.add(bar);
                }
            }
            log.info("[EastMoney] 获取 {} 周期{} 数据 {} 条", stockCode, period, result.size());
            return result;
        } catch (Exception e) {
            log.error("[EastMoney] 获取分钟线失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<KlineBarDTO> getWeeklyData(String stockCode) {
        String secid = getMarketPrefix(stockCode) + "." + stockCode;
        String url = String.format(
                "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=%s&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61&klt=101&fqt=1&end=20500101&lmt=5000",
                secid
        );
        return fetchKlines(url, stockCode, "周线");
    }

    @Override
    public List<KlineBarDTO> getMonthlyData(String stockCode) {
        String secid = getMarketPrefix(stockCode) + "." + stockCode;
        String url = String.format(
                "https://push2his.eastmoney.com/api/qt/stock/kline/get?secid=%s&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61&klt=102&fqt=1&end=20500101&lmt=5000",
                secid
        );
        return fetchKlines(url, stockCode, "月线");
    }

    private List<KlineBarDTO> fetchKlines(String url, String stockCode, String label) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode klines = root.path("data").path("klines");

            List<KlineBarDTO> result = new ArrayList<>();
            if (klines.isArray()) {
                for (JsonNode line : klines) {
                    String[] parts = line.asText().split(",");
                    if (parts.length < 6) continue;

                    KlineBarDTO bar = new KlineBarDTO();
                    bar.setTime(parseToTimestamp(parts[0])); // 日期格式 2024-01-02
                    bar.setOpen(new BigDecimal(parts[1]));
                    bar.setClose(new BigDecimal(parts[2]));
                    bar.setHigh(new BigDecimal(parts[3]));
                    bar.setLow(new BigDecimal(parts[4]));
                    bar.setVolume(Long.parseLong(parts[5]));
                    result.add(bar);
                }
            }
            log.info("[EastMoney] 获取 {} {} 数据 {} 条", stockCode, label, result.size());
            return result;
        } catch (Exception e) {
            log.error("[EastMoney] 获取{}失败: {}", label, e.getMessage());
            return new ArrayList<>();
        }
    }

    private long parseToTimestamp(String datetime) {
        try {
            // 尝试解析 "yyyy-MM-dd HH:mm" 或 "yyyy-MM-dd"
            java.time.LocalDateTime dt;
            if (datetime.contains(":")) {
                dt = java.time.LocalDateTime.parse(datetime.replace(" ", "T"));
            } else {
                dt = java.time.LocalDate.parse(datetime).atStartOfDay();
            }
            return dt.atZone(java.time.ZoneId.of("Asia/Shanghai")).toEpochSecond();
        } catch (Exception e) {
            return System.currentTimeMillis() / 1000;
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
