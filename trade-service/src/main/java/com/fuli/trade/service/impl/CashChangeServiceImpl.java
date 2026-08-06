package com.fuli.trade.service.impl;

import com.fuli.common.api.feign.AuthFeignClient;
import com.fuli.trade.service.CashChangeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Slf4j
@Service
public class CashChangeServiceImpl implements CashChangeService {

    private final AuthFeignClient authFeignClient;

    public CashChangeServiceImpl(AuthFeignClient authFeignClient) {
        this.authFeignClient = authFeignClient;
    }

    @Override
    public void deductCash(Long userId, BigDecimal amount) {
        authFeignClient.deductCash(userId, amount);
    }

    @Override
    public void addCash(Long userId, BigDecimal amount) {
        authFeignClient.addCash(userId, amount);
    }
}
