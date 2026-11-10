package com.fuli.data.market;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 美股行情数据结构（东财源）。
 */
public final class UsMarket {

    private UsMarket() {
    }

    /** 东财搜索匹配结果。 */
    public record UsStockMatch(String code, String name, String exchange, String quoteId) {
    }

    /** 一根美股日线（未复权，date 为美股交易日）。 */
    public record UsCandle(LocalDate date, BigDecimal open, BigDecimal high, BigDecimal low,
                           BigDecimal close, BigDecimal volume, BigDecimal amount) {
    }

    /** 日线拉取结果：价格精度 + 升序 K 线。 */
    public record UsDailyResult(int priceScale, List<UsCandle> candles) {
    }
}
