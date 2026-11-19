package com.fuli.trade.quant;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeekdayTradingDayCalendarTest {
    @Test
    void shouldSkipConfiguredHolidayAndWeekend() {
        var calendar = new WeekdayTradingDayCalendar("2026-11-16");

        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 11, 16)));
        assertFalse(calendar.isTradingDay(LocalDate.of(2026, 11, 15)));
        assertTrue(calendar.isTradingDay(LocalDate.of(2026, 11, 17)));
    }
}
