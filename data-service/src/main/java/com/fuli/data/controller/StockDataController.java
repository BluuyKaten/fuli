package com.fuli.data.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fuli.common.api.util.StockCodeUtil;
import com.fuli.data.entity.Bar;
import com.fuli.data.entity.Instrument;
import com.fuli.data.entity.StockInfo;
import com.fuli.data.mapper.BarMapper;
import com.fuli.data.mapper.InstrumentMapper;
import com.fuli.data.mapper.StockInfoMapper;
import com.fuli.data.repository.KlineQueryRepository;
import com.fuli.data.service.KlineQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 行情数据控制器（data-service 对外 Feign 接口）。
 *
 * <p>trade-service / analysis-service 通过 {@code DataFeignClient} 调用此控制器获取行情数据，
 * 不再直接访问 data-service 的数据库表，消除服务边界耦合。
 *
 * <p>阶段 0 起底层换成 {@code bar}（ADR-0007），但<b>对外契约一字未改</b>：
 * {@code tradeDate} 仍是 {@code yyyyMMdd} 字符串、{@code preClose} 仍在、
 * {@code vol}/{@code amount} 仍是同一批 key。唯一的口径变化是
 * {@code vol} 从「手」变成「股」（×100）、{@code amount} 从「千元」变成「元」（×1000），
 * 这是与美股/加密同列所必需的，前端量柱会自动缩放。
 *
 * <p>注意：为避 Result 双重嵌套（data.data），直接返回数据本身，由调用方 Feign 客户端的原生返回类型接收。
 */
@Slf4j
@RestController
@RequestMapping("/data/stock")
public class StockDataController {

    private final StockInfoMapper stockInfoMapper;
    private final InstrumentMapper instrumentMapper;
    private final BarMapper barMapper;
    private final KlineQueryService klineQueryService;

    public StockDataController(StockInfoMapper stockInfoMapper,
                               InstrumentMapper instrumentMapper,
                               BarMapper barMapper,
                               KlineQueryService klineQueryService) {
        this.stockInfoMapper = stockInfoMapper;
        this.instrumentMapper = instrumentMapper;
        this.barMapper = barMapper;
        this.klineQueryService = klineQueryService;
    }

    /**
     * 搜索股票（按代码或名称模糊匹配）。
     *
     * <p>A 股走 {@code stock_info}；美股/加密走 {@code instrument}（阶段 1 起，让前端能搜到
     * 已同步的非 A 股标的，如 {@code AAPL.US}）。
     */
    @GetMapping("/search")
    public List<Map<String, Object>> searchStocks(@RequestParam("keyword") String keyword) {
        List<Map<String, Object>> result = new ArrayList<>();

        QueryWrapper<StockInfo> wrapper = new QueryWrapper<>();
        wrapper.like("stock_code", keyword).or().like("stock_name", keyword);
        wrapper.orderByAsc("stock_code");
        wrapper.last("LIMIT 20");
        for (StockInfo s : stockInfoMapper.selectList(wrapper)) {
            result.add(toMap(s));
        }

        // 非 A 股（US / CRYPTO）从 instrument 表搜
        QueryWrapper<Instrument> insWrapper = new QueryWrapper<>();
        insWrapper.and(q -> q.like("code", keyword).or().like("name", keyword));
        insWrapper.in("market", "US", "CRYPTO");
        insWrapper.orderByAsc("code");
        insWrapper.last("LIMIT 20");
        for (Instrument ins : instrumentMapper.selectList(insWrapper)) {
            result.add(toSearchMap(ins));
        }
        return result;
    }

    private Map<String, Object> toSearchMap(Instrument ins) {
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", ins.getCode());
        map.put("stockName", ins.getName());
        map.put("market", ins.getMarket());
        map.put("status", ins.getStatus());
        return map;
    }

    /**
     * 获取股票日线行情。
     *
     * <p>裸 6 位代码按 A 股规则补后缀（{@link StockCodeUtil#resolve}）；无法识别的符号直接 400，
     * 不再「原值查一次再补后缀查一次」——两段式符号是唯一身份，猜代码只会掩盖脏输入。
     */
    @GetMapping("/daily")
    public List<Map<String, Object>> getDailyData(@RequestParam("stockCode") String stockCode,
                                                  @RequestParam(value = "startDate", required = false) String startDate,
                                                  @RequestParam(value = "endDate", required = false) String endDate,
                                                  @RequestParam(value = "limit", required = false) Integer limit) {
        String symbol = resolveSymbol(stockCode, HttpStatus.BAD_REQUEST);
        Long fromTs = KlineQueryService.dayStartTs(normalizeDate(startDate));
        Long toTs = KlineQueryService.dayEndExclusiveTs(normalizeDate(endDate));
        List<Bar> bars = klineQueryService.query(symbol, KlineQueryRepository.PERIOD_DAILY,
                fromTs, toTs, limit);
        return bars.stream().map(this::toMap).collect(java.util.stream.Collectors.toList());
    }

    /**
     * 全历史收盘价缩略序列（LTTB 降采样到约 {@code points} 个点）。
     *
     * <p>供 K 线图底部的全貌条使用：主图只加载最近 620 根，这条序列负责
     * 「让用户知道全历史长什么样、现在看的是哪一段」。约 4 KB。
     */
    @GetMapping("/daily/sparkline")
    public List<Map<String, Object>> dailySparkline(@RequestParam("stockCode") String stockCode,
                                                    @RequestParam(value = "points", defaultValue = "240") int points) {
        String symbol = resolveSymbol(stockCode, HttpStatus.BAD_REQUEST);
        int n = Math.min(Math.max(points, 20), 1000);
        return klineQueryService.sparkline(symbol, n);
    }

    /**
     * 获取股票基础信息。
     *
     * <p>新增 {@code marketCode}（SH/SZ/BJ/US/CRYPTO，来自 {@code instrument.market}）；
     * 旧的 {@code market}（Tushare 板块：主板/创业板…）原样保留，避免破坏调用方。
     */
    @GetMapping("/info")
    public Map<String, Object> getStockInfo(@RequestParam("stockCode") String stockCode) {
        String symbol = resolveSymbol(stockCode, HttpStatus.BAD_REQUEST);
        StockInfo info = stockInfoMapper.selectById(symbol);
        if (info == null) {
            return new HashMap<>();
        }
        Map<String, Object> map = toMap(info);
        map.put("marketCode", marketCodeOf(symbol));
        return map;
    }

    /**
     * 获取股票最新行情（含收盘价、前收盘价）。
     */
    @GetMapping("/latest-price")
    public Map<String, Object> getLatestPrice(@RequestParam("stockCode") String stockCode) {
        String symbol = resolveSymbol(stockCode, HttpStatus.BAD_REQUEST);
        List<Bar> latest = barMapper.selectRecentDesc(symbol, KlineQueryRepository.PERIOD_DAILY, 1);
        Map<String, Object> result = new HashMap<>();
        if (!latest.isEmpty()) {
            Bar bar = latest.get(0);
            result.put("stockCode", bar.getInstrumentCode());
            result.put("closePrice", bar.getClosePrice());
            result.put("preClose", bar.getPreClose());
            result.put("tradeDate", String.valueOf(KlineQueryService.tradeDateOf(bar.getTs())));
        }
        return result;
    }

    /**
     * 批量获取股票最新行情，消除 N+1 查询。
     *
     * <p>返回的 key 保持调用方传入的原值（可能是裸码），值里的 {@code stockCode} 是规范符号。
     */
    @GetMapping("/latest-prices")
    public Map<String, Map<String, Object>> getLatestPrices(@RequestParam("stockCodes") String stockCodes) {
        List<String> rawCodes = normalizeCodeList(stockCodes);
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (rawCodes.isEmpty()) {
            return result;
        }

        Map<String, String> symbolToRaw = mapSymbolToRaw(rawCodes);
        List<Bar> rows = barMapper.selectLatestBatch(symbolToRaw.keySet(),
                KlineQueryRepository.PERIOD_DAILY);
        for (Bar row : rows) {
            String raw = symbolToRaw.getOrDefault(row.getInstrumentCode(), row.getInstrumentCode());
            result.put(raw, toLatestMap(row));
        }
        return result;
    }

    /**
     * 批量获取股票基础信息，消除 N+1 查询（一次 IN 查询）。
     */
    @GetMapping("/infos")
    public Map<String, Map<String, Object>> getStockInfos(@RequestParam("stockCodes") String stockCodes) {
        List<String> rawCodes = normalizeCodeList(stockCodes);
        Map<String, Map<String, Object>> result = new HashMap<>();
        if (rawCodes.isEmpty()) {
            return result;
        }

        Map<String, String> symbolToRaw = mapSymbolToRaw(rawCodes);
        List<StockInfo> infos = stockInfoMapper.selectBatchIds(symbolToRaw.keySet());
        Set<String> symbols = new LinkedHashSet<>();
        for (StockInfo info : infos) {
            symbols.add(info.getStockCode());
        }
        Map<String, String> marketCodes = marketCodesOf(symbols);

        for (StockInfo info : infos) {
            String raw = symbolToRaw.getOrDefault(info.getStockCode(), info.getStockCode());
            Map<String, Object> map = toMap(info);
            map.put("marketCode", marketCodes.get(info.getStockCode()));
            result.put(raw, map);
        }
        return result;
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 解析符号；无法解析时返回 400，而不是静默拿原值去查（查不到只会返回空列表，最难排查）。
     */
    private static String resolveSymbol(String raw, HttpStatus status) {
        String symbol = StockCodeUtil.resolveOrNull(raw);
        if (symbol == null) {
            throw new ResponseStatusException(status,
                    "非法的标的符号：" + raw + "；美股/加密必须带市场后缀（如 AAPL.US）");
        }
        return symbol;
    }

    /** 原值 → 规范符号，并保留反查表（响应 key 要用原值）。 */
    private static Map<String, String> mapSymbolToRaw(List<String> rawCodes) {
        Map<String, String> symbolToRaw = new LinkedHashMap<>();
        for (String raw : rawCodes) {
            String symbol = StockCodeUtil.resolveOrNull(raw);
            if (symbol != null) {
                symbolToRaw.putIfAbsent(symbol, raw);
            }
        }
        return symbolToRaw;
    }

    /** 日期入参统一成 yyyyMMdd；容忍 "2023-11-03"，其余格式 400。 */
    private static String normalizeDate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().replace("-", "");
        if (s.length() != 8 || !s.chars().allMatch(Character::isDigit)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "日期格式应为 yyyyMMdd 或 yyyy-MM-dd: " + raw);
        }
        return s;
    }

    private String marketCodeOf(String symbol) {
        Instrument instrument = instrumentMapper.selectById(symbol);
        if (instrument != null && instrument.getMarket() != null) {
            return instrument.getMarket();
        }
        // instrument 尚未覆盖该标的时，按符号后缀兜底（不猜市场）
        return symbol.contains(".") ? symbol.substring(symbol.indexOf('.') + 1) : null;
    }

    private Map<String, String> marketCodesOf(Set<String> symbols) {
        Map<String, String> result = new HashMap<>();
        if (symbols.isEmpty()) {
            return result;
        }
        for (Instrument instrument : instrumentMapper.selectBatchIds(symbols)) {
            result.put(instrument.getCode(), instrument.getMarket());
        }
        return result;
    }

    /** 解析逗号分隔的代码列表并 trim。 */
    private List<String> normalizeCodeList(String stockCodes) {
        List<String> result = new ArrayList<>();
        if (stockCodes == null || stockCodes.isBlank()) {
            return result;
        }
        for (String raw : stockCodes.split(",")) {
            String code = raw.trim();
            if (!code.isEmpty()) {
                result.add(code);
            }
        }
        return result;
    }

    private Map<String, Object> toLatestMap(Bar latest) {
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", latest.getInstrumentCode());
        map.put("closePrice", latest.getClosePrice());
        map.put("preClose", latest.getPreClose());
        map.put("tradeDate", String.valueOf(KlineQueryService.tradeDateOf(latest.getTs())));
        return map;
    }

    private Map<String, Object> toMap(StockInfo info) {
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", info.getStockCode());
        map.put("stockName", info.getStockName());
        map.put("area", info.getArea());
        map.put("industry", info.getIndustry());
        map.put("market", info.getMarket());
        map.put("listDate", info.getListDate());
        map.put("status", info.getStatus());
        return map;
    }

    /**
     * bar → 旧契约。
     *
     * <p>{@code changeAmount}/{@code pctChg} 不落库，由 {@code close − preClose} 现算；
     * {@code preClose} 为空的历史行（除权信息缺失）这两个字段也留空，不编造。
     */
    private Map<String, Object> toMap(Bar bar) {
        Map<String, Object> map = new HashMap<>();
        map.put("stockCode", bar.getInstrumentCode());
        map.put("tradeDate", String.valueOf(KlineQueryService.tradeDateOf(bar.getTs())));
        map.put("openPrice", bar.getOpenPrice());
        map.put("highPrice", bar.getHighPrice());
        map.put("lowPrice", bar.getLowPrice());
        map.put("closePrice", bar.getClosePrice());
        map.put("preClose", bar.getPreClose());
        map.put("changeAmount", changeAmount(bar));
        map.put("pctChg", pctChg(bar));
        map.put("vol", bar.getVolume());
        map.put("amount", bar.getAmount());
        return map;
    }

    private static BigDecimal changeAmount(Bar bar) {
        if (bar.getClosePrice() == null || bar.getPreClose() == null) {
            return null;
        }
        return bar.getClosePrice().subtract(bar.getPreClose()).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pctChg(Bar bar) {
        if (bar.getClosePrice() == null || bar.getPreClose() == null
                || bar.getPreClose().signum() == 0) {
            return null;
        }
        return bar.getClosePrice().subtract(bar.getPreClose())
                .multiply(BigDecimal.valueOf(100))
                .divide(bar.getPreClose(), 4, RoundingMode.HALF_UP);
    }
}
