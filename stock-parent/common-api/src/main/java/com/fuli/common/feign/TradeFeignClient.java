package com.fuli.common.feign;

import com.fuli.common.dto.TradeVO;
import com.fuli.common.model.Result;
import java.time.LocalDate;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "trade-service")
public interface TradeFeignClient {

    @GetMapping("/internal/trades")
    Result<List<TradeVO>> listByUserId(
            @RequestParam("userId") Long userId,
            @RequestParam(value = "start", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate start,
            @RequestParam(value = "end", required = false) @DateTimeFormat(pattern = "yyyy-MM-dd") LocalDate end);
}
