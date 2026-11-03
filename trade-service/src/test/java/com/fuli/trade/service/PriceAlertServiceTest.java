package com.fuli.trade.service;

import com.fuli.trade.dto.PriceAlertDTO;
import com.fuli.trade.dto.PriceAlertVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spring.cloud.nacos.config.enabled=false", "spring.cloud.nacos.discovery.enabled=false"})
@ActiveProfiles("test")
@Transactional
class PriceAlertServiceTest {

    @Autowired
    private PriceAlertService priceAlertService;

    @MockitoBean
    private StockService stockService;

    @Test
    void testPriceAlertLifecycle() {
        Long userId = 2001L;

        // Mock stock 批量行情（PriceAlertServiceImpl 去重后走批量接口）
        Map<String, Object> quote = new HashMap<>();
        quote.put("stockCode", "600519.SH");
        quote.put("closePrice", new BigDecimal("1850.00"));
        quote.put("preClose", new BigDecimal("1800.00"));
        when(stockService.getLatestPrices("600519.SH")).thenReturn(Map.of("600519.SH", quote));

        // 1. Create Take-Profit Alert (target: 1800.00 <= current 1850.00, should trigger!)
        PriceAlertDTO dto = new PriceAlertDTO();
        dto.setStockCode("600519.SH");
        dto.setStockName("贵州茅台");
        dto.setAlertType(1); // 价格上限 / 止盈
        dto.setTargetValue(new BigDecimal("1800.00"));
        dto.setNotes("到达1800减仓止盈");

        Long id = priceAlertService.createAlert(dto, userId);
        assertNotNull(id);

        // 2. List
        List<PriceAlertVO> list = priceAlertService.listAlerts(userId, null);
        assertEquals(1, list.size());
        assertEquals(0, list.get(0).getStatus()); // 监控中

        // 3. Check & Trigger
        List<PriceAlertVO> triggered = priceAlertService.checkAndTriggerAlerts(userId);
        assertEquals(1, triggered.size());
        assertEquals(id, triggered.get(0).getId());
        assertEquals(1, triggered.get(0).getStatus()); // 已触发

        // 4. Toggle Status to 2 (disabled)
        boolean toggled = priceAlertService.toggleAlertStatus(id, 2, userId);
        assertTrue(toggled);

        // 5. Delete
        boolean deleted = priceAlertService.deleteAlert(id, userId);
        assertTrue(deleted);
    }
}
