package com.fuli.trade.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises the real MVC entry point and all registered servlet filters. */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false"})
@ActiveProfiles("test")
class TradeAccessIsolationTest {
    @Autowired WebApplicationContext context;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.fuli.common.api.feign.DataFeignClient dataFeignClient;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(dataFeignClient.getStockInfo(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.Map.of("market", "US"));
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBeansOfType(Filter.class).values().toArray(Filter[]::new))
                .build();
    }

    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired com.fuli.trade.config.InternalKeyFeignInterceptor internalKeyInterceptor;

    @Test
    void createAlwaysUsesVerifiedIdentityInsteadOfBodyIdentity() throws Exception {
        var service = org.mockito.Mockito.mock(com.fuli.trade.service.TradeRecordService.class);
        var verifiedRequest = new org.springframework.mock.web.MockHttpServletRequest();
        var properties = new com.fuli.common.api.config.JwtProperties();
        properties.setSecret("test-jwt-secret-key-01234567890123456789012345");
        var controllerMvc = MockMvcBuilders.standaloneSetup(new TradeRecordController(service, verifiedRequest))
                .addFilters(new com.fuli.trade.security.TradeAuthenticationFilter(properties), (req, res, chain) -> {
                    java.util.Collections.list(req.getAttributeNames()).forEach(name ->
                            verifiedRequest.setAttribute(name, req.getAttribute(name)));
                    chain.doFilter(req, res);
                }).build();
        org.mockito.Mockito.when(service.createTrade(org.mockito.ArgumentMatchers.any())).thenReturn(42L);
        controllerMvc.perform(post("/trade").header("Authorization", bearer(1)).header("X-User-Id", "2").contentType("application/json")
                        .content("{\"userId\":2,\"stockCode\":\"VICTIM\"}"))
                .andExpect(status().isOk());
        var argument = org.mockito.ArgumentCaptor.forClass(com.fuli.common.api.dto.TradeDTO.class);
        org.mockito.Mockito.verify(service).createTrade(argument.capture());
        org.junit.jupiter.api.Assertions.assertEquals(1L, argument.getValue().getUserId());
    }

    @Test
    void allPublicQueriesAreScopedToBearerIdentity() throws Exception {
        jdbc.update("DELETE FROM trade_record");
        jdbc.update("INSERT INTO trade_record(user_id,stock_code,stock_name,trade_type,trade_price,trade_quantity,trade_amount,total_cost,trade_date) VALUES (2,'VICTIM','victim',1,10,100,1000,1000,'2026-01-05')");
        for (String path : new String[]{"/trade/list", "/trade/page", "/trade/queryByCondition", "/trade/statistics"}) {
            String body = "{\"userId\":2}";
            var response = mvc.perform(post(path).header("Authorization", bearer(1)).header("X-User-Id", "2")
                            .contentType("application/json").content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            var json = com.alibaba.fastjson2.JSON.parseObject(response);
            org.junit.jupiter.api.Assertions.assertEquals(200, json.getIntValue("code"), path);
            if (path.endsWith("statistics")) {
                org.junit.jupiter.api.Assertions.assertEquals(0, json.getJSONObject("data").getIntValue("totalTrades"), path);
            } else if (path.endsWith("page")) {
                org.junit.jupiter.api.Assertions.assertTrue(json.getJSONObject("data").getJSONArray("records").isEmpty(), path);
            } else {
                org.junit.jupiter.api.Assertions.assertTrue(json.getJSONArray("data").isEmpty(), path);
            }
        }
    }

    @Test
    void originalBearerIsRequiredOnEveryPrivateRoute() throws Exception {
        for (String path : new String[]{"/trade", "/trade/list", "/trade/page", "/trade/queryByCondition", "/trade/statistics", "/stock/drawing"}) {
            mvc.perform(post(path).header("X-User-Id", "2").header("X-Internal-Key", "test-internal-key-0123456789")
                            .contentType("application/json").content("{\"userId\":2}"))
                    .andExpect(status().isUnauthorized());
        }
        for (String path : new String[]{"/stock/holding", "/stock/available-quantity", "/stock/fix-holding", "/stock/drawing", "/trade/9001"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                            .header("X-User-Id", "2").param("userId", "2").param("stockCode", "VICTIM").param("code", "VICTIM"))
                    .andExpect(status().isUnauthorized());
        }
        for (String token : new String[]{"Bearer invalid", bearer(1) + "tampered"}) {
            mvc.perform(post("/trade/list").header("Authorization", token).header("X-User-Id", "2")
                            .contentType("application/json").content("{\"userId\":2}"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Test
    void internalFeignContractsAndSharedKeyReachRealTradeQueriesWithoutBearer() throws Exception {
        jdbc.update("DELETE FROM trade_record");
        jdbc.update("INSERT INTO trade_record(user_id,stock_code,stock_name,trade_type,trade_price,trade_quantity,trade_amount,total_cost,trade_date) VALUES (2,'VICTIM','victim',1,10,100,1000,1000,'2026-01-05')");
        var contract = new org.springframework.cloud.openfeign.support.SpringMvcContract();
        for (var metadata : contract.parseAndValidateMetadata(com.fuli.common.api.feign.TradeFeignClient.class)) {
            if (!metadata.configKey().contains("#getStatistics(") && !metadata.configKey().contains("#queryByCondition(")) continue;
            var template = metadata.template();
            internalKeyInterceptor.apply(template);
            var request = post(template.path()).contentType("application/json").content("{\"userId\":2}");
            template.headers().forEach((name, values) -> request.header(name, values.toArray()));
            var json = com.alibaba.fastjson2.JSON.parseObject(mvc.perform(request).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            org.junit.jupiter.api.Assertions.assertEquals(200, json.getIntValue("code"), template.path());
            if (metadata.configKey().contains("#getStatistics(")) {
                org.junit.jupiter.api.Assertions.assertEquals(1, json.getJSONObject("data").getIntValue("totalTrades"));
            } else {
                org.junit.jupiter.api.Assertions.assertEquals(2L, json.getJSONArray("data").getJSONObject(0).getLongValue("userId"));
            }
            mvc.perform(post(template.path()).contentType("application/json").content("{\"userId\":2}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post(template.path()).header("X-Internal-Key", "test-internal-key-0123456789")
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    private String bearer(long userId) {
        return "Bearer " + io.jsonwebtoken.Jwts.builder().subject("user-" + userId).claim("userId", userId)
                .expiration(java.util.Date.from(java.time.Instant.now().plusSeconds(600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-jwt-secret-key-01234567890123456789012345".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .compact();
    }

    @Test
    void bodyUserIdCannotReadAnotherUsersTrades() throws Exception {
        jdbc.update("DELETE FROM trade_record");
        jdbc.update("INSERT INTO trade_record(user_id,stock_code,stock_name,trade_type,trade_price,trade_quantity,trade_amount,total_cost,trade_date) VALUES (2,'VICTIM','victim',1,10,100,1000,1000,'2026-01-05')");
        mvc.perform(post("/trade/list").header("Authorization", bearer(1)).header("X-User-Id", "2")
                        .contentType("application/json").content("{\"userId\":2}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.length()").value(0));
    }

    @Test
    void anotherUsersTradeCannotBeReadUpdatedOrDeletedById() throws Exception {
        jdbc.update("DELETE FROM trade_record");
        jdbc.update("INSERT INTO trade_record(id,user_id,stock_code,stock_name,trade_type,trade_price,trade_quantity,trade_amount,total_cost,trade_date) VALUES (9001,2,'VICTIM','victim',1,10,100,1000,1000,'2026-01-05')");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/trade/9001")
                        .header("Authorization", bearer(1))).andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/trade/9001")
                        .header("Authorization", bearer(1)).contentType("application/json")
                        .content("{\"userId\":1,\"stockCode\":\"VICTIM\",\"tradeQuantity\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/trade/9001")
                        .header("Authorization", bearer(1))).andExpect(status().isForbidden());
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM trade_record WHERE id=9001 AND deleted=0 AND user_id=2", Integer.class));
    }

    @Test
    void matrixPathCannotBypassInternalAuthentication() throws Exception {
        mvc.perform(post("/trade/internal;v=1/queryByCondition").header("Authorization", bearer(1))
                        .contentType("application/json").content("{\"userId\":2}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void internalClearAllRejectsMissingOrWrongKeyEvenWithUserBearer() throws Exception {
        for (String key : new String[]{"", "wrong-key"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/trade/internal/clearAll")
                            .header("Authorization", bearer(1)).header("X-Internal-Key", key).param("userId", "2"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void holdingsAndDrawingIgnoreCallerUserId() throws Exception {
        jdbc.update("DELETE FROM position_summary");
        jdbc.update("INSERT INTO position_summary(user_id,stock_code,stock_name,total_quantity,avg_cost) VALUES (2,'VICTIM','victim',321,10)");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/stock/holding")
                        .header("Authorization", bearer(1)).param("userId", "2").param("stockCode", "VICTIM"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.holdingQuantity").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/stock/available-quantity")
                        .header("Authorization", bearer(1)).param("userId", "2").param("stockCode", "VICTIM"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.totalQuantity").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/stock/fix-holding")
                        .header("Authorization", bearer(1)).param("userId", "2"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(321, jdbc.queryForObject(
                "SELECT total_quantity FROM position_summary WHERE user_id=2 AND stock_code='VICTIM'", Integer.class));

        jdbc.execute("CREATE TABLE IF NOT EXISTS chart_drawing(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,stock_code VARCHAR(32),period VARCHAR(16),drawing_data TEXT,create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.update("DELETE FROM chart_drawing");
        jdbc.update("INSERT INTO chart_drawing(user_id,stock_code,period,drawing_data) VALUES (2,'VICTIM','1D','victim-secret')");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/stock/drawing")
                        .header("Authorization", bearer(1)).param("userId", "2").param("code", "VICTIM"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.data").value("[]"));
        mvc.perform(post("/stock/drawing").header("Authorization", bearer(1)).contentType("application/json")
                        .content("{\"userId\":2,\"stockCode\":\"VICTIM\",\"period\":\"1D\",\"data\":\"[]\"}"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals("victim-secret", jdbc.queryForObject(
                "SELECT drawing_data FROM chart_drawing WHERE user_id=2", String.class));
    }

    @Test
    void forgedUserHeaderWithoutBearerCannotReadTrades() throws Exception {
        mvc.perform(post("/trade/list").header("X-User-Id", "2")
                        .contentType("application/json").content("{\"userId\":2}"))
                .andExpect(status().isUnauthorized());
    }
}
