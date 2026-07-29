package com.fuli.trade.feign;

import com.fuli.common.api.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@FeignClient(name = "auth-service", contextId = "authFeignClient")
public interface AuthFeignClient {

    @PutMapping("/auth/internal/deductCash")
    Result<Boolean> deductCash(@RequestParam("userId") Long userId, @RequestParam("amount") BigDecimal amount);

    @PutMapping("/auth/internal/addCash")
    Result<Boolean> addCash(@RequestParam("userId") Long userId, @RequestParam("amount") BigDecimal amount);
}
