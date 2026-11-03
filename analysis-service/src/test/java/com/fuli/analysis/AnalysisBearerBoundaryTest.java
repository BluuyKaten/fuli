package com.fuli.analysis;

import com.fuli.analysis.controller.AnalysisController;
import com.fuli.analysis.service.AnalysisService;
import com.fuli.analysis.service.DashboardService;
import com.fuli.common.api.config.JwtProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AnalysisBearerBoundaryTest {
    @Test
    void directForgedHeaderCannotReachTrustedInternalQueries() throws Exception {
        var service = mock(AnalysisService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new AnalysisController(service, mock(DashboardService.class),
                new MockHttpServletRequest())).build();
        mvc.perform(get("/analysis/statistics").header("X-User-Id", "2"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void verifiedBearerOverridesSpoofedHeaderBeforeInternalQueries() throws Exception {
        var service = mock(AnalysisService.class);
        var request = new MockHttpServletRequest();
        JwtProperties properties = new JwtProperties();
        String secret = "analysis-test-jwt-012345678901234567890123456789";
        properties.setSecret(secret);
        var mvc = MockMvcBuilders.standaloneSetup(new AnalysisController(service, mock(DashboardService.class), request))
                .addFilters(new com.fuli.analysis.security.AnalysisAuthenticationFilter(properties), (req, res, chain) -> {
                    java.util.Collections.list(req.getAttributeNames()).forEach(name ->
                            request.setAttribute(name, req.getAttribute(name)));
                    chain.doFilter(req, res);
                }).build();
        String token = io.jsonwebtoken.Jwts.builder().subject("alice").claim("userId", 1L)
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        mvc.perform(get("/analysis/statistics").header("Authorization", "Bearer " + token).header("X-User-Id", "2"))
                .andExpect(status().isOk());
        verify(service).getStatistics(1L, null, null, null);
    }

    @Test
    void analysisFeignContractsInjectSharedKeyOnlyForServiceEndpoints() {
        var properties = new com.fuli.common.api.config.InternalKeyProperties();
        properties.setInternalKey("analysis-internal-test-0123456789");
        var interceptor = new com.fuli.analysis.config.InternalKeyFeignInterceptor(properties);
        var contract = new org.springframework.cloud.openfeign.support.SpringMvcContract();
        for (Class<?> client : new Class<?>[]{com.fuli.common.api.feign.TradeFeignClient.class,
                com.fuli.common.api.feign.AuthFeignClient.class}) {
            for (var metadata : contract.parseAndValidateMetadata(client)) {
                var template = metadata.template();
                interceptor.apply(template);
                if (metadata.configKey().contains("#getStatistics(") || metadata.configKey().contains("#queryByCondition(")) {
                    org.junit.jupiter.api.Assertions.assertTrue(template.path().startsWith("/trade/internal/"));
                }
                if (template.path().contains("/internal/")) {
                    org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(properties.getInternalKey()),
                            java.util.List.copyOf(template.headers().get("X-Internal-Key")));
                    org.junit.jupiter.api.Assertions.assertFalse(template.headers().containsKey("X-User-Id"));
                } else {
                    org.junit.jupiter.api.Assertions.assertFalse(template.headers().containsKey("X-Internal-Key"));
                }
            }
        }
    }
}
