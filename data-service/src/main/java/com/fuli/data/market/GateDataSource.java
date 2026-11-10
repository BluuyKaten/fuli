package com.fuli.data.market;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Gate 现货 K 线数据源（备用源）。
 *
 * <p>URL：{@code https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=BTC_USDT&interval=1d&limit=1000&to=<ts>}。
 * 字段序（见 {@code docs/multi-market-rollout.md} 阶段 2.1）：
 * {@code [ts, 计价量, close, high, low, open, 基础量, 是否收线]}。
 */
@Slf4j
@Component
public class GateDataSource implements CryptoDataSource {

    private final HttpGet http;
    private final boolean enabled;
    private final ObjectMapper mapper = new ObjectMapper();

    public GateDataSource(HttpGet http, CryptoMarketProperties properties) {
        this.http = http;
        this.enabled = properties.isGateEnabled();
    }

    @Override
    public String getName() {
        return "Gate";
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public List<CryptoCandle> fetch(String symbol, String interval, long fromTs, long toTs) {
        String pair = CryptoSymbol.toGate(symbol);
        List<CryptoCandle> result = new ArrayList<>();
        long cursor = toTs;
        while (true) {
            String url = "https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=" + pair
                    + "&interval=" + interval + "&limit=1000&to=" + cursor;
            List<CryptoCandle> batch;
            try {
                String json = new String(http.get(url), StandardCharsets.UTF_8);
                batch = parse(json);
            } catch (IOException e) {
                throw new RuntimeException("Gate 拉取失败: " + e.getMessage(), e);
            }
            batch.sort(Comparator.comparingLong(CryptoCandle::ts));
            if (batch.isEmpty()) {
                break;
            }
            boolean added = false;
            for (CryptoCandle c : batch) {
                if (c.ts() >= fromTs && c.ts() < toTs) {
                    result.add(c);
                    added = true;
                }
            }
            long earliest = batch.get(0).ts();
            if (batch.size() < 1000 || earliest <= fromTs) {
                break;
            }
            cursor = earliest; // 继续向前翻页
            if (!added) {
                break;
            }
        }
        result.sort(Comparator.comparingLong(CryptoCandle::ts));
        return result;
    }

    /** 解析 Gate candlesticks JSON（字段序见类注释），已收线 K 线才保留。 */
    static List<CryptoCandle> parse(String json) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        List<List<Object>> rows = mapper.readValue(json, new TypeReference<List<List<Object>>>() {
        });
        List<CryptoCandle> result = new ArrayList<>();
        for (List<Object> row : rows) {
            if (row.size() < 7) {
                continue;
            }
            // 未收线（最新一根）不落事实表
            if (row.size() >= 8 && "false".equalsIgnoreCase(String.valueOf(row.get(7)))) {
                continue;
            }
            long ts = ((Number) row.get(0)).longValue();
            result.add(new CryptoCandle(
                    ts,
                    new BigDecimal(String.valueOf(row.get(5))), // open
                    new BigDecimal(String.valueOf(row.get(3))), // high
                    new BigDecimal(String.valueOf(row.get(4))), // low
                    new BigDecimal(String.valueOf(row.get(2))), // close
                    new BigDecimal(String.valueOf(row.get(6))), // 基础量
                    new BigDecimal(String.valueOf(row.get(1))))); // 计价量
        }
        return result;
    }
}
