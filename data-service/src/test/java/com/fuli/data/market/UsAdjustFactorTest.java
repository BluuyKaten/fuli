package com.fuli.data.market;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class UsAdjustFactorTest {

    private static UsMarket.UsCandle c(String date, String close) {
        return new UsMarket.UsCandle(LocalDate.parse(date), null, null, null,
                new BigDecimal(close), null, null);
    }

    @Test
    void detectsFourToOneSplit() {
        // 2020-08-28 拆股前 raw=500 / fwd=125（1拆4）；08-31 拆股后 raw=fwd=125
        List<UsMarket.UsCandle> raw = List.of(c("2020-08-28", "500.00"), c("2020-08-31", "125.00"));
        List<UsMarket.UsCandle> fwd = List.of(c("2020-08-28", "125.00"), c("2020-08-31", "125.00"));
        Map<LocalDate, BigDecimal> factors = UsAdjustFactor.compute(raw, fwd);
        assertEquals(0, new BigDecimal("0.25").compareTo(factors.get(LocalDate.parse("2020-08-28"))));
        assertEquals(0, new BigDecimal("1.00").compareTo(factors.get(LocalDate.parse("2020-08-31"))));
    }

    @Test
    void calibratesPriceNoise() {
        // fwd 有精度噪声：125.05/500 = 0.2501，应校准为 0.25
        List<UsMarket.UsCandle> raw = List.of(c("2020-08-28", "500.00"), c("2020-08-31", "125.00"));
        List<UsMarket.UsCandle> fwd = List.of(c("2020-08-28", "125.05"), c("2020-08-31", "125.00"));
        Map<LocalDate, BigDecimal> factors = UsAdjustFactor.compute(raw, fwd);
        assertEquals(0, new BigDecimal("0.25").compareTo(factors.get(LocalDate.parse("2020-08-28"))));
    }

    @Test
    void noSplitYieldsAllOne() {
        List<UsMarket.UsCandle> raw = List.of(c("2020-08-28", "100.00"), c("2020-08-31", "101.00"));
        List<UsMarket.UsCandle> fwd = List.of(c("2020-08-28", "100.01"), c("2020-08-31", "101.00"));
        Map<LocalDate, BigDecimal> factors = UsAdjustFactor.compute(raw, fwd);
        assertEquals(0, new BigDecimal("1.00").compareTo(factors.get(LocalDate.parse("2020-08-28"))));
        assertEquals(0, new BigDecimal("1.00").compareTo(factors.get(LocalDate.parse("2020-08-31"))));
    }
}
