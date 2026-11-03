package com.fuli.trade.controller;

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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false", "spring.datasource.url=jdbc:h2:mem:drawingdb;DB_CLOSE_DELAY=-1;MODE=MySQL"})
@ActiveProfiles("test")
class DrawingPersistenceTest {
    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean DataFeignClient dataFeignClient;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBeansOfType(Filter.class).values().toArray(Filter[]::new))
                .build();
        jdbc.execute("CREATE TABLE IF NOT EXISTS chart_drawing(id BIGINT AUTO_INCREMENT PRIMARY KEY,user_id BIGINT,stock_code VARCHAR(32),period VARCHAR(16),drawing_data TEXT CHECK(drawing_data <> 'reject'),create_time TIMESTAMP,update_time TIMESTAMP)");
        jdbc.update("DELETE FROM chart_drawing");
        jdbc.update("INSERT INTO chart_drawing(user_id,stock_code,period,drawing_data) VALUES (1,'TEST','1D','original'),(2,'TEST','1D','other-user'),(1,'TEST','1W','other-period')");
    }

    @Test
    void missingNullOrBlankFieldsCannotEraseExistingDrawing() throws Exception {
        for (String payload : new String[]{
                "{\"stockCode\":\"TEST\",\"period\":\"1D\"}",
                "{\"stockCode\":\"TEST\",\"period\":\"1D\",\"data\":null}",
                "{\"stockCode\":\"TEST\",\"period\":\"1D\",\"data\":\" \"}",
                "{\"stockCode\":null,\"period\":\"1D\",\"data\":\"[]\"}",
                "{\"stockCode\":\"TEST\",\"period\":\"\",\"data\":\"[]\"}"}) {
            mvc.perform(post("/stock/drawing").header("Authorization", bearer())
                            .contentType("application/json").content(payload))
                    .andExpect(status().isBadRequest());
            assertEquals("original", currentDrawing());
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM chart_drawing", Integer.class));
    }

    @Test
    void explicitEmptyArrayClearsOnlyCurrentUsersSelectedPeriod() throws Exception {
        save("[]").andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM chart_drawing WHERE user_id=1 AND period='1D'", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM chart_drawing", Integer.class));
    }

    @Test
    void validDataReplacesSelectedDrawing() throws Exception {
        save("[{\"id\":\"new-line\"}]").andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        assertEquals("[{\"id\":\"new-line\"}]", currentDrawing());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM chart_drawing", Integer.class));
    }

    @Test
    void failedInsertRollsBackRemovalOfPreviousDrawing() throws Exception {
        // The database constraint fails after DELETE, so this verifies the transaction boundary.
        save("reject").andExpect(jsonPath("$.code").value(500));
        assertEquals("original", currentDrawing());
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM chart_drawing", Integer.class));
    }

    private org.springframework.test.web.servlet.ResultActions save(String data) throws Exception {
        String payload = com.alibaba.fastjson2.JSON.toJSONString(Map.of("stockCode", "TEST", "period", "1D", "data", data));
        return mvc.perform(post("/stock/drawing").header("Authorization", bearer())
                .contentType("application/json").content(payload));
    }

    private String currentDrawing() {
        return jdbc.queryForObject("SELECT drawing_data FROM chart_drawing WHERE user_id=1 AND stock_code='TEST' AND period='1D'", String.class);
    }

    private String bearer() {
        return "Bearer " + io.jsonwebtoken.Jwts.builder().subject("user-1").claim("userId", 1L)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-jwt-secret-key-01234567890123456789012345".getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
