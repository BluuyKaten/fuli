package com.fuli.trade.service;

import com.fuli.common.api.Result;
import com.fuli.common.api.exception.BusinessException;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.trade.entity.LocalMessage;
import com.fuli.trade.service.impl.CashChangeServiceImpl;
import com.fuli.trade.service.impl.LocalMessageRetryServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CashMessageReliabilityTest {
    private final AuthFeignClient auth = mock(AuthFeignClient.class);
    private final CashChangeService cash = new CashChangeServiceImpl(auth);
    private final LocalMessageService messages = mock(LocalMessageService.class);
    private final LocalMessageRetryService retry = new LocalMessageRetryServiceImpl(messages, cash);

    @Test
    void rejectedDeductionRemainsRetryable() {
        when(auth.deductCash(eq(1L), any(), eq("buy-1"))).thenReturn(Result.error("资金不足"));
        runMessage("buy-1", "{\"userId\":1,\"amount\":100,\"msgId\":\"buy-1\",\"direction\":1}");
        verify(messages, never()).markSuccess(any());
        verify(messages).incrementRetryOrDeadLetter(eq(10L), contains("资金不足"));
    }

    @Test
    void invalidJsonIsNotAcknowledged() {
        runMessage("bad-json", "{bad");
        verify(messages, never()).markSuccess(any());
        verify(messages).incrementRetryOrDeadLetter(eq(10L), anyString());
        verifyNoInteractions(auth);
    }

    @Test
    void invalidDirectionIsNotAcknowledged() {
        runMessage("bad-direction", "{\"userId\":1,\"amount\":100,\"msgId\":\"bad-direction\",\"direction\":9}");
        verify(messages, never()).markSuccess(any());
        verify(messages).incrementRetryOrDeadLetter(eq(10L), anyString());
        verifyNoInteractions(auth);
    }

    @Test
    void emptyPayloadIsNotAcknowledged() {
        runMessage("empty", "");
        verify(messages, never()).markSuccess(any());
        verify(messages).incrementRetryOrDeadLetter(eq(10L), anyString());
    }

    @Test
    void onlyConfirmedCashChangesAreAcknowledged() {
        when(auth.addCash(eq(1L), any(), eq("sell-1"))).thenReturn(Result.success(true));
        runMessage("sell-1", "{\"userId\":1,\"amount\":100,\"msgId\":\"sell-1\",\"direction\":2}");
        verify(messages).markSuccess(10L);
        verify(messages, never()).incrementRetryOrDeadLetter(any(), anyString());
    }

    @Test
    void falseOrMissingResponseRejectsCashChange() {
        when(auth.deductCash(eq(1L), any(), anyString())).thenReturn(Result.success(false)).thenReturn(null);
        assertThrows(BusinessException.class, () -> cash.deductCash(1L, BigDecimal.TEN, "false"));
        assertThrows(BusinessException.class, () -> cash.deductCash(1L, BigDecimal.TEN, "null"));
    }

    @Test
    void payloadCannotChangeTheIdempotencyKey() {
        runMessage("actual", "{\"userId\":1,\"amount\":100,\"msgId\":\"different\",\"direction\":1}");
        verifyNoInteractions(auth);
        verify(messages, never()).markSuccess(any());
        verify(messages).incrementRetryOrDeadLetter(eq(10L), anyString());
    }

    @Test
    void messageClaimedByAnotherInstanceIsSkipped() {
        when(messages.tryClaim(any())).thenReturn(false);
        LocalMessage message = new LocalMessage();
        message.setId(11L);
        message.setMsgId("buy-claim");
        message.setPayload("{\"userId\":1,\"amount\":100,\"msgId\":\"buy-claim\",\"direction\":1}");
        message.setRetryCount(0);
        when(messages.listRetryableMessages(50)).thenReturn(List.of(message));

        retry.retryPendingMessages();

        verifyNoInteractions(auth);
        verify(messages, never()).markSuccess(any());
        verify(messages, never()).incrementRetryOrDeadLetter(any(), anyString());
    }

    private void runMessage(String msgId, String payload) {
        LocalMessage message = new LocalMessage();
        message.setId(10L);
        message.setMsgId(msgId);
        message.setPayload(payload);
        message.setRetryCount(0);
        when(messages.tryClaim(any())).thenReturn(true);
        when(messages.listRetryableMessages(50)).thenReturn(List.of(message));
        retry.retryPendingMessages();
    }
}
