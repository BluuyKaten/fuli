package com.fuli.trade.quant;

import com.alibaba.fastjson2.JSON;
import com.fuli.common.api.Result;
import com.fuli.common.api.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

/** 量化排行导入、查询、采集和通知接口。 */
@RestController
@RequestMapping("/trade/quant-ranking")
public class QuantRankingController {
    private final QuantRankingService rankingService;
    private final QuantCollectorScheduler scheduler;
    private final QuantNotificationService notificationService;
    private final HttpServletRequest request;
    private final QuantAutomationService automationService;
    private final DazhihuiExportCollector exportCollector;

    public QuantRankingController(QuantRankingService rankingService,
                                  QuantCollectorScheduler scheduler,
                                  QuantNotificationService notificationService,
                                  HttpServletRequest request, QuantAutomationService automationService,
                                  DazhihuiExportCollector exportCollector) {
        this.rankingService = rankingService;
        this.scheduler = scheduler;
        this.notificationService = notificationService;
        this.request = request;
        this.automationService = automationService;
        this.exportCollector = exportCollector;
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<QuantRankingDtos.ImportResult> importMultipart(
            @RequestParam(required = false) String text,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(required = false) LocalDate tradingDate,
            @RequestParam(required = false) String mapping) throws IOException {
        String source = file == null ? "粘贴文本" : file.getOriginalFilename();
        String content = text;
        if (file != null && !file.isEmpty()) {
            if (file.getOriginalFilename() != null && file.getOriginalFilename().toLowerCase().endsWith(".xlsx")) {
                throw new IllegalArgumentException("暂不支持直接解析 XLSX，请从大智慧复制为 CSV 或制表符文本后导入");
            }
            content = new QuantRankingParser().decodeText(file.getBytes());
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("请粘贴排行文本或上传 CSV 文件");
        }
        return Result.success("量化排行导入成功", rankingService.importText(userId(),
                tradingDate == null ? LocalDate.now() : tradingDate, content, source, parseMapping(mapping)));
    }

    @PostMapping(value = "/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<QuantRankingDtos.ImportResult> importJson(@RequestBody ImportJsonRequest body) {
        return Result.success("量化排行导入成功", rankingService.importText(userId(),
                body.tradingDate() == null ? LocalDate.now() : body.tradingDate(), body.text(), body.sourceName(), body.mapping()));
    }

    @GetMapping("/daily")
    public Result<QuantRankingDtos.DailyData> daily(@RequestParam LocalDate date,
                                                    @RequestParam(defaultValue = "20") int limit) {
        return Result.success(new QuantRankingDtos.DailyData(date, rankingService.daily(userId(), date, limit)));
    }

    @GetMapping("/summary")
    public Result<QuantRankingDtos.SummaryData> summary(@RequestParam(defaultValue = "20") int limit) {
        var rows = rankingService.summary(userId(), limit);
        return Result.success(new QuantRankingDtos.SummaryData(rows, rankingService.summaryCount(userId())));
    }

    @GetMapping("/logs")
    public Result<java.util.List<QuantRankingDtos.LogItem>> logs(@RequestParam(defaultValue = "30") int limit) {
        return Result.success(rankingService.logs(userId(), limit));
    }

    @PostMapping("/collect")
    public Result<QuantRankingDtos.SchedulerResult> collect(@RequestParam(required = false) LocalDate date) {
        LocalDate target = date == null ? LocalDate.now() : date;
        return Result.success(automationService.collect(userId(), target));
    }

    @GetMapping("/automation/config")
    public Result<QuantAutomationConfig> automationConfig() {
        return Result.success(automationService.getConfig(userId()));
    }

    @PostMapping("/collect-file")
    public Result<QuantRankingDtos.SchedulerResult> collectFile(@RequestParam(required = false) LocalDate date) {
        LocalDate target = date == null ? LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")) : date;
        var collected = exportCollector.collect();
        if (!collected.success()) return Result.success(new QuantRankingDtos.SchedulerResult(
                "COLLECT_FAILED", target, 0, collected.failureReason()));
        return Result.success(scheduler.runText(userId(), target, collected.text(), collected.sourceName()));
    }

    @PutMapping("/automation/config")
    public Result<Void> saveAutomationConfig(@RequestBody QuantAutomationConfig config) {
        automationService.saveConfig(userId(), config);
        return Result.success("自动采集设置已保存", null);
    }

    @GetMapping("/automation/status")
    public Result<QuantAutomationService.AutomationStatus> automationStatus() {
        return Result.success(automationService.status(userId()));
    }

    @PostMapping(value = "/collect-text", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Result<QuantRankingDtos.SchedulerResult> collectText(@RequestBody CollectTextRequest body) {
        LocalDate target = body.tradingDate() == null ? LocalDate.now() : body.tradingDate();
        return Result.success(scheduler.runText(userId(), target, body.text(), body.sourceName()));
    }

    @GetMapping("/notification/config")
    public Result<Map<String, Object>> notificationConfig() {
        QuantRankingDtos.NotificationConfig config = notificationService.getConfig(userId());
        return Result.success(Map.of("enabled", config.enabled(), "webhookConfigured",
                config.webhookUrl() != null && !config.webhookUrl().isBlank()));
    }

    @PutMapping("/notification/config")
    public Result<Void> saveNotificationConfig(@RequestBody NotificationConfigRequest body) {
        notificationService.saveConfig(userId(), body.enabled(), body.webhookUrl());
        return Result.success("通知配置已保存", null);
    }

    @PostMapping("/notification/test")
    public Result<QuantRankingDtos.NotificationResult> testNotification() {
        return Result.success(notificationService.testSend(userId()));
    }

    private Map<String, String> parseMapping(String mapping) {
        return mapping == null || mapping.isBlank() ? null : JSON.parseObject(mapping, Map.class);
    }

    private long userId() {
        return AuthenticatedUser.requireId(request);
    }

    public record ImportJsonRequest(String text, LocalDate tradingDate, String sourceName,
                                    Map<String, String> mapping) {
    }

    public record CollectTextRequest(String text, LocalDate tradingDate, String sourceName) {
    }

    public record NotificationConfigRequest(boolean enabled, String webhookUrl) {
    }
}
