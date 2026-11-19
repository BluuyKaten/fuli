package com.fuli.trade.quant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/** 跳过周末和本机配置的节假日。 */
@Component
public class WeekdayTradingDayCalendar implements TradingDayCalendar {
    private final Set<LocalDate> holidays;

    public WeekdayTradingDayCalendar(@Value("${quant-ranking.holidays:}") String configuredHolidays) {
        if (configuredHolidays == null || configuredHolidays.isBlank()) {
            holidays = Collections.emptySet();
            return;
        }
        holidays = Arrays.stream(configuredHolidays.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(LocalDate::parse)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean isTradingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(date);
    }
}
