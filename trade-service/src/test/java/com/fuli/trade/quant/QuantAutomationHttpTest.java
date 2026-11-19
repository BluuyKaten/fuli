package com.fuli.trade.quant;

import com.fuli.common.api.config.JwtProperties;
import com.fuli.common.api.feign.DataFeignClient;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 穿过真实认证过滤器验证页面采集入口、配置持久化和账户隔离。 */
@SpringBootTest(properties = {"spring.cloud.nacos.discovery.enabled=false", "spring.cloud.nacos.config.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:quant_http;DB_CLOSE_DELAY=-1;MODE=MySQL"})
@ActiveProfiles("test")
class QuantAutomationHttpTest {
    @Autowired WebApplicationContext context;
    @Autowired JwtProperties jwt;
    @MockitoBean DataFeignClient data;
    @MockitoBean DazhihuiDesktopCollector collector;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBeansOfType(Filter.class).values().toArray(Filter[]::new)).build();
        when(collector.collect(anyString())).thenReturn(QuantRankingDtos.CollectResult.success("大智慧原生复制", 
                "代码\t名称\t最新\t人气(wyz)\n'600000'\t浦发银行\t10.78\t16.582\n'600519'\t贵州茅台\t1435.26\t85.518\n"));
    }

    @Test
    void shouldPersistConfigAndKeepItPrivateToTheAuthenticatedUser() throws Exception {
        mvc.perform(put("/trade/quant-ranking/automation/config").header("Authorization", bearer(801))
                .contentType("application/json").content("{\"enabled\":false,\"executablePath\":\"\",\"scheduleTime\":\"15:10\",\"startupCatchUp\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        mvc.perform(get("/trade/quant-ranking/automation/config").header("Authorization", bearer(801)))
                .andExpect(jsonPath("$.data.scheduleTime").value("15:10"));
        mvc.perform(get("/trade/quant-ranking/automation/config").header("Authorization", bearer(802)))
                .andExpect(jsonPath("$.data.enabled").value(false));
    }

    @Test
    void shouldCollectRankByPopularityAndExposeLastResult() throws Exception {
        mvc.perform(post("/trade/quant-ranking/collect").param("date", "2026-11-16").header("Authorization", bearer(803)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCESS"))
                .andExpect(jsonPath("$.data.importedRows").value(2));
        mvc.perform(get("/trade/quant-ranking/daily").param("date", "2026-11-16").header("Authorization", bearer(803)))
                .andExpect(jsonPath("$.data.rows[0].code").value("600519"))
                .andExpect(jsonPath("$.data.rows[0].metricValue").value(85.518));
        mvc.perform(get("/trade/quant-ranking/automation/status").header("Authorization", bearer(803)))
                .andExpect(jsonPath("$.data.lastRun.status").value("SUCCESS"));
        mvc.perform(get("/trade/quant-ranking/daily").param("date", "2026-11-16").header("Authorization", bearer(804)))
                .andExpect(jsonPath("$.data.rows.length()").value(0));
    }

    @Test
    void shouldRejectUnauthenticatedDesktopCollection() throws Exception {
        mvc.perform(post("/trade/quant-ranking/collect")).andExpect(status().isUnauthorized());
    }

    private String bearer(long userId) {
        return "Bearer " + Jwts.builder().subject("test").claim("userId", userId)
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(jwt.getSecret().getBytes(StandardCharsets.UTF_8))).compact();
    }
}
