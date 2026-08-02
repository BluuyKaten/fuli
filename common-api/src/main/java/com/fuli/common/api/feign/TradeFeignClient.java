package com.fuli.common.api.feign;

import com.fuli.common.api.Result;
import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.dto.TradeQueryDTO;
import com.fuli.common.api.vo.StatisticsVO;
import com.fuli.common.api.vo.TradeVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "trade-service", contextId = "tradeFeignClient")
public interface TradeFeignClient {

    @PostMapping("/trade/list")
    Result<List<TradeVO>> listTrades(@RequestBody TradeQueryDTO queryDTO);

    @GetMapping("/trade/{id}")
    Result<TradeVO> getTradeById(@PathVariable("id") Long id);

    @GetMapping("/trade/user/{userId}")
    Result<List<TradeVO>> getTradesByUserId(@PathVariable("userId") Long userId);

    @PostMapping("/trade/statistics")
    Result<StatisticsVO> getStatistics(@RequestBody TradeQueryDTO queryDTO);

    @PostMapping("/trade/queryByCondition")
    Result<List<TradeVO>> queryByCondition(@RequestBody TradeQueryDTO queryDTO);

    @DeleteMapping("/trade/internal/clearAll")
    Result<Boolean> clearAll(@RequestParam("userId") Long userId);
}
