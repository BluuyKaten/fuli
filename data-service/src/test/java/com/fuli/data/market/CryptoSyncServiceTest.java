package com.fuli.data.market;

import com.fuli.data.cache.DailyKlineCache;
import com.fuli.data.entity.Bar;
import com.fuli.data.entity.Instrument;
import com.fuli.data.mapper.BarMapper;
import com.fuli.data.mapper.InstrumentMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CryptoSyncServiceTest {

    private static final long TS0 = 1_609_459_200L;

    private static CryptoDataSource source(String name, List<CryptoCandle> candles, boolean available) {
        return new CryptoDataSource() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public boolean isAvailable() {
                return available;
            }

            @Override
            public List<CryptoCandle> fetch(String symbol, String interval, long fromTs, long toTs) {
                return candles;
            }
        };
    }

    private static CryptoCandle candle(long ts) {
        return new CryptoCandle(ts, bd("29000"), bd("30000"), bd("28000"), bd("29500"),
                bd("100"), bd("2950000"));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @Test
    void syncRegistersInstrumentAndUpsertsBars() {
        BarMapper barMapper = mock(BarMapper.class);
        InstrumentMapper instrumentMapper = mock(InstrumentMapper.class);
        DailyKlineCache cache = mock(DailyKlineCache.class);

        CryptoSyncService svc = new CryptoSyncService(
                List.of(source("BinanceVision", List.of(candle(TS0)), true)),
                barMapper, instrumentMapper, cache, new CryptoMarketProperties());

        int count = svc.sync("BTC-USDT.CRYPTO", "1d", "2021-01-01", "2021-01-02");
        assertEquals(1, count);
        verify(barMapper).upsert(any(Bar.class));
        verify(instrumentMapper).upsert(any(Instrument.class));
        verify(cache).evict(eq("BTC-USDT.CRYPTO"), eq("1d"));
    }

    @Test
    void registersInstrumentWithCryptoAttributes() {
        BarMapper barMapper = mock(BarMapper.class);
        InstrumentMapper instrumentMapper = mock(InstrumentMapper.class);
        DailyKlineCache cache = mock(DailyKlineCache.class);

        CryptoSyncService svc = new CryptoSyncService(
                List.of(source("BinanceVision", List.of(), true)),
                barMapper, instrumentMapper, cache, new CryptoMarketProperties());

        svc.sync("BTC-USDT.CRYPTO", "1d", "2021-01-01", "2021-01-02");
        org.mockito.ArgumentCaptor<Instrument> cap = org.mockito.ArgumentCaptor.forClass(Instrument.class);
        verify(instrumentMapper).upsert(cap.capture());
        Instrument ins = cap.getValue();
        assertEquals("BTC-USDT.CRYPTO", ins.getCode());
        assertEquals("CRYPTO", ins.getMarket());
        assertEquals("USDT", ins.getCurrency());
        assertEquals("UTC", ins.getTz());
        assertEquals("24H", ins.getSession());
    }

    @Test
    void fallsBackToSecondaryWhenPrimaryEmpty() {
        BarMapper barMapper = mock(BarMapper.class);
        InstrumentMapper instrumentMapper = mock(InstrumentMapper.class);
        DailyKlineCache cache = mock(DailyKlineCache.class);

        CryptoSyncService svc = new CryptoSyncService(
                List.of(
                        source("BinanceVision", List.of(), true),
                        source("Gate", List.of(candle(TS0)), true)),
                barMapper, instrumentMapper, cache, new CryptoMarketProperties());

        int count = svc.sync("BTC-USDT.CRYPTO", "1d", "2021-01-01", "2021-01-02");
        assertEquals(1, count, "主源空时应切备用源");
    }

    @Test
    void rejectsUnsupportedInterval() {
        CryptoSyncService svc = new CryptoSyncService(List.of(), mock(BarMapper.class),
                mock(InstrumentMapper.class), mock(DailyKlineCache.class), new CryptoMarketProperties());
        assertThrows(IllegalArgumentException.class,
                () -> svc.sync("BTC-USDT.CRYPTO", "1w", "2021-01-01", "2021-01-02"));
    }

    @Test
    void rejectsNonCryptoSymbol() {
        CryptoSyncService svc = new CryptoSyncService(List.of(), mock(BarMapper.class),
                mock(InstrumentMapper.class), mock(DailyKlineCache.class), new CryptoMarketProperties());
        assertThrows(IllegalArgumentException.class,
                () -> svc.sync("600519.SH", "1d", "2021-01-01", "2021-01-02"));
    }
}
