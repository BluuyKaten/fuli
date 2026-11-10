package com.fuli.auth.controller;

import com.fuli.auth.config.ServiceClientProperties;
import com.fuli.auth.util.JwtUtil;
import com.fuli.common.api.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * client_credentials 令牌签发测试（ADR-0010 决策 4）。
 *
 * <p>验证的是「Agent 只读」这条边界能不能在协议层立住：
 * 令牌必须是 {@code type=SERVICE}、{@code scope=orders:read}、15 分钟过期、
 * 受众固定；并且不能靠改请求参数把 scope 撑大。
 */
class ServiceTokenControllerTest {

    private static final String SECRET = "test-jwt-secret-key-01234567890123456789012345";

    private static final String CLIENT_SECRET = "ai-service-secret-0123456789";

    private ServiceTokenController controller;

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        JwtProperties jwtProperties = new JwtProperties();
        jwtProperties.setSecret(SECRET);
        jwtUtil = new JwtUtil(jwtProperties);

        ServiceClientProperties properties = new ServiceClientProperties();
        ServiceClientProperties.Client client = new ServiceClientProperties.Client();
        client.setSecret(CLIENT_SECRET);
        client.setScopes("orders:read");
        properties.getClients().put("ai-service", client);
        controller = new ServiceTokenController(properties, jwtUtil);
    }

    private static Map<String, String> form(String grant, String clientId, String secret, String scope) {
        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
        form.put("grant_type", grant);
        form.put("client_id", clientId);
        form.put("client_secret", secret);
        if (scope != null) {
            form.put("scope", scope);
        }
        return form;
    }

    @Test
    @DisplayName("签发：type=SERVICE / scope=orders:read / 15 分钟 / 受众正确，且没有 userId")
    void issuesReadOnlyServiceToken() {
        ResponseEntity<Map<String, Object>> response = controller.token(
                form("client_credentials", "ai-service", CLIENT_SECRET, null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Bearer", response.getBody().get("token_type"));
        assertEquals(900L, response.getBody().get("expires_in"));
        assertEquals("orders:read", response.getBody().get("scope"));

        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims((String) response.getBody().get("access_token")).getPayload();
        assertEquals("SERVICE", claims.get("type", String.class));
        assertEquals("orders:read", claims.get("scope", String.class));
        assertEquals("ai-service", claims.getSubject());
        assertTrue(claims.getAudience().contains("fuli-data"));
        assertNull(claims.get("userId"), "服务令牌不能携带用户身份，否则等于借用了某个人的权限");
    }

    @Test
    @DisplayName("有效期就是 15 分钟（泄露后的重放窗口要足够小）")
    void tokenExpiresInFifteenMinutes() {
        ResponseEntity<Map<String, Object>> response = controller.token(
                form("client_credentials", "ai-service", CLIENT_SECRET, "orders:read"));
        Claims claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .build().parseSignedClaims((String) response.getBody().get("access_token")).getPayload();
        long ttlSeconds = (claims.getExpiration().getTime() - claims.getIssuedAt().getTime()) / 1000;
        assertEquals(900L, ttlSeconds);
    }

    @Test
    @DisplayName("scope 只能收窄：申请 orders:write 被拒")
    void cannotEscalateScope() {
        ResponseEntity<Map<String, Object>> response = controller.token(
                form("client_credentials", "ai-service", CLIENT_SECRET, "orders:read orders:write"));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("invalid_scope", response.getBody().get("error"));
    }

    @Test
    @DisplayName("密钥错 / 客户端不存在：统一 invalid_client，不区分原因")
    void invalidClientIsIndistinguishable() {
        for (String[] pair : new String[][]{
                {"ai-service", "wrong-secret"},
                {"unknown-service", CLIENT_SECRET},
                {"ai-service", ""}}) {
            ResponseEntity<Map<String, Object>> response = controller.token(
                    form("client_credentials", pair[0], pair[1], null));
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
            assertEquals("invalid_client", response.getBody().get("error"));
        }
    }

    @Test
    @DisplayName("只支持 client_credentials：password 授权一律拒绝")
    void onlyClientCredentialsGrantIsSupported() {
        ResponseEntity<Map<String, Object>> response = controller.token(
                form("password", "ai-service", CLIENT_SECRET, null));
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("unsupported_grant_type", response.getBody().get("error"));
    }

    @Test
    @DisplayName("客户端被停用：即使密钥正确也签不出票")
    void disabledClientIsRejected() {
        ServiceClientProperties properties = new ServiceClientProperties();
        ServiceClientProperties.Client client = new ServiceClientProperties.Client();
        client.setSecret(CLIENT_SECRET);
        client.setEnabled(false);
        properties.getClients().put("ai-service", client);
        ServiceTokenController disabled = new ServiceTokenController(properties, jwtUtil);

        ResponseEntity<Map<String, Object>> response = disabled.token(
                form("client_credentials", "ai-service", CLIENT_SECRET, null));

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertFalse(response.getBody().containsKey("access_token"));
        assertNotNull(response.getBody().get("error"));
    }
}
