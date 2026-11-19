package com.fuli.trade.quant;

import java.time.LocalDate;

/** 交易日判断接口，后续可接入节假日历。 */
@FunctionalInterface
public interface TradingDayCalendar {
    boolean isTradingDay(LocalDate date);
}
