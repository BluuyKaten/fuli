package com.fuli.data.security;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fuli.data.config.OkxAuthProperties;
import com.fuli.data.controller.OkxController;
import com.fuli.data.okx.OkxAccountController;
import com.fuli.data.config.OkxConfig;
import com.fuli.data.okx.OkxAccountIdentity;
import com.fuli.data.okx.OkxAccountView;
import com.fuli.data.okx.OkxCredentialService;
import com.fuli.data.okx.OkxMarketService;
import com.fuli.data.okx.OkxTradeService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OKX 端点鉴权边界测试（ADR-0009 决策 8 / ADR-0010 决策 2、3、4）。
 *
 * <p>用 standaloneSetup 走真实 MVC 入口 + 真实过滤器，不启动 Spring 上下文、不依赖数据库。
 * 重点验证：无令牌不可达、伪造身份头无效、用户级权限默认拒绝、
 * 服务令牌只能读、错误受众的令牌不被接受、缺密钥时 fail-closed。
 */
class OkxAuthenticationTest {

    private static final String SECRET = "test-jwt-secret-key-01234567890123456789012345";

    private static final String AUDIENCE = "fuli-data";

    private OkxMarketService marketService;

    private OkxTradeService tradeService;

    private OkxCredentialService credentialService;

    private OkxTradePermissionClient permission;

    private OkxAuditService audit;

    @BeforeEach
    void setUp() {
        marketService = mock(OkxMarketService.class);
        tradeService = mock(OkxTradeService.class);
        credentialService = mock(OkxCredentialService.class);
        permission = mock(OkxTradePermissionClient.class);
        audit = mock(OkxAuditService.class);
        when(marketService.configuredSymbols()).thenReturn(List.of());
        when(marketService.syncConfigured()).thenReturn(0);
        when(tradeService.status()).thenReturn(Map.of());
        when(tradeService.balance(anyLong(), any())).thenReturn(JsonNodeFactory.instance.arrayNode());
        when(tradeService.pendingOrders(anyLong(), any())).thenReturn(JsonNodeFactory.instance.arrayNode());
        when(credentialService.hasEnabled(anyLong())).thenReturn(true);
        when(credentialService.list(anyLong())).thenReturn(List.of());
    }

    private MockMvc mvc(String secret) {
        return mvc(secret, false);
    }

    private MockMvc mvc(String secret, boolean tradeEnabled) {
        return mvc(secret, tradeEnabled, 42L);
    }

    private MockMvc mvc(String secret, boolean tradeEnabled, long agentOwnerUserId) {
        OkxAuthProperties properties = new OkxAuthProperties();
        properties.setJwtSecret(secret);
        properties.setServiceTokenAudience(AUDIENCE);
        when(permission.isTradeEnabled(anyLong())).thenReturn(tradeEnabled);
        OkxAuthz authz = new OkxAuthz(permission, audit, properties);
        OkxConfig okxConfig = new OkxConfig();
        okxConfig.setAgentOwnerUserId(agentOwnerUserId);
        OkxController controller = new OkxController(marketService, tradeService,
                credentialService, new OkxAccountIdentity(okxConfig), authz);
        OkxAccountController accountController = new OkxAccountController(credentialService, authz);
        return MockMvcBuilders.standaloneSetup(controller, accountController, new PublicProbe())
                .addFilters(new OkxAuthenticationFilter(properties))
                .build();
    }

    @Test
    void noTokenIsRejectedOnEveryOkxEndpoint() throws Exception {
        MockMvc mvc = mvc(SECRET);
        for (String path : new String[]{"/data/okx/status", "/data/okx/trade/balance",
                "/data/okx/trade/config", "/data/okx/trade/positions",
                "/data/okx/trade/orders-pending", "/data/okx/account"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        for (String path : new String[]{"/data/okx/sync/instruments", "/data/okx/sync/all",
                "/data/okx/sync/candles", "/data/okx/backfill", "/data/okx/trade/order",
                "/data/okx/trade/cancel", "/data/okx/account"}) {
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void forgedUserHeaderAndInternalKeyCannotBypass() throws Exception {
        MockMvc mvc = mvc(SECRET, true);
        mvc.perform(get("/data/okx/status").header("X-User-Id", "1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/data/okx/status").header("X-Internal-Key", "whatever"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/data/okx/status").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/data/okx/status").header("Authorization", bearer(1) + "tampered"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedReadIsAllowed() throws Exception {
        mvc(SECRET).perform(get("/data/okx/trade/balance").header("Authorization", bearer(1)))
                .andExpect(status().isOk());
        mvc(SECRET).perform(get("/data/okx/status").header("Authorization", bearer(1)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("没被授予 okx_trade_enabled 的用户：写操作 403（不再是环境变量白名单）")
    void writeScopeIsDeniedWhenUserHasNoTradePermission() throws Exception {
        mvc(SECRET, false).perform(post("/data/okx/sync/all")
                        .header("Authorization", bearer(1))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeScopeIsAllowedOnlyWhenPermissionIsGranted() throws Exception {
        mvc(SECRET, true).perform(post("/data/okx/sync/all")
                        .header("Authorization", bearer(1))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("伪造身份头不能提权：权限是按令牌里的 userId 查的")
    void forgedHeaderCannotEscalateWriteScope() throws Exception {
        MockMvc mvc = mvc(SECRET, false);
        mvc.perform(post("/data/okx/trade/order")
                        .header("Authorization", bearer(1))
                        .header("X-User-Id", "99")
                        .contentType("application/json")
                        .content("{\"symbol\":\"BTC-USDT.CRYPTO\",\"side\":\"buy\",\"ordType\":\"market\",\"sz\":\"0.001\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("服务令牌：带 orders:read 可读")
    void serviceTokenCanRead() throws Exception {
        mvc(SECRET).perform(get("/data/okx/trade/orders-pending")
                        .header("Authorization", serviceToken(AUDIENCE, "orders:read")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("服务令牌读私有端点：需要配置代理账户身份，否则明确 400 而不是给空数据")
    void serviceTokenReadWithoutAgentOwnerIsRejected() throws Exception {
        mvc(SECRET, false, 0L).perform(get("/data/okx/trade/orders-pending")
                        .header("Authorization", serviceToken(AUDIENCE, "orders:read")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("服务令牌：写操作一律 403（Agent 不下单是协议级约束）")
    void serviceTokenCanNeverWrite() throws Exception {
        mvc(SECRET, true).perform(post("/data/okx/trade/order")
                        .header("Authorization", serviceToken(AUDIENCE, "orders:read,orders:write"))
                        .contentType("application/json")
                        .content("{\"symbol\":\"BTC-USDT.CRYPTO\",\"side\":\"buy\",\"ordType\":\"market\",\"sz\":\"0.001\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("服务令牌：受众不符 → 401（禁止 token passthrough）")
    void serviceTokenWithWrongAudienceIsRejected() throws Exception {
        mvc(SECRET).perform(get("/data/okx/trade/balance")
                        .header("Authorization", serviceToken("another-service", "orders:read")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("服务令牌：没有 orders:read 时读也被拒")
    void serviceTokenWithoutReadScopeIsDenied() throws Exception {
        mvc(SECRET).perform(get("/data/okx/trade/balance")
                        .header("Authorization", serviceToken(AUDIENCE, "orders:suggest")))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("服务令牌不能读写凭证接口")
    void serviceTokenCannotTouchCredentials() throws Exception {
        mvc(SECRET).perform(get("/data/okx/account")
                        .header("Authorization", serviceToken(AUDIENCE, "orders:read")))
                .andExpect(status().isForbidden());
        mvc(SECRET).perform(post("/data/okx/account")
                        .header("Authorization", serviceToken(AUDIENCE, "orders:read"))
                        .contentType("application/json")
                        .content("{\"label\":\"x\",\"apiKey\":\"k\",\"secretKey\":\"s\",\"passphrase\":\"p\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("凭证录入不需要交易权限（否则先配 Key 再授权就成了死锁）")
    void credentialWriteOnlyNeedsIdentity() throws Exception {
        when(credentialService.save(anyLong(), any())).thenReturn(
                new OkxAccountView("默认", "abcd****wxyz", true, 1, null, null));
        mvc(SECRET, false).perform(post("/data/okx/account")
                        .header("Authorization", bearer(1))
                        .contentType("application/json")
                        .content("{\"label\":\"默认\",\"apiKey\":\"AKID1234567890\",\"secretKey\":\"s\",\"passphrase\":\"p\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("凭证录入的审计 scope 是 orders:credential，不能混进交易审计")
    void credentialSaveIsAuditedUnderCredentialScope() throws Exception {
        when(credentialService.save(anyLong(), any())).thenReturn(
                new OkxAccountView("默认", "abcd****wxyz", true, 1, null, null));
        mvc(SECRET, false).perform(post("/data/okx/account")
                .header("Authorization", bearer(1))
                .contentType("application/json")
                .content("{\"label\":\"默认\",\"apiKey\":\"AKID1234567890\",\"secretKey\":\"s\",\"passphrase\":\"p\"}"));

        org.mockito.ArgumentCaptor<OkxAuditService.Event> captor =
                org.mockito.ArgumentCaptor.forClass(OkxAuditService.Event.class);
        org.mockito.Mockito.verify(audit, org.mockito.Mockito.atLeastOnce()).record(captor.capture());
        boolean successAudited = captor.getAllValues().stream()
                .anyMatch(e -> "orders:credential".equals(e.scope())
                        && "SUCCESS".equals(e.outcome())
                        && "saveOkxAccount".equals(e.action()));
        org.junit.jupiter.api.Assertions.assertTrue(successAudited,
                "凭证类操作必须记在 orders:credential 下，否则按 scope 统计会失真");
    }

    @Test
    void futureMcpPathsAreProtectedInAdvance() throws Exception {
        MockMvc mvc = mvc(SECRET);
        for (String path : new String[]{"/okx/mcp", "/okx/mcp/", "/mcp", "/mcp/message"}) {
            mvc.perform(post(path).contentType("application/json")
                            .content("{\"jsonrpc\":\"2.0\",\"method\":\"tools/list\"}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void nonOkxPathsAreUnaffected() throws Exception {
        mvc(SECRET).perform(get("/market/bars")).andExpect(status().isOk());
        mvc("").perform(get("/market/bars")).andExpect(status().isOk());
    }

    @Test
    void missingOrWeakSecretFailsClosedWithoutTakingDownOtherEndpoints() throws Exception {
        for (String secret : new String[]{"", "too-short"}) {
            MockMvc mvc = mvc(secret, true);
            mvc.perform(get("/data/okx/status").header("Authorization", bearer(1)))
                    .andExpect(status().isServiceUnavailable());
            mvc.perform(get("/market/bars")).andExpect(status().isOk());
        }
    }

    private static String bearer(long userId) {
        return "Bearer " + Jwts.builder().subject("user-" + userId).claim("userId", userId)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    static String serviceToken(String audience, String scope) {
        return "Bearer " + Jwts.builder()
                .subject("ai-service")
                .claim("type", "SERVICE")
                .claim("scope", scope)
                .audience().add(audience).and()
                .expiration(Date.from(Instant.now().plusSeconds(900)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }

    /** 站在一个与 OKX 无关的公共端点，验证过滤器没有扩大伤害面。 */
    @RestController
    static class PublicProbe {
        @GetMapping("/market/bars")
        public Map<String, Object> bars() {
            return Map.of("ok", true);
        }
    }
}
