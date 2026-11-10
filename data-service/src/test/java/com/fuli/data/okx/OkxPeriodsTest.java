package com.fuli.data.okx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 周期映射的两条硬约束：大小写不能想当然、受控集合不能偷偷放宽。
 *
 * <p>OKX 里 {@code 1m} 是分钟、{@code 1M} 是月，{@code 1h} 会被判非法（它认 {@code 1H}）。
 * 写错不会报「周期不支持」，而是拿到一个完全不同的周期数据——比报错更难查。
 */
class OkxPeriodsTest {

    @Test
    @DisplayName("受控集合就是 bar.chk_bar_period 的 9 个，不多不少")
    void supportedSetIsClosed() {
        List<String> supported = OkxPeriods.supported();
        assertEquals(9, supported.size());
        assertEquals(List.of("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w", "1mo"), supported);
    }

    @Test
    @DisplayName("大小写按 OKX 约定：分钟小写 m，小时/天/周/月大写")
    void okxBarCaseFollowsOkxConvention() {
        assertEquals("1m", OkxPeriods.toOkxBar("1m"));
        assertEquals("15m", OkxPeriods.toOkxBar("15m"));
        assertEquals("1H", OkxPeriods.toOkxBar("1h"));
        assertEquals("4H", OkxPeriods.toOkxBar("4h"));
        // 日/周/月必须带 utc 后缀：1D 是 UTC+8 开盘，会把日线边界挪 8 小时
        assertEquals("1Dutc", OkxPeriods.toOkxBar("1d"));
        assertEquals("1Wutc", OkxPeriods.toOkxBar("1w"));
        assertEquals("1Mutc", OkxPeriods.toOkxBar("1mo"));
    }

    @Test
    @DisplayName("OKX 的 3m/6H/12H/3M 等不在集合内，必须报 400 而不是静默取默认值")
    void rejectsUnsupportedBars() {
        for (String bad : new String[]{"3m", "2H", "6H", "12H", "2D", "3D", "3M", "1D", "1M", "1H", "", null}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> OkxPeriods.toOkxBar(bad), "应拒绝：" + bad);
            assertTrue(e.getMessage().contains("不支持的周期"), "消息要说清原因");
        }
    }

    @Test
    @DisplayName("按 OKX 精确写法反查，1M（月）不会被错认成 1m（分钟）")
    void reverseMapping() {
        assertEquals("1d", OkxPeriods.toPeriod("1Dutc"));
        assertEquals("1h", OkxPeriods.toPeriod("1H"));
        assertEquals("1mo", OkxPeriods.toPeriod("1Mutc"));
        // OKX 的 1M 是「UTC+8 开盘的月线」，我们没接，必须返回 null 而不是 1m
        assertNull(OkxPeriods.toPeriod("1M"));
        assertNull(OkxPeriods.toPeriod("6H"));
        assertNull(OkxPeriods.toPeriod(null));
    }

    @Test
    @DisplayName("OKX 词汇不能直接当 period 传进来：1M 必须是错，不能变成 1 分钟")
    void okxVocabularyIsNotAcceptedAsInput() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> OkxPeriods.toOkxBar("1M"));
        assertTrue(e.getMessage().contains("不支持的周期"));
    }
}
