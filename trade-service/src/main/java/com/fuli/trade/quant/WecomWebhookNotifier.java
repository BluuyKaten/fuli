package com.fuli.trade.quant;

import com.alibaba.fastjson2.JSON;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** 企业微信机器人文本通知器，网络请求被限制为短超时和三次重试。 */
@Component
public class WecomWebhookNotifier {
    private static final int MAX_ATTEMPTS = 3;
    private final WebhookTransport transport;

    @Autowired
    public WecomWebhookNotifier() {
        this((url, body) -> {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return validateResponse(response.statusCode(), response.body());
        });
    }

    public WecomWebhookNotifier(WebhookTransport transport) {
        this.transport = transport;
    }

    /** 企业微信通过响应正文报告业务结果，HTTP 成功并不代表消息已送出。 */
    static int validateResponse(int status, String body) {
        if (status < 200 || status >= 300) return status;
        try {
            var result = JSON.parseObject(body);
            if (result == null || !result.containsKey("errcode")) throw new IllegalStateException("企业微信未返回有效发送结果");
            int code = result.getIntValue("errcode");
            if (code != 0) throw new IllegalStateException("企业微信拒绝发送，错误码：" + code);
            return status;
        } catch (IllegalStateException error) { throw error; }
        catch (RuntimeException error) { throw new IllegalStateException("企业微信发送结果无法解析"); }
    }

    public QuantNotificationService.SendAttempt send(String webhookUrl, String content) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            return new QuantNotificationService.SendAttempt(false, 0, "企业微信 webhook 未配置");
        }
        String payload = JSON.toJSONString(Map.of("msgtype", "text",
                "text", Map.of("content", content == null ? "" : content)));
        String lastError = "发送失败";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                int status = transport.post(webhookUrl, payload);
                if (status >= 200 && status < 300) {
                    return new QuantNotificationService.SendAttempt(true, attempt, null);
                }
                lastError = "HTTP " + status;
            } catch (Exception error) {
                lastError = error.getClass().getSimpleName() + ": "
                        + redact(String.valueOf(error.getMessage()), webhookUrl);
            }
        }
        return new QuantNotificationService.SendAttempt(false, MAX_ATTEMPTS, lastError);
    }

    private String redact(String message, String webhookUrl) {
        if (message == null) {
            return "未知网络错误";
        }
        return message.replace(webhookUrl, "[已隐藏 webhook]");
    }

    @FunctionalInterface
    public interface WebhookTransport {
        int post(String url, String requestBody) throws Exception;
    }
}
