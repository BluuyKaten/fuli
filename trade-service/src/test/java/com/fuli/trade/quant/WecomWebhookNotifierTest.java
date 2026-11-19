package com.fuli.trade.quant;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

class WecomWebhookNotifierTest {
    @Test
    void shouldRejectBusinessFailureEvenWhenHttpStatusIsSuccessful() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () ->
                WecomWebhookNotifier.validateResponse(200, "{\"errcode\":93000,\"errmsg\":\"invalid webhook\"}"));
        assertEquals(200, WecomWebhookNotifier.validateResponse(200, "{\"errcode\":0,\"errmsg\":\"ok\"}"));
    }

    @Test
    void shouldSendTheExpectedWecomTextPayload() {
        AtomicReference<String> body = new AtomicReference<>();
        WecomWebhookNotifier notifier = new WecomWebhookNotifier((url, requestBody) -> {
            body.set(requestBody);
            return 200;
        });

        QuantNotificationService.SendAttempt attempt = notifier.send(
                "https://example.invalid/webhook?key=secret", "测试排行消息");

        assertTrue(attempt.success());
        JSONObject payload = JSON.parseObject(body.get());
        assertEquals("text", payload.getString("msgtype"));
        assertEquals("测试排行消息", payload.getJSONObject("text").getString("content"));
    }

    @Test
    void shouldRetryBoundedAndReturnFailureWithoutExposingWebhook() {
        WecomWebhookNotifier notifier = new WecomWebhookNotifier((url, requestBody) -> 500);

        QuantNotificationService.SendAttempt attempt = notifier.send(
                "https://example.invalid/webhook?key=top-secret", "消息");

        assertEquals(false, attempt.success());
        assertEquals(3, attempt.attempts());
        assertTrue(attempt.error().contains("HTTP 500"));
        assertTrue(!attempt.error().contains("top-secret"));
    }

    @Test
    void shouldNotCallTheNetworkWhenNotificationIsDisabled() {
        QuantRankingRepository repository = mock(QuantRankingRepository.class);
        WecomWebhookNotifier notifier = mock(WecomWebhookNotifier.class);
        when(repository.findNotificationConfig(7L)).thenReturn(Optional.of(
                new QuantRankingDtos.NotificationConfig(false, "https://example.invalid", null)));
        QuantNotificationService service = new QuantNotificationService(repository, notifier);

        var result = service.testSend(7L);

        assertEquals("SKIPPED", result.status());
        verifyNoInteractions(notifier);
    }
}
