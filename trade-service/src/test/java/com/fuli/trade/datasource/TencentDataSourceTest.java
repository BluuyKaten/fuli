package com.fuli.trade.datasource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.trade.dto.KlineBarDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 腾讯美股周/月线的解析口径。
 *
 * <p>和东财一样，腾讯的字段序是「日期, 开, 收, 高, 低, 量」——高开低收写反了不会报错，
 * 只会让影线上下颠倒，所以这里逐字段断言，并额外断言 high ≥ open/close、low ≤ open/close。
 */
class TencentDataSourceTest {

    @Test
    void parsesWeeklyRowsAsOpenCloseHighLow() throws Exception {
        JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"code":0,"data":{"AAPL.OQ":{"qfqweek":[
                  ["2026-11-06","204.64","212.23","214.35","204.61","108483103.00"],
                  ["2026-11-13","213.00","210.10","215.00","208.00","90000000.00"]
                ]}}}""");

        List<KlineBarDTO> bars = TencentDataSource.parse("week", root);

        assertEquals(2, bars.size());
        KlineBarDTO first = bars.get(0);
        assertEquals(0, new java.math.BigDecimal("204.64").compareTo(first.getOpen()));
        assertEquals(0, new java.math.BigDecimal("212.23").compareTo(first.getClose()));
        assertEquals(0, new java.math.BigDecimal("214.35").compareTo(first.getHigh()));
        assertEquals(0, new java.math.BigDecimal("204.61").compareTo(first.getLow()));
        assertEquals(108483103L, first.getVolume());
        assertTrue(first.getHigh().compareTo(first.getClose()) >= 0);
        assertTrue(first.getLow().compareTo(first.getOpen()) <= 0);
    }

    @Test
    void timestampsMatchTheEastMoneyChartAxis() {
        // 东财图用的是「交易日 00:00 Asia/Shanghai」的 epoch 秒；切源时横轴不能跳。
        long ts = TencentDataSource.epochOf(LocalDate.of(2026, 11, 6));
        assertEquals("2026-11-06T00:00",
                java.time.LocalDateTime.ofEpochSecond(ts, 0, java.time.ZoneOffset.ofHours(8)).toString());
    }

    @Test
    void emptyOrMalformedPayloadYieldsNoBars() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertTrue(TencentDataSource.parse("week", mapper.readTree("{\"code\":0,\"data\":{}}")).isEmpty());
        assertTrue(TencentDataSource.parse("week", mapper.readTree("{\"code\":0,\"data\":[]}")).isEmpty());
        assertTrue(TencentDataSource.parse("week",
                mapper.readTree("{\"code\":0,\"data\":{\"AAPL.OQ\":{\"qfqweek\":[]}}}")).isEmpty());
    }

    @Test
    void parsesTencentCodeFromQuoteLine() {
        assertEquals("AAPL.OQ", TencentDataSource.parseCode("v_usAAPL=\"200~苹果~AAPL.OQ~353.00~\";"));
        assertEquals("SPY.AM", TencentDataSource.parseCode("v_usSPY=\"200~标普500~SPY.AM~755.33~\";"));
        assertNull(TencentDataSource.parseCode("v_usAAPL=\"500~苹果~AAPL.OQ~\";"));
        assertNull(TencentDataSource.parseCode(""));
    }

    @Test
    void minuteDataIsEmptyBecauseTencentHasNoUsMinuteHistory() {
        TencentDataSource ds = new TencentDataSource();
        assertTrue(ds.getMinuteData("AAPL.US", 5).isEmpty());
        assertEquals("Tencent", ds.getName());
    }
}
