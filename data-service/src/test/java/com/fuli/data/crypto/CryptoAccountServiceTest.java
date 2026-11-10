package com.fuli.data.crypto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.data.crypto.entity.CryptoAccount;
import com.fuli.data.crypto.mapper.CryptoAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 余额快照落库（V12）。
 *
 * <p>核心是**去重**：交易所会定时重推同一份快照，唯一键冲突必须当正常路径吞掉，
 * 而不是把 WS 分发线程打挂。
 */
class CryptoAccountServiceTest {

    private static final long USER = 3L;

    private static final ObjectMapper JSON = new ObjectMapper();

    private CryptoAccountMapper mapper;

    private CryptoAccountService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CryptoAccountMapper.class);
        service = new CryptoAccountService(mapper);
    }

    private static JsonNode push(String totalEq, String uTime, String details) {
        try {
            return JSON.readTree("{\"totalEq\":\"" + totalEq + "\",\"uTime\":\"" + uTime
                    + "\",\"details\":[" + details + "]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("一条推送 → TOTAL + 每个币种各一行")
    void writesTotalAndEachCurrency() {
        JsonNode node = push("12345.6", "1794283000000",
                "{\"ccy\":\"USDT\",\"eq\":\"10000\",\"cashBal\":\"9000\",\"availBal\":\"8000\","
                        + "\"frozenBal\":\"1000\"},"
                        + "{\"ccy\":\"BTC\",\"eq\":\"2345.6\",\"upl\":\"12.5\"}");

        int written = service.recordSnapshot(USER, node);

        assertEquals(3, written);
        ArgumentCaptor<CryptoAccount> captor = ArgumentCaptor.forClass(CryptoAccount.class);
        verify(mapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        List<CryptoAccount> rows = captor.getAllValues();
        assertEquals(CryptoAccountService.TOTAL_CCY, rows.get(0).getCcy());
        assertEquals(new BigDecimal("12345.6"), rows.get(0).getEquity());
        assertEquals("USDT", rows.get(1).getCcy());
        assertEquals(new BigDecimal("9000"), rows.get(1).getCashBal());
        assertEquals(new BigDecimal("1000"), rows.get(1).getFrozenBal());
        assertEquals("BTC", rows.get(2).getCcy());
        assertEquals(new BigDecimal("12.5"), rows.get(2).getUpl());
        rows.forEach(r -> {
            assertEquals(USER, r.getUserId());
            assertEquals(1794283000000L, r.getUTime());
            assertTrue(r.getSnapshotAt() != null);
        });
    }

    @Test
    @DisplayName("重复快照：唯一键冲突当正常路径，返回 0 而不是抛异常")
    void duplicateSnapshotIsIgnored() {
        when(mapper.insert(any(CryptoAccount.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("uk_crypto_account"));

        int written = service.recordSnapshot(USER, push("1", "100", "{\"ccy\":\"USDT\",\"eq\":\"1\"}"));

        assertEquals(0, written);
    }

    @Test
    @DisplayName("缺 uTime / 缺 userId / 非对象：不写库")
    void invalidPushIsSkipped() {
        assertEquals(0, service.recordSnapshot(USER, push("1", "", "{\"ccy\":\"USDT\"}")));
        assertEquals(0, service.recordSnapshot(0L, push("1", "100", "{\"ccy\":\"USDT\"}")));
        assertEquals(0, service.recordSnapshot(USER, null));
        verify(mapper, never()).insert(any(CryptoAccount.class));
    }

    @Test
    @DisplayName("没有 totalEq 时只写币种行（不编一个 0 出来）")
    void missingTotalEqWritesOnlyDetails() {
        JsonNode node;
        try {
            node = JSON.readTree("{\"uTime\":\"100\",\"details\":[{\"ccy\":\"USDT\",\"eq\":\"5\"}]}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        assertEquals(1, service.recordSnapshot(USER, node));
        ArgumentCaptor<CryptoAccount> captor = ArgumentCaptor.forClass(CryptoAccount.class);
        verify(mapper).insert(captor.capture());
        assertEquals("USDT", captor.getValue().getCcy());
    }

    @Test
    @DisplayName("历史查询 limit 有上限")
    void historyLimitIsCapped() {
        when(mapper.selectHistory(USER, "USDT", 500)).thenReturn(List.of());
        service.history(USER, "USDT", 9999);
        verify(mapper).selectHistory(USER, "USDT", 500);
    }
}
