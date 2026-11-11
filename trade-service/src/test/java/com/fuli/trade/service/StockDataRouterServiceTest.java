package com.fuli.trade.service;

import com.fuli.trade.datasource.StockDataSource;
import com.fuli.trade.dto.KlineBarDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 数据源优先级：美股先问腾讯（东财 K 线主机在部分网络不可达），A 股仍只走东财。
 *
 * <p>这条规则值得钉住：写反了不会报错，只会让美股每次画图都先等一次东财超时，
 * 而 A 股则可能在腾讯的口径下静默变样。
 */
class StockDataRouterServiceTest {

    private final StockDataSource eastMoney = mock(StockDataSource.class);
    private final StockDataSource tencent = mock(StockDataSource.class);

    private StockDataRouterService router() {
        when(eastMoney.getName()).thenReturn("EastMoney");
        when(eastMoney.isAvailable()).thenReturn(true);
        when(tencent.getName()).thenReturn("Tencent");
        when(tencent.isAvailable()).thenReturn(true);
        return new StockDataRouterService(List.of(eastMoney, tencent));
    }

    @Test
    void usWeeklyPrefersTencentAndNeverTouchesEastMoney() {
        when(tencent.getWeeklyData("AAPL.US")).thenReturn(List.of(new KlineBarDTO()));

        var bars = router().getWeeklyData("AAPL.US");

        assertEquals(1, bars.size());
        verify(eastMoney, never()).getWeeklyData(anyString());
    }

    @Test
    void usFallsBackToEastMoneyWhenTencentReturnsNothing() {
        when(tencent.getWeeklyData("AAPL.US")).thenReturn(List.of());
        when(eastMoney.getWeeklyData("AAPL.US")).thenReturn(List.of(new KlineBarDTO(), new KlineBarDTO()));

        var bars = router().getWeeklyData("AAPL.US");

        assertEquals(2, bars.size());
        verify(tencent).getWeeklyData("AAPL.US");
        verify(eastMoney).getWeeklyData("AAPL.US");
    }

    @Test
    void aShareNeverUsesTencent() {
        when(eastMoney.getMonthlyData("600519.SH")).thenReturn(List.of(new KlineBarDTO()));

        var bars = router().getMonthlyData("600519.SH");

        assertEquals(1, bars.size());
        verify(tencent, never()).getMonthlyData(anyString());
    }

    @Test
    void allSourcesEmptyYieldsEmptyListInsteadOfThrowing() {
        when(tencent.getWeeklyData("AAPL.US")).thenReturn(List.of());
        when(eastMoney.getWeeklyData("AAPL.US")).thenThrow(new IllegalStateException("east money down"));

        assertTrue(router().getWeeklyData("AAPL.US").isEmpty());
    }
}
