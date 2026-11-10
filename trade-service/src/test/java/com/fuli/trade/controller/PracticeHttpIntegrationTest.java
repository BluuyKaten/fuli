package com.fuli.trade.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fuli.common.api.feign.DataFeignClient;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP, identity filters, database persistence and fixed hand-calculated market fixtures. */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false"})
@ActiveProfiles("test")
class PracticeHttpIntegrationTest {
    private static final String BASE = "/trade/practice";
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DataFeignClient market;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBeansOfType(Filter.class).values().toArray(Filter[]::new)).build();
        jdbc.update("DELETE FROM practice_session");
        when(market.getDailyData(anyString(), nullable(String.class), nullable(String.class), nullable(Integer.class)))
                .thenReturn(prices());
    }

    @Test
    void bearerIdentityProtectsEveryPracticeReadAndMutation() throws Exception {
        var created = create();
        String id = created.getString("id");
        for (var request : List.of(get(BASE), get(BASE + "/" + id), post(BASE),
                post(BASE + "/" + id + "/orders"), post(BASE + "/" + id + "/advance"),
                post(BASE + "/" + id + "/finish"), post(BASE + "/" + id + "/cancel-order"),
                put(BASE + "/" + id + "/annotations"))) {
            mvc.perform(request.header("X-User-Id", "1").header("X-Internal-Key", "test-internal-key-0123456789")
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(get(BASE + "/" + id).header("Authorization", bearer(2)).param("userId", "1"))
                .andExpect(status().isNotFound());
        for (String action : List.of("orders", "advance", "finish", "cancel-order")) {
            mvc.perform(post(BASE + "/" + id + "/" + action).header("Authorization", bearer(2))
                            .contentType("application/json").content("{\"expectedVersion\":0,\"side\":\"BUY\",\"quantity\":100}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(put(BASE + "/" + id + "/annotations").header("Authorization", bearer(2))
                        .contentType("application/json").content("{\"expectedVersion\":0,\"annotations\":[]}"))
                .andExpect(status().isNotFound());
        assertEquals(0, response(get(BASE).header("Authorization", bearer(2))).getJSONArray("data").size());
    }

    @Test
    void activeResponsesExposeOnlyKnownPricesAndSnapshotSurvivesMarketChanges() throws Exception {
        var initial = create();
        assertEquals("600519.SH", initial.getString("stockCode"));
        assertKnownPrefix(initial, "2026-01-05");
        assertEquals(1, initial.getJSONArray("bars").size());
        var forged = data(get(BASE + "/" + initial.getString("id")).header("Authorization", bearer(1))
                .param("userId", "2").param("cursor", "999").param("endDate", "2099-12-31").param("stockCode", "OTHER"));
        assertKnownPrefix(forged, "2026-01-05");
        assertEquals(initial.getJSONArray("bars"), forged.getJSONArray("bars"));
        assertEquals("600519.SH", forged.getString("stockCode"));
        var pending = order(initial, "BUY", 100);
        assertKnownPrefix(pending, "2026-01-05");
        assertFalse(pending.getJSONObject("pendingOrder").containsKey("price"));
        when(market.getDailyData(anyString(), nullable(String.class), nullable(String.class), nullable(Integer.class)))
                .thenThrow(new IllegalStateException("market must never be queried after creation"));
        var next = mutate(pending, "advance");
        assertKnownPrefix(next, "2026-01-06");
        money("11", next.getJSONArray("fills").getJSONObject(0), "price");
        var restored = view(next.getString("id"));
        assertEquals(next.getJSONArray("bars"), restored.getJSONArray("bars"));
        assertEquals(next.getJSONArray("fills"), restored.getJSONArray("fills"));
        assertEquals(next.getLongValue("version"), restored.getLongValue("version"));
        verify(market, times(1)).getDailyData(anyString(), nullable(String.class), nullable(String.class), nullable(Integer.class));
        String persisted = jdbc.queryForObject("SELECT snapshot_json FROM practice_session WHERE id=?", String.class, next.getString("id"));
        assertNotNull(persisted);
        assertTrue(persisted.contains("2026-01-08"));
    }

    @Test
    void nextOpenBuyAndNextDaySellMatchHandCalculatedFeesWithoutTouchingRealAccounts() throws Exception {
        int tradesBefore = count("trade_record");
        int positionsBefore = count("position_summary");
        int messagesBefore = count("local_message");
        var bought = mutate(order(create(), "BUY", 100), "advance");
        assertEquals("2026-01-06", bought.getString("currentDate"));
        assertEquals(100, bought.getIntValue("quantity"));
        money("98895.00", bought, "cash");
        money("100095.00", bought, "equity");
        money("108970.30", bought, "benchmarkEquity");
        var fill = bought.getJSONArray("fills").getJSONObject(0);
        money("1100.00", fill, "amount");
        money("5.00", fill, "commission");
        money("0.00", fill, "tax");
        var sold = mutate(order(bought, "SELL", 100), "advance");
        assertEquals(0, sold.getIntValue("quantity"));
        money("100189.35", sold, "cash");
        money("100189.35", sold, "equity");
        money("10.65", sold, "fees");
        money("0.65", sold.getJSONArray("fills").getJSONObject(1), "tax");
        money("13", sold.getJSONArray("fills").getJSONObject(1), "price");
        assertEquals(tradesBefore, count("trade_record"));
        assertEquals(positionsBefore, count("position_summary"));
        assertEquals(messagesBefore, count("local_message"));
    }

    @Test
    void retryAndConcurrentAdvanceCannotRevealTwoBarsOrDuplicateADeal() throws Exception {
        var ready = order(create(), "BUY", 100);
        String path = BASE + "/" + ready.getString("id") + "/advance";
        String body = JSON.toJSONString(Map.of("expectedVersion", ready.getLongValue("version")));
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> advance = () -> mvc.perform(post(path).header("Authorization", bearer(1))
                    .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
            var tasks = pool.invokeAll(List.of(advance, advance), 30, TimeUnit.SECONDS);
            var statuses = List.of(tasks.get(0).get(), tasks.get(1).get());
            assertEquals(1, statuses.stream().filter(s -> s == 200).count());
            assertEquals(1, statuses.stream().filter(s -> s == 409).count());
        } finally {
            pool.shutdownNow();
        }
        mvc.perform(post(path).header("Authorization", bearer(1)).contentType("application/json").content(body))
                .andExpect(status().isConflict());
        var restored = view(ready.getString("id"));
        assertKnownPrefix(restored, "2026-01-06");
        assertEquals(1, restored.getJSONArray("fills").size());
        assertEquals(1, restored.getIntValue("revealedSteps"));
    }

    @Test
    void earlyFinishRevealsReviewOnlyAndFreezesActualEndValuation() throws Exception {
        var bought = mutate(order(create(), "BUY", 100), "advance");
        var pending = order(bought, "SELL", 100);
        var finished = mutate(pending, "finish");
        assertEquals("FINISHED", finished.getString("status"));
        assertEquals("2026-01-06", finished.getString("currentDate"));
        money("100095", finished, "equity");
        money("108970.30", finished, "benchmarkEquity");
        money("5", finished, "fees");
        assertEquals(100, finished.getIntValue("quantity"));
        assertNull(finished.get("pendingOrder"));
        assertEquals(2, finished.getJSONArray("bars").size());
        assertEquals(4, finished.getJSONArray("reviewBars").size());
        assertEquals(1, finished.getJSONArray("fills").size());
        assertEquals("CANCELLED", finished.getJSONArray("orders").getJSONObject(1).getString("status"));
        for (String action : List.of("advance", "finish", "orders")) {
            mvc.perform(post(BASE + "/" + finished.getString("id") + "/" + action).header("Authorization", bearer(1))
                            .contentType("application/json").content(JSON.toJSONString(Map.of("expectedVersion", finished.getLongValue("version"), "side", "BUY", "quantity", 100))))
                    .andExpect(status().isConflict());
        }
        var restored = view(finished.getString("id"));
        assertEquals(finished.getBigDecimal("equity"), restored.getBigDecimal("equity"));
        assertEquals(finished.getJSONArray("equityCurve"), restored.getJSONArray("equityCurve"));
    }

    @Test
    void naturalEndMarksToMarketAndComputesCloseDrawdown() throws Exception {
        var state = mutate(order(create(), "BUY", 100), "advance");
        state = mutate(state, "advance");
        state = mutate(state, "advance");
        assertEquals("FINISHED", state.getString("status"));
        assertEquals("2026-01-08", state.getString("currentDate"));
        money("99795", state, "equity");
        money("81970.30", state, "benchmarkEquity");
        assertEquals(100, state.getIntValue("quantity"));
        assertEquals(1, state.getJSONArray("fills").size());
        assertEquals(4, state.getJSONArray("equityCurve").size());
        assertEquals(300.0 / 100095.0, state.getDoubleValue("maxDrawdown"), 0.00000001);
        assertEquals(-0.00205, state.getDoubleValue("returnRate"), 0.00000001);
    }

    @Test
    void annotationsAreSessionOwnedAndCannotContainFutureDates() throws Exception {
        var first = create();
        var second = create();
        String path = BASE + "/" + first.getString("id") + "/annotations";
        var future = Map.of("id", "future", "type", "BUY", "points", List.of(Map.of("date", "2026-01-06", "price", 11)));
        mvc.perform(put(path).header("Authorization", bearer(1)).contentType("application/json")
                        .content(JSON.toJSONString(Map.of("expectedVersion", first.getLongValue("version"), "annotations", List.of(future)))))
                .andExpect(status().isBadRequest());
        var valid = Map.of("id", "known", "type", "LINE", "points", List.of(Map.of("date", "2026-01-05", "price", 9), Map.of("date", "2026-01-05", "price", 10)));
        var annotated = data(put(path).header("Authorization", bearer(1)).contentType("application/json")
                .content(JSON.toJSONString(Map.of("expectedVersion", first.getLongValue("version"), "annotations", List.of(valid)))));
        assertEquals(1, annotated.getJSONArray("annotations").size());
        assertTrue(view(second.getString("id")).getJSONArray("annotations").isEmpty());
        assertEquals(1, view(first.getString("id")).getJSONArray("annotations").size());
        assertEquals(0, annotated.getIntValue("quantity"));
        assertTrue(annotated.getJSONArray("fills").isEmpty());
    }

    @Test
    void invalidOrDiscontinuousFutureSampleIsRejectedWithoutEchoingFutureValues() throws Exception {
        var invalid = prices();
        invalid.get(2).put("preClose", new BigDecimal("99.8765"));
        when(market.getDailyData(anyString(), nullable(String.class), nullable(String.class), nullable(Integer.class))).thenReturn(invalid);
        var result = mvc.perform(post(BASE).header("Authorization", bearer(1)).contentType("application/json").content(createBody()))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(result.contains("99.8765"));
        assertFalse(result.contains("2026-01-07"));
        assertEquals(0, count("practice_session"));
    }

    @Test
    void changingOrCancellingAnOrderNeverCreatesADealAndStaleRequestsFail() throws Exception {
        var start = create();
        var first = order(start, "BUY", 100);
        var replacement = order(first, "BUY", 200);
        assertEquals("CANCELLED", replacement.getJSONArray("orders").getJSONObject(0).getString("status"));
        assertEquals(200, replacement.getJSONObject("pendingOrder").getIntValue("quantity"));
        var cancelled = mutate(replacement, "cancel-order");
        assertNull(cancelled.get("pendingOrder"));
        var next = mutate(cancelled, "advance");
        assertTrue(next.getJSONArray("fills").isEmpty());
        money("100000", next, "cash");
        mvc.perform(post(BASE + "/" + next.getString("id") + "/orders").header("Authorization", bearer(1))
                        .contentType("application/json").content("{\"side\":\"BUY\",\"quantity\":100}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyMutationRejectsMalformedVersionsWithoutChangingPersistedState() throws Exception {
        var ready = order(create(), "BUY", 100);
        String id = ready.getString("id");
        // Version 1 makes fractional/quoted/exponent values dangerous if coerced to a matching long.
        assertEquals(1, ready.getLongValue("version"));
        var before = jdbc.queryForMap("SELECT version,state_json,snapshot_json,updated_at FROM practice_session WHERE id=?", id);
        for (String action : List.of("orders", "advance", "cancel-order", "finish", "annotations")) {
            String extra = switch (action) {
                case "orders" -> "\"side\":\"BUY\",\"quantity\":200";
                case "annotations" -> "\"annotations\":[]";
                default -> "";
            };
            for (String value : List.of("", "null", "-1", "1.5", "1.0", "1e0", "9223372036854775808",
                    "999999999999999999999999999999999999", "\"1\"", "\"invalid\"", "true", "false", "{}", "[]")) {
                String versionField = value.isEmpty() ? "" : "\"expectedVersion\":" + value;
                String body = "{" + versionField + (!versionField.isEmpty() && !extra.isEmpty() ? "," : "") + extra + "}";
                var request = action.equals("annotations") ? put(BASE + "/" + id + "/" + action)
                        : post(BASE + "/" + id + "/" + action);
                mvc.perform(request.header("Authorization", bearer(1)).contentType("application/json").content(body))
                        .andExpect(status().isBadRequest());
                assertEquals(before,
                        jdbc.queryForMap("SELECT version,state_json,snapshot_json,updated_at FROM practice_session WHERE id=?", id),
                        action + " accepted or changed state for expectedVersion=" + value);
            }
        }
        assertEquals(ready, view(id));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private JSONObject create() throws Exception { return data(post(BASE).header("Authorization", bearer(1)).contentType("application/json").content(createBody())); }
    private String createBody() { return "{\"stockCode\":\"600519\",\"startDate\":\"2026-01-05\",\"endDate\":\"2026-01-08\",\"initialCash\":100000}"; }
    private JSONObject view(String id) throws Exception { return data(get(BASE + "/" + id).header("Authorization", bearer(1))); }
    private JSONObject order(JSONObject state, String side, int quantity) throws Exception {
        return data(post(BASE + "/" + state.getString("id") + "/orders").header("Authorization", bearer(1)).contentType("application/json")
                .content(JSON.toJSONString(Map.of("expectedVersion", state.getLongValue("version"), "side", side, "quantity", quantity))));
    }
    private JSONObject mutate(JSONObject state, String action) throws Exception {
        return data(post(BASE + "/" + state.getString("id") + "/" + action).header("Authorization", bearer(1)).contentType("application/json")
                .content(JSON.toJSONString(Map.of("expectedVersion", state.getLongValue("version")))));
    }
    private JSONObject response(MockHttpServletRequestBuilder request) throws Exception {
        return JSON.parseObject(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
    private JSONObject data(MockHttpServletRequestBuilder request) throws Exception {
        var envelope = response(request);
        assertEquals(200, envelope.getIntValue("code"), envelope.toJSONString());
        return envelope.getJSONObject("data");
    }
    private void assertKnownPrefix(JSONObject state, String cutoff) {
        assertEquals(cutoff, state.getString("currentDate"));
        assertTrue(state.get("reviewBars") == null || state.getJSONArray("reviewBars").isEmpty());
        for (String forbidden : List.of("snapshot", "snapshotJson", "snapshot_json", "stateJson")) assertFalse(state.containsKey(forbidden));
        for (Object entry : state.getJSONArray("bars")) {
            assertTrue(((JSONObject) entry).getString("date").compareTo(cutoff) <= 0);
        }
        for (Object entry : state.getJSONArray("equityCurve")) {
            assertTrue(((JSONObject) entry).getString("date").compareTo(cutoff) <= 0);
        }
    }
    private void money(String expected, JSONObject actual, String field) {
        assertNotNull(actual.getBigDecimal(field), field);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.getBigDecimal(field)), field + ": " + actual);
    }
    private List<Map<String, Object>> prices() {
        return new java.util.ArrayList<>(List.of(
                bar("20260105", "10", "11", "9", "10", "10"),
                bar("20260106", "11", "13", "10", "12", "10"),
                bar("20260107", "13", "14", "11", "11", "12"),
                bar("20260108", "10", "11", "8", "9", "11")));
    }
    private Map<String, Object> bar(String date, String open, String high, String low, String close, String preClose) {
        return new java.util.HashMap<>(Map.of("stockCode", "600519.SH", "tradeDate", date, "openPrice", new BigDecimal(open),
                "highPrice", new BigDecimal(high), "lowPrice", new BigDecimal(low), "closePrice", new BigDecimal(close),
                "preClose", new BigDecimal(preClose), "vol", new BigDecimal("10000")));
    }
    private String bearer(long userId) {
        return "Bearer " + io.jsonwebtoken.Jwts.builder().subject("practice-user-" + userId).claim("userId", userId)
                .expiration(java.util.Date.from(Instant.now().plusSeconds(600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-jwt-secret-key-01234567890123456789012345".getBytes(StandardCharsets.UTF_8))).compact();
    }
}
