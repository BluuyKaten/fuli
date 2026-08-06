package com.fuli.common.api.feign;

import com.fuli.common.api.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.math.BigDecimal;

@FeignClient(name = "auth-service", contextId = "authFeignClient")
public interface AuthFeignClient {

    @GetMapping("/auth/internal/userCash")
    Result<BigDecimal> getUserCash(@RequestParam("userId") Long userId);

    @GetMapping("/auth/internal/userInitialCash")
    Result<BigDecimal> getUserInitialCash(@RequestParam("userId") Long userId);

    @PutMapping("/auth/internal/resetCash")
    Result<Boolean> resetCash(@RequestParam("userId") Long userId, @RequestParam("newCash") BigDecimal newCash);

    /**
     * 扣除现金（买入时调用）
     */
    @PutMapping("/auth/internal/deductCash")
    Result<Boolean> deductCash(@RequestParam("userId") Long userId, @RequestParam("amount") BigDecimal amount);

    /**
     * 增加现金（卖出时调用）
     */
    @PutMapping("/auth/internal/addCash")
    Result<Boolean> addCash(@RequestParam("userId") Long userId, @RequestParam("amount") BigDecimal amount);
}
