package com.fuli.auth.controller;

import com.fuli.auth.config.ServiceClientProperties;
import com.fuli.auth.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 服务账号令牌签发：{@code POST /auth/oauth/token}（client_credentials，ADR-0010 决策 4）。
 *
 * <p>形状按 OAuth2 的 client_credentials 走，是为了让调用方用现成的 HTTP 客户端就能接，
 * 不需要为「我们的私有限定」学一套新协议。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>只有 client_credentials</b>：password / refresh_token 一律 unsupported，这条端点不签发用户令牌，
 *       否则「服务身份」就成了绕过用户令牌的万能入口；</li>
 *   <li><b>scope 只能收窄不能扩张</b>：请求的 scope 必须是配置的子集，否则 invalid_scope；</li>
 *   <li><b>失败不带细节</b>：客户端不存在 / 密钥错 / 被停用，统一返回 {@code invalid_client}。
 *       区分这几种情况等于给爆破者一个「这个 client_id 存在」的探针。</li>
 * </ol>
 *
 * <p>响应格式：{@code {"access_token","token_type":"Bearer","expires_in","scope"}}。
 * 注意 token 本身**不落库、不缓存**——签发是纯计算，吊销靠短有效期（默认 15 分钟）。
 */
@Slf4j
@RestController
@RequestMapping("/auth/oauth")
@RequiredArgsConstructor
public class ServiceTokenController {

    private final ServiceClientProperties properties;

    private final JwtUtil jwtUtil;

    @PostMapping("/token")
    public ResponseEntity<Map<String, Object>> token(@RequestParam Map<String, String> form) {
        String grantType = form.getOrDefault("grant_type", "");
        if (!"client_credentials".equals(grantType)) {
            return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type",
                    "只支持 grant_type=client_credentials");
        }

        String clientId = form.getOrDefault("client_id", "");
        String clientSecret = form.getOrDefault("client_secret", "");
        ServiceClientProperties.Client client = properties.getClients().get(clientId);
        if (client == null || !client.isEnabled() || client.getSecret() == null
                || client.getSecret().isBlank() || !secretMatches(client.getSecret(), clientSecret)) {
            log.warn("[AUTH] client_credentials 失败 clientId={} reason=invalid_client", clientId);
            return error(HttpStatus.UNAUTHORIZED, "invalid_client", "客户端认证失败");
        }

        Set<String> allowed = split(client.getScopes());
        String requested = form.getOrDefault("scope", client.getScopes());
        Set<String> wanted = split(requested);
        if (wanted.isEmpty() || !allowed.containsAll(wanted)) {
            // 不回显「允许哪些」，避免把权限清单暴露给已泄露密钥的攻击者
            log.warn("[AUTH] client_credentials 越权申请 clientId={} requested={}", clientId, requested);
            return error(HttpStatus.BAD_REQUEST, "invalid_scope", "申请的 scope 超出授权范围");
        }

        // 排序后写入，保证同一请求每次拿到相同的 scope 字符串（便于日志比对与缓存）
        String scope = String.join(" ", new TreeSet<>(wanted));
        long ttl = properties.getTokenTtlSeconds();
        String token = jwtUtil.generateServiceToken(clientId, scope, ttl, properties.getTokenAudience());
        log.info("[AUTH] 签发服务令牌 clientId={} scope={} ttl={}s aud={}",
                clientId, scope, ttl, properties.getTokenAudience());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", token);
        body.put("token_type", "Bearer");
        body.put("expires_in", ttl);
        body.put("scope", scope);
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache")
                .body(body);
    }

    /** 常量时间比较，避免用响应时间逐字节猜密钥。 */
    private static boolean secretMatches(String expected, String provided) {
        if (provided == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> split(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,\\s]+")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code,
                                                              String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("error_description", description);
        return ResponseEntity.status(status).body(body);
    }
}
