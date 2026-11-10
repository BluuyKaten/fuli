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
import static org.mockito.Mockito.*;

class UsSyncServiceTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static UsMarket.UsCandle candle(LocalDate date) {
        return new UsMarket.UsCandle(date, bd("325.58"), bd("334.50"), bd("325.33"),
                bd("331.90"), bd("63160026"), bd("20877909760"));
    }

    private UsSyncService svc(EastMoneyUsDataSource ds, BarMapper barMapper,
                              InstrumentMapper instrumentMapper, AdjFactorMapper adjMapper,
                              DailyKlineCache cache) {
        return new UsSyncService(ds, barMapper, instrumentMapper, adjMapper, cache,
                new UsMarketProperties());
    }

    @Test
    void syncRegistersInstrumentBarsAndFactors() {
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

        int count = svc(ds, barMapper, instrumentMapper, adjMapper, cache).sync("AAPL.US");
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
        svc(ds, barMapper, mock(InstrumentMapper.class), mock(AdjFactorMapper.class),
                mock(DailyKlineCache.class)).sync("AAPL.US");

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

    @Test
    void rejectsNonUsSymbol() {
        UsSyncService s = svc(mock(EastMoneyUsDataSource.class), mock(BarMapper.class),
                mock(InstrumentMapper.class), mock(AdjFactorMapper.class),
                mock(DailyKlineCache.class));
        assertThrows(IllegalArgumentException.class, () -> s.sync("600519.SH"));
    }
}
