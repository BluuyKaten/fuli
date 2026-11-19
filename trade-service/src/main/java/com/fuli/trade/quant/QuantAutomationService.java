package com.fuli.trade.quant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** 统一页面、定时与启动补跑入口，同一桌面一次仅允许一项采集。 */
@Service
public class QuantAutomationService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final QuantAutomationRepository repository;
    private final QuantCollectorScheduler scheduler;
    private final TradingDayCalendar calendar;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    @Autowired
    public QuantAutomationService(QuantAutomationRepository repository, QuantCollectorScheduler scheduler, TradingDayCalendar calendar) {
        this(repository, scheduler, calendar, Clock.system(ZONE));
    }

    QuantAutomationService(QuantAutomationRepository repository, QuantCollectorScheduler scheduler,
                           TradingDayCalendar calendar, Clock clock) {
        this.repository = repository;
        this.scheduler = scheduler;
        this.calendar = calendar;
        this.clock = clock.withZone(ZONE);
    }

    public QuantAutomationConfig getConfig(long userId) { return repository.get(userId).config(); }

    public void saveConfig(long userId, QuantAutomationConfig config) {
        String executable = config.executablePath() == null ? "" : config.executablePath().trim();
        if (!executable.isBlank()) {
            Path path = Path.of(executable);
            if (!path.isAbsolute() || !path.getFileName().toString().equalsIgnoreCase("dzh2.exe") || !Files.isRegularFile(path)) {
                throw new IllegalArgumentException("请填写本机已安装的大智慧 dzh2.exe 完整路径，或留空识别当前进程");
            }
        }
        repository.save(userId, new QuantAutomationConfig(config.enabled(), executable, "15:10", config.startupCatchUp()));
    }

    public QuantRankingDtos.SchedulerResult collect(long userId, LocalDate date) {
        if (!running.compareAndSet(false, true)) {
            return new QuantRankingDtos.SchedulerResult("ALREADY_RUNNING", date, 0, "正在采集大智慧，请稍后查看结果");
        }
        try {
            var result = scheduler.run(userId, date, getConfig(userId).executablePath());
            repository.record(userId, LocalDateTime.now(clock), result);
            return result;
        } catch (RuntimeException error) {
            var result = new QuantRankingDtos.SchedulerResult("COLLECT_FAILED", date, 0,
                    error.getMessage() == null ? "采集失败，请检查大智慧状态" : error.getMessage());
            repository.record(userId, LocalDateTime.now(clock), result);
            return result;
        } finally { running.set(false); }
    }

    public AutomationStatus status(long userId) {
        var state = repository.get(userId);
        boolean supported = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
        return new AutomationStatus(supported, running.get(), supported
                ? "从本机大智慧的常用菜单采集人气{wyz}，需登录且保持目标行情表打开"
                : "自动采集需要在运行大智慧的 Windows 电脑上启动 Java 服务", state.lastRun());
    }

    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() { runDue(true); }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void tick() { runDue(false); }

    void runDue(boolean startup) {
        LocalDateTime now = LocalDateTime.now(clock);
        if (now.toLocalTime().isBefore(LocalTime.of(15,10)) || !calendar.isTradingDay(now.toLocalDate())) return;
        for (Long userId : repository.enabledUsers()) {
            var state = repository.get(userId);
            if (startup && !state.config().startupCatchUp()) continue;
            if (now.toLocalDate().equals(state.lastSuccess())) continue;
            boolean attemptedToday = state.lastAttempt() != null && state.lastAttempt().toLocalDate().equals(now.toLocalDate());
            if (!startup && !state.config().startupCatchUp() && !attemptedToday
                    && !now.toLocalTime().withSecond(0).withNano(0).equals(LocalTime.of(15,10))) continue;
            if (state.lastAttempt() != null && state.lastAttempt().toLocalDate().equals(now.toLocalDate())
                    && (state.attemptCount() >= 3 || Duration.between(state.lastAttempt(), now).toMinutes() < 5)) continue;
            collect(userId, now.toLocalDate());
        }
    }

    public record AutomationStatus(boolean supported, boolean running, String message, QuantRankingDtos.SchedulerResult lastRun) {}
}
