package com.fuli.trade.practice;

import com.fuli.common.api.feign.DataFeignClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Tests the actual transactional service with a real database, without unrelated microservice startup. */
@SpringJUnitConfig(PracticePersistenceTest.Config.class)
class PracticePersistenceTest {
    @Configuration
    @EnableTransactionManagement
    @Import({PracticeService.class, PracticeSnapshotLoader.class, PracticeSnapshotCache.class})
    static class Config {
        @Bean DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:practice_engine_persistence;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        }
        @Bean JdbcTemplate jdbc(DataSource source) { return new JdbcTemplate(source); }
        @Bean JdbcTransactionManager transactionManager(DataSource source) { return new JdbcTransactionManager(source); }
        @Bean DataFeignClient dataFeignClient() { return mock(DataFeignClient.class); }
    }

    @Autowired PracticeService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired PracticeSnapshotCache snapshots;
    @Autowired DataFeignClient market;

    @BeforeEach
    void setUp() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS practice_session(id VARCHAR(36) PRIMARY KEY,user_id BIGINT NOT NULL,stock_code VARCHAR(20),start_date VARCHAR(10),end_date VARCHAR(10),current_day VARCHAR(10),status VARCHAR(20),equity DECIMAL(18,2),initial_cash DECIMAL(18,2),version BIGINT NOT NULL,state_json LONGTEXT NOT NULL,snapshot_json LONGTEXT NOT NULL,created_at TIMESTAMP NOT NULL,updated_at TIMESTAMP NOT NULL)");
        jdbc.update("DELETE FROM practice_session");
        snapshots.clear();
        reset(market);
        when(market.getDailyData(anyString(), anyString(), anyString(), nullable(Integer.class))).thenReturn(List.of(
                bar("20260105", "10", "10"), bar("20260106", "11", "12"), bar("20260107", "13", "13")));
    }

    @Test
    void reloadingASeparateServiceUsesFrozenSnapshotAndEnforcesOwner() {
        var initial = create();
        var ordered = service.order(1, initial.id(), new PracticeDtos.OrderRequest(initial.version(), "BUY", BigDecimal.valueOf(100)));
        var advanced = service.advance(1, initial.id(), new PracticeDtos.VersionRequest(ordered.version()));
        String originalSnapshot = snapshot(initial.id());
        when(market.getDailyData(anyString(), anyString(), anyString(), nullable(Integer.class))).thenThrow(new IllegalStateException("No market calls after create"));
        // 重启 = 新实例 + 新缓存：快照仍必须来自数据库里冻结的那一份。
        var restarted = new PracticeService(jdbc, new PracticeSnapshotLoader(market), new PracticeSnapshotCache());
        assertEquals(advanced, restarted.get(1, initial.id()));
        assertEquals(originalSnapshot, snapshot(initial.id()));
        assertEquals(404, assertThrows(ResponseStatusException.class, () -> service.get(2, initial.id())).getStatusCode().value());
        assertTrue(service.list(2).isEmpty());
        assertEquals(1, service.list(1).size());
        verify(market, times(1)).getDailyData(anyString(), anyString(), anyString(), nullable(Integer.class));
    }

    @Test
    void repeatedReadsAndMutationsStopReloadingTheImmutableSnapshotColumn() {
        var initial = create();
        // 新实例 + 空缓存，模拟「进程刚启动、缓存还没热」。
        var recorded = new SqlRecordingJdbcTemplate(jdbc.getDataSource());
        var cache = new PracticeSnapshotCache();
        var instrumented = new PracticeService(recorded, new PracticeSnapshotLoader(market), cache);

        assertEquals(initial, instrumented.get(1, initial.id()));
        assertEquals(1, recorded.count("SELECT snapshot_json"));
        int afterFirstRead = recorded.statements().size();

        // 第二次读、下单、揭晓下一日都只该读 state_json，不再碰几十 KB 的 bars。
        assertEquals(initial, instrumented.get(1, initial.id()));
        var ordered = instrumented.order(1, initial.id(), new PracticeDtos.OrderRequest(initial.version(), "BUY", BigDecimal.valueOf(100)));
        var advanced = instrumented.advance(1, initial.id(), new PracticeDtos.VersionRequest(ordered.version()));

        assertEquals(1, recorded.count("SELECT snapshot_json"), recorded.statements().toString());
        assertTrue(recorded.statements().size() > afterFirstRead);
        assertEquals(1, cache.misses());
        assertEquals(3, cache.hits());
        // 行为不变：缓存只是省掉了读取，落库的 state 仍然完整可读。
        assertEquals(advanced, new PracticeService(jdbc, new PracticeSnapshotLoader(market), new PracticeSnapshotCache()).get(1, initial.id()));
    }

    @Test
    void failedConditionalWriteRollsBackFundsFillProgressAndVersion() {
        var initial = create();
        var ordered = service.order(1, initial.id(), new PracticeDtos.OrderRequest(initial.version(), "BUY", BigDecimal.valueOf(100)));
        var originalSnapshot = snapshot(initial.id());
        jdbc.execute("ALTER TABLE practice_session ADD CONSTRAINT test_reject_version_two CHECK(version<>2)");
        try {
            assertThrows(DataIntegrityViolationException.class, () -> service.advance(1, initial.id(), new PracticeDtos.VersionRequest(ordered.version())));
            var restored = service.get(1, initial.id());
            assertEquals(ordered, restored);
            assertEquals(originalSnapshot, snapshot(initial.id()));
        } finally {
            jdbc.execute("ALTER TABLE practice_session DROP CONSTRAINT test_reject_version_two");
        }
        var advanced = service.advance(1, initial.id(), new PracticeDtos.VersionRequest(ordered.version()));
        assertEquals(1, advanced.fills().size());
        assertEquals(1, advanced.revealedSteps());
        assertEquals(409, assertThrows(ResponseStatusException.class, () -> service.advance(1, initial.id(), new PracticeDtos.VersionRequest(ordered.version()))).getStatusCode().value());
        assertEquals(advanced, service.get(1, initial.id()));
    }

    private PracticeDtos.PracticeView create() {
        return service.create(1, new PracticeDtos.CreateRequest("600519", "2026-01-05", "2026-01-07", BigDecimal.valueOf(100000)));
    }
    private String snapshot(String id) { return jdbc.queryForObject("SELECT snapshot_json FROM practice_session WHERE id=?", String.class, id); }
    private Map<String, Object> bar(String date, String open, String close) {
        return Map.of("stockCode", "600519.SH", "tradeDate", date, "openPrice", new BigDecimal(open), "highPrice", new BigDecimal("15"),
                "lowPrice", new BigDecimal("8"), "closePrice", new BigDecimal(close), "preClose", new BigDecimal(date.equals("20260107") ? "12" : "10"), "vol", new BigDecimal("1000"));
    }
}
