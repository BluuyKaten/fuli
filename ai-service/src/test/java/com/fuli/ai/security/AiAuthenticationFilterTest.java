package com.fuli.ai.security;

import com.fuli.ai.config.AiAuthProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ai-service 的身份边界（ADR-0010 决策 4）。
 *
 * <p>三条：无令牌不可达、服务令牌不能当用户令牌用、缺密钥时 fail-closed 但健康检查仍可探活。
 */
class AiAuthenticationFilterTest {

    private static final String SECRET = "test-jwt-secret-key-01234567890123456789012345";

    private static MockMvc mvc(String secret) {
        AiAuthProperties properties = new AiAuthProperties();
        properties.setJwtSecret(secret);
        return MockMvcBuilders.standaloneSetup(new Probe())
                .addFilters(new AiAuthenticationFilter(properties))
                .build();
    }

    private static String userToken() {
        return "Bearer " + Jwts.builder().subject("user-1").claim("userId", 1L)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    private static String serviceToken() {
        return "Bearer " + Jwts.builder().subject("ai-service").claim("type", "SERVICE")
                .claim("scope", "orders:read")
                .audience().add("fuli-data").and()
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void userTokenIsAccepted() throws Exception {
        mvc(SECRET).perform(get("/ai/ask").header("Authorization", userToken()))
                .andExpect(status().isOk());
    }

    @Test
    void noTokenOrForgedHeaderIsRejected() throws Exception {
        mvc(SECRET).perform(get("/ai/ask")).andExpect(status().isUnauthorized());
        mvc(SECRET).perform(get("/ai/ask").header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized());
        mvc(SECRET).perform(get("/ai/ask").header("Authorization", "Bearer broken"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("服务令牌不能调用用户端点：它不是任何一个用户")
    void serviceTokenCannotCallUserEndpoints() throws Exception {
        mvc(SECRET).perform(get("/ai/ask").header("Authorization", serviceToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("缺密钥：业务端点 503，健康检查仍可探活")
    void missingSecretFailsClosedButHealthStaysUp() throws Exception {
        mvc("").perform(get("/ai/ask").header("Authorization", userToken()))
                .andExpect(status().isServiceUnavailable());
        mvc("").perform(get("/ai/health")).andExpect(status().isOk());
    }

    @RestController
    static class Probe {
        @GetMapping("/ai/ask")
        public Map<String, Object> ask() {
            return Map.of("ok", true);
        }

        @GetMapping("/ai/health")
        public Map<String, Object> health() {
            return Map.of("status", "UP");
        }
    }
}
