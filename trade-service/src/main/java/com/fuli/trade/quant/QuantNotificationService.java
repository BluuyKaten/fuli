package com.fuli.trade.quant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/** 企业微信通知配置、测试发送和发送日志服务。 */
@Service
public class QuantNotificationService {
    private final QuantRankingRepository repository;
    private final WecomWebhookNotifier notifier;

    public QuantNotificationService(QuantRankingRepository repository, WecomWebhookNotifier notifier) {
        this.repository = repository;
        this.notifier = notifier;
    }

    @Transactional
    public void saveConfig(long userId, boolean enabled, String webhookUrl) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            webhookUrl = getConfig(userId).webhookUrl();
        }
        if (enabled && (webhookUrl == null || webhookUrl.isBlank())) {
            throw new IllegalArgumentException("启用通知时必须填写企业微信 webhook");
        }
        repository.saveNotificationConfig(userId, enabled, webhookUrl);
    }

    public QuantRankingDtos.NotificationConfig getConfig(long userId) {
        return repository.findNotificationConfig(userId).orElse(new QuantRankingDtos.NotificationConfig(false, null, null));
    }

    @Transactional
    public QuantRankingDtos.NotificationResult testSend(long userId) {
        QuantRankingDtos.NotificationConfig config = getConfig(userId);
        if (!config.enabled()) {
            repository.insertNotificationLog(userId, null, "SKIPPED", "通知未启用", null, 0);
            return new QuantRankingDtos.NotificationResult(false, "SKIPPED", "通知未启用");
        }
        SendAttempt attempt = notifier.send(config.webhookUrl(), "复利终端量化排行测试消息");
        repository.insertNotificationLog(userId, null, attempt.success() ? "SENT" : "FAILED",
                "测试消息", attempt.error(), attempt.attempts());
        return new QuantRankingDtos.NotificationResult(attempt.success(), attempt.success() ? "SENT" : "FAILED",
                attempt.success() ? "测试消息已发送" : "企业微信发送失败");
    }

    @Transactional
    public QuantRankingDtos.NotificationResult notifyDaily(long userId, LocalDate date, String message) {
        QuantRankingDtos.NotificationConfig config = getConfig(userId);
        if (!config.enabled()) {
            repository.insertNotificationLog(userId, date, "SKIPPED", message, null, 0);
            return new QuantRankingDtos.NotificationResult(false, "SKIPPED", "通知未启用");
        }
        SendAttempt attempt = notifier.send(config.webhookUrl(), message);
        repository.insertNotificationLog(userId, date, attempt.success() ? "SENT" : "FAILED",
                message, attempt.error(), attempt.attempts());
        return new QuantRankingDtos.NotificationResult(attempt.success(), attempt.success() ? "SENT" : "FAILED",
                attempt.success() ? "排行通知已发送" : "企业微信发送失败");
    }

    /** 采集或导入失败时写入本地日志，下一次调度可以安全重试。 */
    @Transactional
    public void recordFailure(long userId, LocalDate date, String message) {
        repository.insertNotificationLog(userId, date, "FAILED", message, message, 0);
    }

    public record SendAttempt(boolean success, int attempts, String error) {
    }
}
