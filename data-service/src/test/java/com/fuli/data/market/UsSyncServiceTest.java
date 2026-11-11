package com.fuli.data.market;

import com.fuli.data.cache.DailyKlineCache;
import com.fuli.data.entity.AdjFactor;
import com.fuli.data.entity.Bar;
import com.fuli.data.entity.Instrument;
import com.fuli.data.mapper.AdjFactorMapper;
import com.fuli.data.mapper.BarMapper;
import com.fuli.data.mapper.InstrumentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class UsSyncServiceTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static UsMarket.UsCandle candle(LocalDate date) {
        return new UsMarket.UsCandle(date, bd("325.58"), bd("334.50"), bd("325.33"),
                bd("331.90"), bd("63160026"), bd("20877909760"));
    }

    private static UsMarketProperties props(String source) {
        UsMarketProperties p = new UsMarketProperties();
        p.setSource(source);
        return p;
    }

    private UsSyncService svc(EastMoneyUsDataSource eastMoney, TencentUsDataSource tencent,
                              BarMapper barMapper, InstrumentMapper instrumentMapper,
                              AdjFactorMapper adjMapper, DailyKlineCache cache, String source) {
        return new UsSyncService(eastMoney, tencent, barMapper, instrumentMapper, adjMapper, cache,
                props(source));
    }

    // ---------------- 东财源（原始价 + 因子） ----------------

    @Test
    void eastMoneySourceRegistersInstrumentBarsAndFactors() {
        EastMoneyUsDataSource ds = mock(EastMoneyUsDataSource.class);
        when(ds.search("AAPL")).thenReturn(
                new UsMarket.UsStockMatch("AAPL", "苹果", "NASDAQ", "105.AAPL"));
        when(ds.fetchDaily("105.AAPL", false)).thenReturn(
                new UsMarket.UsDailyResult(3, List.of(candle(LocalDate.of(2026, 11, 3)))));
        when(ds.fetchDaily("105.AAPL", true)).thenReturn(
                new UsMarket.UsDailyResult(3, List.of(candle(LocalDate.of(2026, 11, 3)))));

        BarMapper barMapper = mock(BarMapper.class);
        InstrumentMapper instrumentMapper = mock(InstrumentMapper.class);
        AdjFactorMapper adjMapper = mock(AdjFactorMapper.class);
        DailyKlineCache cache = mock(DailyKlineCache.class);

        int count = svc(ds, mock(TencentUsDataSource.class), barMapper, instrumentMapper,
                adjMapper, cache, "eastmoney").sync("AAPL.US");

        assertEquals(1, count);
        verify(barMapper).upsert(any(Bar.class));
        verify(adjMapper).upsert(any(AdjFactor.class));
        verify(cache).evict("AAPL.US", "1d");

        ArgumentCaptor<Instrument> cap = ArgumentCaptor.forClass(Instrument.class);
        verify(instrumentMapper).upsert(cap.capture());
        Instrument ins = cap.getValue();
        assertEquals("AAPL.US", ins.getCode());
        assertEquals("US", ins.getMarket());
        assertEquals("NASDAQ", ins.getExchange());
        assertEquals("USD", ins.getCurrency());
        assertEquals("America/New_York", ins.getTz());
        assertEquals("DAY", ins.getSession());
        assertEquals(3, ins.getPriceScale());
    }

    @Test
    void barTsUsesUsEastern930() {
        EastMoneyUsDataSource ds = mock(EastMoneyUsDataSource.class);
        when(ds.search("AAPL")).thenReturn(
                new UsMarket.UsStockMatch("AAPL", "苹果", "NASDAQ", "105.AAPL"));
        when(ds.fetchDaily("105.AAPL", false)).thenReturn(
                new UsMarket.UsDailyResult(3, List.of(candle(LocalDate.of(2026, 1, 1)))));
        when(ds.fetchDaily("105.AAPL", true)).thenReturn(
                new UsMarket.UsDailyResult(3, List.of(candle(LocalDate.of(2026, 1, 1)))));

        BarMapper barMapper = mock(BarMapper.class);
        svc(ds, mock(TencentUsDataSource.class), barMapper, mock(InstrumentMapper.class),
                mock(AdjFactorMapper.class), mock(DailyKlineCache.class), "eastmoney").sync("AAPL.US");

        ArgumentCaptor<Bar> cap = ArgumentCaptor.forClass(Bar.class);
        verify(barMapper).upsert(cap.capture());
        // 冬令时（EST，UTC-5）：美东 09:30 = UTC 14:30
        assertEquals("14:30",
                java.time.Instant.ofEpochSecond(cap.getValue().getTs())
                        .atZone(java.time.ZoneOffset.UTC).toLocalTime().toString());
    }

    @Test
    void toUsTsHandlesDst() {
        long summer = UsSyncService.toUsTs(LocalDate.of(2026, 7, 1)); // EDT, UTC-4 → 13:30 UTC
        long winter = UsSyncService.toUsTs(LocalDate.of(2026, 1, 1)); // EST, UTC-5 → 14:30 UTC
        assertEquals("13:30", java.time.Instant.ofEpochSecond(summer)
                .atZone(java.time.ZoneOffset.UTC).toLocalTime().toString());
        assertEquals("14:30", java.time.Instant.ofEpochSecond(winter)
                .atZone(java.time.ZoneOffset.UTC).toLocalTime().toString());
    }

    // ---------------- 腾讯源（前复权，默认） ----------------

    @Test
    void tencentSourceRefreshesBarsAndClearsStaleFactors() {
        TencentUsDataSource ds = mock(TencentUsDataSource.class);
        when(ds.search("AAPL"))
                .thenReturn(new UsMarket.UsStockMatch("AAPL", "苹果", "NASDAQ", "AAPL.OQ"));
        when(ds.fetchDaily(eq("AAPL.OQ"), any(LocalDate.class))).thenReturn(
                new UsMarket.UsDailyResult(2, List.of(
                        candle(LocalDate.of(2026, 11, 2)),
                        candle(LocalDate.of(2026, 11, 3)))));

        BarMapper barMapper = mock(BarMapper.class);
        InstrumentMapper instrumentMapper = mock(InstrumentMapper.class);
        AdjFactorMapper adjMapper = mock(AdjFactorMapper.class);
        DailyKlineCache cache = mock(DailyKlineCache.class);

        int count = svc(mock(EastMoneyUsDataSource.class), ds, barMapper, instrumentMapper,
                adjMapper, cache, "tencent").sync("AAPL.US");

        assertEquals(2, count);
        // 换源/重同步必须整段替换：否则旧口径的 bar 会和新口径混在一条序列里
        verify(barMapper).deleteBySymbol("AAPL.US", "1d");
        verify(adjMapper).deleteBySymbol("AAPL.US");
        verify(adjMapper, never()).upsert(any(AdjFactor.class));
        verify(cache).evict("AAPL.US", "1d");

        ArgumentCaptor<Bar> bars = ArgumentCaptor.forClass(Bar.class);
        verify(barMapper, times(2)).upsert(bars.capture());
        // preClose 取上一根收盘，而不是留空或臆造
        assertNull(bars.getAllValues().get(0).getPreClose());
        assertEquals(0, bd("331.90").compareTo(bars.getAllValues().get(1).getPreClose()));
    }

    @Test
    void tencentSourceRejectsUnknownSymbolInsteadOfWritingEmptySeries() {
        TencentUsDataSource ds = mock(TencentUsDataSource.class);
        when(ds.search("NOPE")).thenReturn(null);
        BarMapper barMapper = mock(BarMapper.class);

        UsSyncService s = svc(mock(EastMoneyUsDataSource.class), ds, barMapper,
                mock(InstrumentMapper.class), mock(AdjFactorMapper.class),
                mock(DailyKlineCache.class), "tencent");

        assertThrows(IllegalArgumentException.class, () -> s.sync("NOPE.US"));
        verify(barMapper, never()).deleteBySymbol(anyString(), anyString());
    }

    @Test
    void rejectsNonUsSymbol() {
        UsSyncService s = svc(mock(EastMoneyUsDataSource.class), mock(TencentUsDataSource.class),
                mock(BarMapper.class), mock(InstrumentMapper.class),
                mock(AdjFactorMapper.class), mock(DailyKlineCache.class), "tencent");
        assertThrows(IllegalArgumentException.class, () -> s.sync("600519.SH"));
    }
}
