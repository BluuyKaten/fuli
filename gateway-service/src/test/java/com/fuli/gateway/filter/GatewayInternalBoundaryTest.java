package com.fuli.gateway.filter;

import com.fuli.common.api.config.JwtProperties;
import com.fuli.gateway.util.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class GatewayInternalBoundaryTest {
    private static final String SECRET = "gateway-test-jwt-012345678901234567890123456789";
    private final JwtGlobalFilter filter = new JwtGlobalFilter(new JwtUtil(properties()), mock(ObjectProvider.class));

    private static JwtProperties properties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        return properties;
    }

    private static String token() {
        return Jwts.builder().subject("alice").claim("userId", 1L)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    @Test
    void externalInternalRouteIsForbiddenEvenWithBearerAndInternalKey() {
        for (String path : new String[]{"/api/trade/internal/clearAll", "/api/auth/internal/userCash",
                "/api/trade/%69nternal/queryByCondition", "/api/trade/internal;v=1/clearAll"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path)
                    .header("Authorization", "Bearer " + token()).header("X-Internal-Key", "caller-supplied-key"));
            AtomicBoolean forwarded = new AtomicBoolean();
            filter.filter(exchange, next -> { forwarded.set(true); return Mono.empty(); }).block();
            assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode(), path);
            assertFalse(forwarded.get(), path);
        }
    }

    @Test
    void forwardedRequestsNeverCarryCallerInternalCredentials() {
        for (String path : new String[]{"/api/trade/list", "/api/auth/login"}) {
            var exchange = MockServerWebExchange.from(MockServerHttpRequest.post(path)
                    .header("Authorization", "Bearer " + token()).header("X-Internal-Key", "caller-supplied-key")
                    .header("X-User-Id", "999"));
            filter.filter(exchange, next -> {
                assertNull(next.getRequest().getHeaders().getFirst("X-Internal-Key"));
                assertNotEquals("999", next.getRequest().getHeaders().getFirst("X-User-Id"));
                return Mono.empty();
            }).block();
        }
    }
}
