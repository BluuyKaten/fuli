package com.fuli.trade.market;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期下发口径。
 *
 * <p>关键约束：加密不走这条链路（它用 {@code /market/bars}），必须返回空列表而不是
 * 「看起来支持」——否则前端会拿一个永远 400 的周期去请求。
 */
class KlinePeriodsTest {

    @Test
    void aShareAndUsShareBothExposeFullSet() {
        for (String market : List.of("SH", "SZ", "BJ", "US")) {
            List<KlinePeriods.Period> periods = KlinePeriods.of(market);
            assertEquals(8, periods.size(), market + " 应有 8 个周期");
            assertEquals("1", periods.get(0).value());
            assertEquals("minute", periods.get(0).kind());
            assertEquals("1D", periods.get(5).value());
            assertEquals("day", periods.get(5).kind());
            assertEquals("1W", periods.get(6).value());
            assertEquals("week", periods.get(6).kind());
            assertEquals("1M", periods.get(7).value());
            assertEquals("month", periods.get(7).kind());
        }
    }

    @Test
    void everyMinutePeriodUsesMinuteKind() {
        long minutes = KlinePeriods.of("SH").stream().filter(p -> "minute".equals(p.kind())).count();
        // 1/5/15/30/60 五个；前端靠 kind 分派端点，漏一个就是「点了没反应」
        assertEquals(5, minutes);
    }

    @Test
    void cryptoHasNoPeriodsOnThisPath() {
        assertTrue(KlinePeriods.of("CRYPTO").isEmpty());
        assertTrue(KlinePeriods.of("UNKNOWN").isEmpty());
    }

    @Test
    void describeCarriesMarketAndTimezone() {
        Map<String, Object> body = KlinePeriods.describe("AAPL.US");
        assertEquals("US", body.get("market"));
        assertEquals("1D", body.get("defaultPeriod"));
        // 美股分钟线也是北京时间，前端要靠这个字段标注，不然用户以为数据错了
        assertEquals("Asia/Shanghai", body.get("timezone"));
        assertEquals(8, ((List<?>) body.get("periods")).size());
    }

    @Test
    void describeAcceptsBareAShareCode() {
        assertEquals("SH", KlinePeriods.describe("600519").get("market"));
    }
}
