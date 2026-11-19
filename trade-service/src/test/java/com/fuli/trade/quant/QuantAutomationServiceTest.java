package com.fuli.trade.quant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuantAutomationServiceTest {
    private QuantAutomationRepository repository;
    private QuantCollectorScheduler scheduler;
    private QuantAutomationService service;

    @BeforeEach
    void setUp() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:automation_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("RUNSCRIPT FROM 'classpath:schema-h2.sql'");
        repository = new QuantAutomationRepository(jdbc);
        scheduler = mock(QuantCollectorScheduler.class);
        when(scheduler.run(anyLong(), any(), anyString())).thenAnswer(call ->
                new QuantRankingDtos.SchedulerResult("SUCCESS", call.getArgument(1), 25, "采集完成"));
        service = new QuantAutomationService(repository, scheduler, date -> true,
                Clock.fixed(Instant.parse("2026-11-16T08:00:00Z"), ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void shouldPersistSettingsPerUserAndNotEnableOtherUsers() {
        service.saveConfig(7L, new QuantAutomationConfig(true, "", "15:10", true));
        assertTrue(service.getConfig(7L).enabled());
        assertFalse(service.getConfig(8L).enabled());
        assertEquals("15:10", service.getConfig(7L).scheduleTime());
    }

    @Test
    void shouldCatchUpOnceAfterStartupAndRememberSuccessAcrossRestarts() {
        service.saveConfig(7L, new QuantAutomationConfig(true, "", "15:10", true));
        service.runDue(true);
        service.runDue(true);
        verify(scheduler, times(1)).run(eq(7L), eq(LocalDate.of(2026, 11, 16)), eq(""));
        assertEquals("SUCCESS", service.status(7L).lastRun().status());
    }

    @Test
    void shouldRespectStartupCatchUpSetting() {
        service.saveConfig(7L, new QuantAutomationConfig(true, "", "15:10", false));
        service.runDue(true);
        verifyNoInteractions(scheduler);
        service.runDue(false);
        verifyNoInteractions(scheduler);
    }

    @Test
    void shouldRunAtScheduledTimeWhenStartupCatchUpIsDisabled() {
        service = new QuantAutomationService(repository, scheduler, date -> true,
                Clock.fixed(Instant.parse("2026-11-16T07:10:00Z"), ZoneId.of("Asia/Shanghai")));
        service.saveConfig(7L, new QuantAutomationConfig(true, "", "15:10", false));
        service.runDue(false);
        verify(scheduler).run(eq(7L), any(), eq(""));
    }

    @Test
    void shouldKeepFailureStatusInsteadOfClaimingCollectionSucceeded() {
        when(scheduler.run(anyLong(), any(), anyString())).thenReturn(
                new QuantRankingDtos.SchedulerResult("COLLECT_FAILED", LocalDate.of(2026, 11, 16), 0, "未找到人气列"));
        var result = service.collect(7L, LocalDate.of(2026, 11, 16));
        assertEquals("COLLECT_FAILED", result.status());
        assertEquals("未找到人气列", service.status(7L).lastRun().message());
    }

    @Test
    void shouldNotOverwriteSettingsSavedWhileTheCaptureWasRunning() {
        service.saveConfig(7L, new QuantAutomationConfig(true, "", "15:10", true));
        when(scheduler.run(anyLong(), any(), anyString())).thenAnswer(call -> {
            service.saveConfig(7L, new QuantAutomationConfig(false, "", "15:10", false));
            return new QuantRankingDtos.SchedulerResult("SUCCESS", LocalDate.of(2026,11,16), 25, "完成");
        });
        service.collect(7L, LocalDate.of(2026,11,16));
        assertFalse(service.getConfig(7L).enabled());
        assertFalse(service.getConfig(7L).startupCatchUp());
    }
}
