package com.fuli.data.risk;

import com.fuli.data.crypto.entity.CryptoAccount;
import com.fuli.data.crypto.mapper.CryptoAccountMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RiskControlServiceTest {

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static RiskControlProperties props() {
        RiskControlProperties p = new RiskControlProperties();
        p.setEnabled(true);
        p.setSingleOrderMaxNotional(bd("1000"));
        p.setDailyMaxNotional(bd("5000"));
        p.setMaxDrawdown(bd("0.20"));
        p.setKillSwitch(false);
        return p;
    }

    private static RiskControlService svc(RiskStateMapper stateMapper, CryptoAccountMapper accMapper,
                                          RiskControlProperties p) {
        return new RiskControlService(stateMapper, accMapper, p);
    }

    private static CryptoAccount totalEquity(String eq) {
        CryptoAccount acc = new CryptoAccount();
        acc.setCcy("TOTAL");
        acc.setEquity(bd(eq));
        return acc;
    }

    @Test
    void killSwitchRejects() {
        RiskControlProperties p = props();
        p.setKillSwitch(true);
        RiskControlService svc = svc(mock(RiskStateMapper.class), mock(CryptoAccountMapper.class), p);
        assertThrows(RiskControlException.class, () -> svc.checkBeforeOrder(1L, bd("100")));
    }

    @Test
    void singleOrderOverLimitRejects() {
        RiskControlService svc = svc(mock(RiskStateMapper.class), mock(CryptoAccountMapper.class), props());
        assertThrows(RiskControlException.class, () -> svc.checkBeforeOrder(1L, bd("2000")));
    }

    @Test
    void dailyOverLimitRejects() {
        RiskStateMapper stateMapper = mock(RiskStateMapper.class);
        RiskState state = new RiskState();
        state.setUserId(1L);
        state.setDailyDate(LocalDate.now());
        state.setDailyNotional(bd("4500"));
        when(stateMapper.selectById(1L)).thenReturn(state);
        RiskControlService svc = svc(stateMapper, mock(CryptoAccountMapper.class), props());
        // 4500 + 1000 = 5500 > 5000
        assertThrows(RiskControlException.class, () -> svc.checkBeforeOrder(1L, bd("1000")));
    }

    @Test
    void dailyResetsAcrossDays() {
        RiskStateMapper stateMapper = mock(RiskStateMapper.class);
        RiskState state = new RiskState();
        state.setUserId(1L);
        state.setDailyDate(LocalDate.now().minusDays(1)); // 昨天的额度
        state.setDailyNotional(bd("4500"));
        when(stateMapper.selectById(1L)).thenReturn(state);
        RiskControlService svc = svc(stateMapper, mock(CryptoAccountMapper.class), props());
        // 跨天重置：1000 <= 5000，通过
        assertDoesNotThrow(() -> svc.checkBeforeOrder(1L, bd("1000")));
    }

    @Test
    void drawdownOverThresholdRejects() {
        RiskStateMapper stateMapper = mock(RiskStateMapper.class);
        RiskState state = new RiskState();
        state.setUserId(1L);
        state.setPeakEquity(bd("200"));
        when(stateMapper.selectById(1L)).thenReturn(state);

        CryptoAccountMapper accMapper = mock(CryptoAccountMapper.class);
        when(accMapper.selectLatestPerCcy(1L)).thenReturn(List.of(totalEquity("100")));

        RiskControlService svc = svc(stateMapper, accMapper, props());
        // 回撤 (200-100)/200 = 50% > 20%
        assertThrows(RiskControlException.class, () -> svc.checkBeforeOrder(1L, bd("100")));
    }

    @Test
    void noSnapshotFailsOpen() {
        CryptoAccountMapper accMapper = mock(CryptoAccountMapper.class);
        when(accMapper.selectLatestPerCcy(1L)).thenReturn(List.of());
        RiskControlService svc = svc(mock(RiskStateMapper.class), accMapper, props());
        assertDoesNotThrow(() -> svc.checkBeforeOrder(1L, bd("100")));
    }

    @Test
    void passesWhenAllClear() {
        RiskStateMapper stateMapper = mock(RiskStateMapper.class);
        when(stateMapper.selectById(1L)).thenReturn(null);
        CryptoAccountMapper accMapper = mock(CryptoAccountMapper.class);
        when(accMapper.selectLatestPerCcy(1L)).thenReturn(List.of(totalEquity("150")));

        RiskControlService svc = svc(stateMapper, accMapper, props());
        assertDoesNotThrow(() -> svc.checkBeforeOrder(1L, bd("100")));
        verify(stateMapper).insert(any(RiskState.class));
    }

    @Test
    void disabledSkipsAllChecks() {
        RiskControlProperties p = props();
        p.setEnabled(false);
        p.setKillSwitch(true); // 即便 kill switch 打开，enabled=false 也应跳过
        RiskControlService svc = svc(mock(RiskStateMapper.class), mock(CryptoAccountMapper.class), p);
        assertDoesNotThrow(() -> svc.checkBeforeOrder(1L, bd("999999")));
    }
}
