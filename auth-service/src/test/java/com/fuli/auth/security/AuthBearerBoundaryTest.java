package com.fuli.auth.security;

import com.fuli.common.api.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@SpringJUnitConfig(classes = {JwtAuthenticationFilter.class, AuthBearerBoundaryTest.Config.class})
class AuthBearerBoundaryTest {
    private static final String SECRET = "auth-boundary-test-012345678901234567890123456789";
    @Autowired JwtAuthenticationFilter filter;

    @Configuration
    static class Config {
        @Bean JwtProperties properties() {
            JwtProperties p = new JwtProperties(); p.setSecret(SECRET); return p;
        }
    }

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void directForgedHeaderCannotAuthenticate() throws Exception {
        var request = new MockHttpServletRequest("GET", "/auth/profile");
        request.addHeader("X-User-Id", "2");
        var response = new MockHttpServletResponse();
        AtomicBoolean forwarded = new AtomicBoolean();
        filter.doFilter(request, response, (req, res) -> forwarded.set(true));
        assertEquals(401, response.getStatus());
        assertFalse(forwarded.get());
    }

    @Test
    void bearerIdentityOverridesForgedUserHeader() throws Exception {
        var request = new MockHttpServletRequest("GET", "/auth/profile");
        request.addHeader("X-User-Id", "2");
        request.addHeader("Authorization", "Bearer " + Jwts.builder().subject("alice").claim("userId", 1L)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact());
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) ->
                assertEquals("1", SecurityContextHolder.getContext().getAuthentication().getDetails().toString()));
        assertEquals(200, response.getStatus());
    }
}
