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
import java.util.Map;

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

    /**
     * 获取股票最新行情（含收盘价、前收盘价）
     */
    @GetMapping("/stock/latest-price")
    Result<Map<String, Object>> getLatestPrice(@RequestParam("stockCode") String stockCode);

    /**
     * 获取股票基础信息
     */
    @GetMapping("/stock/info")
    Result<Map<String, Object>> getStockInfo(@RequestParam("stockCode") String stockCode);

    /**
     * 获取股票日线数据（用于计算资产曲线）
     */
    @GetMapping("/stock/daily")
    Result<List<Map<String, Object>>> getStockDaily(@RequestParam("stockCode") String stockCode,
                                                     @RequestParam(value = "startDate", required = false) String startDate,
                                                     @RequestParam(value = "endDate", required = false) String endDate);

    /**
     * 批量获取股票最新行情（含收盘价、前收盘价），消除 N+1 查询。
     * @param stockCodes 股票代码列表（逗号分隔）
     * @return key=stockCode, value=行情数据 Map
     */
    @GetMapping("/stock/latest-prices")
    Result<Map<String, Map<String, Object>>> getLatestPrices(@RequestParam("stockCodes") String stockCodes);

    /**
     * 批量获取股票基础信息，消除 N+1 查询。
     * @param stockCodes 股票代码列表（逗号分隔）
     * @return key=stockCode, value=股票信息 Map
     */
    @GetMapping("/stock/infos")
    Result<Map<String, Map<String, Object>>> getStockInfos(@RequestParam("stockCodes") String stockCodes);
}
