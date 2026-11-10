package com.fuli.ai.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Optional;

/**
 * 用服务令牌访问 data-service 的受保护只读端点（ADR-0010 决策 4）。
 *
 * <p>只封装**两个读接口**（挂单、余额），方法名与路径都写死在这里——
 * 不给「传个路径就能调」的通用方法，避免某天有人顺手把
 * {@code /data/okx/trade/order} 也塞进来。
 *
 * <p><b>失败要能分清原因</b>：401/403 是服务账号配置问题（密钥、scope、aud），
 * 400 往往是业务问题（该用户还没录 OKX 凭证）。早期版本一律回一句
 * 「检查 client-secret 与 aud」，把「没录 Key」误报成「令牌不可用」，排查会绕远路——
 * 所以这里把 HTTP 状态与对端 message 原样带出来。
 */
@Slf4j
@Component
public class OkxReadOnlyClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient dataClient;

    private final ServiceTokenProvider tokens;

    public OkxReadOnlyClient(@Value("${fuli.data-service-url}") String dataServiceUrl,
                             ServiceTokenProvider tokens) {
        this.dataClient = RestClient.builder().baseUrl(dataServiceUrl).build();
        this.tokens = tokens;
    }

    /** 读结果：ok=false 时 reason 是给人看的具体原因，不是笼统的「不可用」。 */
    public record ReadResult(boolean ok, String body, String reason) {
    }

    public ReadResult pendingOrders(String instType) {
        String type = instType == null || instType.isBlank() ? "SPOT" : instType;
        return get("/data/okx/trade/orders-pending?instType={instType}", type);
    }

    public ReadResult balance(String ccy) {
        if (ccy == null || ccy.isBlank()) {
            return get("/data/okx/trade/balance", null);
        }
        return get("/data/okx/trade/balance?ccy={ccy}", ccy);
    }

    /** 兼容按 Optional 使用的调用方（编排层只需要「有没有」）。 */
    public Optional<String> pendingOrdersOrEmpty(String instType) {
        ReadResult result = pendingOrders(instType);
        return result.ok() ? Optional.ofNullable(result.body()) : Optional.empty();
    }

    private ReadResult get(String uriTemplate, String arg) {
        String token = tokens.token();
        if (token == null) {
            return new ReadResult(false, null,
                    "拿不到服务令牌：检查 fuli.ai.client-secret 是否与 auth-service 的 "
                            + "fuli.agent.clients.ai-service.secret 一致");
        }
        try {
            String body = (arg == null
                    ? dataClient.get().uri(uriTemplate)
                    : dataClient.get().uri(uriTemplate, arg))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                    .retrieve()
                    .body(String.class);
            return new ReadResult(true, body, null);
        } catch (RestClientResponseException e) {
            String reason = describe(e);
            log.warn("[AI-OKX] 受保护只读端点返回 {}：{}", e.getStatusCode().value(), reason);
            return new ReadResult(false, null, reason);
        } catch (Exception e) {
            log.warn("[AI-OKX] 受保护只读端点调用异常 {}：{}", uriTemplate, e.getMessage());
            return new ReadResult(false, null, "data-service 不可达：" + e.getMessage());
        }
    }

    /** 把「令牌问题」和「业务问题」分开说，避免把没录 Key 误报成鉴权失败。 */
    private static String describe(RestClientResponseException e) {
        int status = e.getStatusCode().value();
        String message = extractMessage(e.getResponseBodyAsString());
        String prefix = switch (status) {
            case 401 -> "服务令牌被拒（aud 或签名不一致）";
            case 403 -> "服务令牌没有该 scope";
            case 400 -> "业务拒绝";
            default -> "HTTP " + status;
        };
        return prefix + "：" + (message == null ? e.getStatusText() : message);
    }

    private static String extractMessage(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(body);
            String msg = root.path("message").asText(null);
            return msg == null || msg.isBlank() ? null : msg;
        } catch (Exception ignored) {
            return null;
        }
    }
}
