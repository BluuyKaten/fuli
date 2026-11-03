package com.fuli.trade.service.impl;

import com.fuli.common.api.Result;
import com.fuli.common.api.exception.BusinessException;
import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.common.api.enums.TradeTypeEnum;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fuli.trade.dto.CashChangeMessage;
import com.fuli.trade.service.CashChangeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class CashChangeServiceImpl implements CashChangeService {

    private final AuthFeignClient authFeignClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CashChangeServiceImpl(AuthFeignClient authFeignClient) {
        this.authFeignClient = authFeignClient;
    }

    @Override
    public void deductCash(Long userId, BigDecimal amount, String msgId) {
        requireConfirmed(authFeignClient.deductCash(userId, amount, msgId));
    }

    @Override
    public void addCash(Long userId, BigDecimal amount, String msgId) {
        requireConfirmed(authFeignClient.addCash(userId, amount, msgId));
    }

    private void requireConfirmed(Result<Boolean> response) {
        if (response == null || !Integer.valueOf(200).equals(response.getCode())
                || !Boolean.TRUE.equals(response.getData())) {
            String reason = response == null ? "未收到资金服务回执" : response.getMessage();
            throw new BusinessException("资金变动未确认成功: " + reason);
        }
    }

    @Override
    public void processCashChange(CashChangeMessage message) {
        if (message == null || message.getUserId() == null
                || message.getAmount() == null || message.getAmount().signum() < 0
                || message.getMsgId() == null || message.getMsgId().isBlank()
                || message.getDirection() == null) {
            throw new IllegalArgumentException("资金变动消息字段不完整: " + message);
        }
        TradeTypeEnum type = TradeTypeEnum.of(message.getDirection());
        if (type == null) {
            throw new IllegalArgumentException("未知的资金变动方向: " + message.getDirection());
        }
        switch (type) {
            case BUY -> deductCash(message.getUserId(), message.getAmount(), message.getMsgId());
            case SELL -> addCash(message.getUserId(), message.getAmount(), message.getMsgId());
        }
    }

    @Override
    public String serializePayload(CashChangeMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("资金变动消息序列化失败: " + message, e);
        }
    }
}
