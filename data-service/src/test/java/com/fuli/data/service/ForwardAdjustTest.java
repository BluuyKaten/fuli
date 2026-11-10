package com.fuli.data.service;

import com.fuli.data.cache.DailyKlineCache;
import com.fuli.data.entity.AdjFactor;
import com.fuli.data.entity.Bar;
import com.fuli.data.entity.Instrument;
import com.fuli.data.mapper.AdjFactorMapper;
import com.fuli.data.mapper.InstrumentMapper;
import com.fuli.data.repository.KlineQueryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 前复权的两个「致命-静默」故障点：
 *
 * <p>1. <b>分母取错</b>：{@code factor_latest} 必须是「窗口末端那根 bar 的因子」。
 * {@code AdjFactorMapper.selectRange} 的 {@code toTs} 是开区间，调用方传 {@code lastTs}
 * 会把末端 bar 排除，分母退化为前一根的因子——窗口止于除权日时，除权日之前的
 * 价格全部不缩放，界面看起来「没复权」，但不报错。
 *
 * <p>2. <b>量的口径</b>：复权只改价格，成交量/成交额必须原样透出。
 */
class ForwardAdjustTest {

    /** 三根 bar：第 3 根的因子翻倍，等价于第 3 天除权。 */
    private static final long TS1 = 1_590_000_000L;
    private static final long TS2 = TS1 + 86_400L;
    private static final long TS3 = TS2 + 86_400L;

    private static Bar bar(long ts, String close, String volume) {
        Bar b = new Bar();
        b.setInstrumentCode("600519.SH");
        b.setPeriod("1d");
        b.setTs(ts);
        b.setOpenPrice(new BigDecimal(close));
        b.setHighPrice(new BigDecimal(close));
        b.setLowPrice(new BigDecimal(close));
        b.setClosePrice(new BigDecimal(close));
        b.setPreClose(new BigDecimal(close));
        b.setVolume(new BigDecimal(volume));
        b.setAmount(new BigDecimal("1000"));
        return b;
    }

    private static AdjFactor factor(long ts, String f) {
        AdjFactor a = new AdjFactor();
        a.setInstrumentCode("600519.SH");
        a.setTs(ts);
        a.setFactor(new BigDecimal(f));
        return a;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> barsOf(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("bars");
    }

    @Test
    @DisplayName("forward：末端 bar 的因子必须进分母（区间上界要 +1 秒）")
    void terminalBarFactorIsTheDenominator() {
        KlineQueryRepository repository = mock(KlineQueryRepository.class);
        DailyKlineCache cache = mock(DailyKlineCache.class);
        InstrumentMapper instrumentMapper = mock(InstrumentMapper.class);
        AdjFactorMapper adjFactorMapper = mock(AdjFactorMapper.class);

        Instrument inst = new Instrument();
        inst.setCode("600519.SH");
        inst.setMarket("SH");
        inst.setName("贵州茅台");
        inst.setCurrency("CNY");
        inst.setPriceScale(2);
        inst.setTz("Asia/Shanghai");
        inst.setLotSize(new BigDecimal("100"));
        inst.setTickSize(new BigDecimal("0.01"));
        when(instrumentMapper.selectById("600519.SH")).thenReturn(inst);

        List<Bar> raw = new ArrayList<>(List.of(
                bar(TS1, "10", "100"),
                bar(TS2, "20", "200"),
                bar(TS3, "30", "300")));
        when(repository.selectRange(eq("600519.SH"), eq("1d"), anyLong(), anyLong(), isNull()))
                .thenReturn(raw);

        // 因子：前两天 1，第三天 2 —— 分母必须是第三天的 2
        when(adjFactorMapper.selectRange(eq("600519.SH"), anyLong(), anyLong()))
                .thenReturn(List.of(factor(TS1, "1"), factor(TS2, "1"), factor(TS3, "2")));

        MarketBarsService service = new MarketBarsService(
                repository, cache, instrumentMapper, adjFactorMapper);

        Map<String, Object> data = service.query(
                "600519.SH", "1d", "2020-05-20", "2020-05-22", null, "forward");

        List<Map<String, Object>> bars = barsOf(data);
        assertEquals(3, bars.size());
        assertEquals(0, new BigDecimal("5").compareTo((BigDecimal) bars.get(0).get("close")));
        assertEquals(0, new BigDecimal("10").compareTo((BigDecimal) bars.get(1).get("close")));
        // 末端 bar 的 factor == factor_latest，比值恒为 1，必然等于原价
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) bars.get(2).get("close")));

        // 量不缩放（复权是价格口径）
        assertEquals(0, new BigDecimal("100").compareTo((BigDecimal) bars.get(0).get("volume")));

        // 回归护栏：上界必须是 lastTs + 1，否则末端 bar 被开区间排除
        ArgumentCaptor<Long> to = ArgumentCaptor.forClass(Long.class);
        verify(adjFactorMapper).selectRange(eq("600519.SH"), eq(TS1), to.capture());
        assertEquals(TS3 + 1, to.getValue());
    }

    @Test
    @DisplayName("forward：无因子的标的原样返回，等价 none（加密/未回填市场）")
    void withoutFactorsFallsBackToRaw() {
        KlineQueryRepository repository = mock(KlineQueryRepository.class);
        DailyKlineCache cache = mock(DailyKlineCache.class);
        InstrumentMapper instrumentMapper = mock(InstrumentMapper.class);
        AdjFactorMapper adjFactorMapper = mock(AdjFactorMapper.class);

        Instrument inst = new Instrument();
        inst.setCode("BTCUSDT.CRYPTO");
        inst.setMarket("CRYPTO");
        inst.setName("BTCUSDT");
        inst.setCurrency("USDT");
        inst.setPriceScale(2);
        inst.setTz("UTC");
        when(instrumentMapper.selectById("BTCUSDT.CRYPTO")).thenReturn(inst);

        Bar b = bar(TS1, "100", "1");
        b.setInstrumentCode("BTCUSDT.CRYPTO");
        when(repository.selectRange(eq("BTCUSDT.CRYPTO"), eq("1d"), anyLong(), anyLong(), isNull()))
                .thenReturn(new ArrayList<>(List.of(b)));
        when(adjFactorMapper.selectRange(any(), anyLong(), anyLong())).thenReturn(List.of());

        MarketBarsService service = new MarketBarsService(
                repository, cache, instrumentMapper, adjFactorMapper);

        Map<String, Object> data = service.query(
                "BTCUSDT.CRYPTO", "1d", "2020-05-20", "2020-05-22", null, "forward");

        assertEquals(0, new BigDecimal("100").compareTo(
                (BigDecimal) barsOf(data).get(0).get("close")));
    }

    @Test
    @DisplayName("none：即使有因子也不改价格")
    void noneKeepsRaw() {
        KlineQueryRepository repository = mock(KlineQueryRepository.class);
        DailyKlineCache cache = mock(DailyKlineCache.class);
        InstrumentMapper instrumentMapper = mock(InstrumentMapper.class);
        AdjFactorMapper adjFactorMapper = mock(AdjFactorMapper.class);

        Instrument inst = new Instrument();
        inst.setCode("600519.SH");
        inst.setMarket("SH");
        inst.setName("贵州茅台");
        inst.setCurrency("CNY");
        inst.setPriceScale(2);
        inst.setTz("Asia/Shanghai");
        when(instrumentMapper.selectById("600519.SH")).thenReturn(inst);

        when(repository.selectRange(eq("600519.SH"), eq("1d"), anyLong(), anyLong(), isNull()))
                .thenReturn(new ArrayList<>(List.of(bar(TS1, "10", "100"))));
        when(adjFactorMapper.selectRange(any(), anyLong(), anyLong()))
                .thenReturn(List.of(factor(TS1, "5")));

        MarketBarsService service = new MarketBarsService(
                repository, cache, instrumentMapper, adjFactorMapper);

        Map<String, Object> data = service.query(
                "600519.SH", "1d", "2020-05-20", "2020-05-22", null, "none");

        assertEquals(0, new BigDecimal("10").compareTo(
                (BigDecimal) barsOf(data).get(0).get("close")));
    }
}
