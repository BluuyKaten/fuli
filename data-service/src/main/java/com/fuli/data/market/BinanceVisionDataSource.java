package com.fuli.data.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipInputStream;

/**
 * Binance Vision 数据源：公共历史行情镜像，按「月」提供 zip 压缩的 K 线 CSV。
 *
 * <p>URL：{@code https://data.binance.vision/data/spot/monthly/klines/{SYM}/{INT}/{SYM}-{INT}-{YYYY-MM}.zip}。
 * CSV 列（Binance kline）：{@code open_time, open, high, low, close, volume, close_time,
 * quote_volume, trades, taker_buy_base, taker_buy_quote, ignore}。
 * 注意：open_time/close_time 在 2024 及以前是「毫秒」，2025 起改为「微秒」，解析时按位数自适应。
 */
@Slf4j
@Component
public class BinanceVisionDataSource implements CryptoDataSource {

    /** open_time 大于该值即视为微秒（1e13 ms ≈ 2286 年，正常毫秒值恒小于它）。 */
    private static final long MICROS_THRESHOLD = 10_000_000_000_000L;

    private final HttpGet http;
    private final boolean enabled;

    public BinanceVisionDataSource(HttpGet http, CryptoMarketProperties properties) {
        this.http = http;
        this.enabled = properties.isBinanceEnabled();
    }

    @Override
    public String getName() {
        return "BinanceVision";
    }

    @Override
    public boolean isAvailable() {
        return enabled;
    }

    @Override
    public List<CryptoCandle> fetch(String symbol, String interval, long fromTs, long toTs) {
        String sym = CryptoSymbol.toBinance(symbol);
        List<CryptoCandle> result = new ArrayList<>();
        for (String ym : months(fromTs, toTs)) {
            String url = "https://data.binance.vision/data/spot/monthly/klines/"
                    + sym + "/" + interval + "/" + sym + "-" + interval + "-" + ym + ".zip";
            try {
                byte[] zip = http.get(url);
                result.addAll(parseZip(zip, fromTs, toTs));
            } catch (IOException e) {
                // 某个月无数据（该月文件不存在）属正常，跳过继续；整体失败让上层切备用源。
                log.debug("下载 {} 失败: {}", url, e.getMessage());
            }
        }
        result.sort(java.util.Comparator.comparingLong(CryptoCandle::ts));
        return result;
    }

    /** 覆盖 [fromTs, toTs) 的所有「yyyy-MM」月份；toTs 为开区间。 */
    static List<String> months(long fromTs, long toTs) {
        List<String> list = new ArrayList<>();
        var fmt = DateTimeFormatter.ofPattern("yyyy-MM").withZone(ZoneOffset.UTC);
        var it = Instant.ofEpochSecond(fromTs).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        // 最后一份要下载的月份 = (toTs-1) 秒所在的月份（toTs 是开区间，不包含自身）
        var end = Instant.ofEpochSecond(Math.max(toTs - 1, 0)).atZone(ZoneOffset.UTC)
                .toLocalDate().withDayOfMonth(1);
        while (!it.isAfter(end)) {
            list.add(fmt.format(it));
            it = it.plusMonths(1);
        }
        return list;
    }

    /** 解出 zip 内唯一 CSV 并解析。 */
    static List<CryptoCandle> parseZip(byte[] zipBytes, long fromTs, long toTs) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            var entry = zis.getNextEntry();
            if (entry == null) {
                return List.of();
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return parseCsv(sb.toString(), fromTs, toTs);
        } catch (IOException e) {
            throw new RuntimeException("解析 Binance zip 失败", e);
        }
    }

    /** 解析 K 线 CSV，过滤到 [fromTs, toTs)。 */
    static List<CryptoCandle> parseCsv(String csv, long fromTs, long toTs) {
        List<CryptoCandle> result = new ArrayList<>();
        String[] lines = csv.split("\n");
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            String[] p = line.split(",");
            long raw = Long.parseLong(p[0]);
            long ts = raw >= MICROS_THRESHOLD ? raw / 1_000_000 : raw / 1_000;
            if (ts < fromTs || ts >= toTs) {
                continue;
            }
            result.add(new CryptoCandle(
                    ts,
                    new BigDecimal(p[1]), new BigDecimal(p[2]),
                    new BigDecimal(p[3]), new BigDecimal(p[4]),
                    new BigDecimal(p[5]), new BigDecimal(p[7])));
        }
        return result;
    }
}
