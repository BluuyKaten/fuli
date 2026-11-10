package com.fuli.data.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryptoSymbolTest {

    @Test
    void splitsBaseAndQuote() {
        assertEquals("BTC", CryptoSymbol.base("BTC-USDT.CRYPTO"));
        assertEquals("USDT", CryptoSymbol.quote("BTC-USDT.CRYPTO"));
    }

    @Test
    void convertsToBinanceAndGate() {
        assertEquals("BTCUSDT", CryptoSymbol.toBinance("btc-usdt.CRYPTO"));
        assertEquals("BTC_USDT", CryptoSymbol.toGate("BTC-USDT.CRYPTO"));
    }

    @Test
    void resolveCryptoRejectsNonCrypto() {
        assertThrows(IllegalArgumentException.class, () -> CryptoSymbol.resolveCrypto("600519.SH"));
        assertThrows(IllegalArgumentException.class, () -> CryptoSymbol.resolveCrypto("BTCUSDT"));
    }

    @Test
    void resolveCryptoReturnsCanonical() {
        assertEquals("BTC-USDT.CRYPTO", CryptoSymbol.resolveCrypto("btc-usdt.crypto"));
    }

    @Test
    void rejectsMissingDash() {
        assertThrows(IllegalArgumentException.class, () -> CryptoSymbol.base("BTCUSDT.CRYPTO"));
    }
}
