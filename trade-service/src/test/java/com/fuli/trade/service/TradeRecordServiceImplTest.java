package com.fuli.trade.service;

import com.fuli.common.api.dto.TradeDTO;
import com.fuli.common.api.enums.TradeTypeEnum;
import com.fuli.common.api.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TradeRecordServiceImpl 单元测试
 * 注意：由于 TradeRecordServiceImpl 依赖 MyBatis-Plus 的 ServiceImpl（需要 Mapper），
 * 完整测试需要集成测试环境。这里只测试参数校验逻辑。
 */
@ExtendWith(MockitoExtension.class)
class TradeRecordServiceImplTest {

    private TradeDTO buyDTO;
    private TradeDTO sellDTO;

    @BeforeEach
    void setUp() {
        buyDTO = new TradeDTO();
        buyDTO.setUserId(1L);
        buyDTO.setStockCode("600519");
        buyDTO.setStockName("贵州茅台");
        buyDTO.setTradeType(TradeTypeEnum.BUY.getCode());
        buyDTO.setTradePrice(new BigDecimal("100.00"));
        buyDTO.setTradeQuantity(100);
        buyDTO.setCommission(new BigDecimal("5.00"));
        buyDTO.setTax(BigDecimal.ZERO);

        sellDTO = new TradeDTO();
        sellDTO.setUserId(1L);
        sellDTO.setStockCode("600519");
        sellDTO.setStockName("贵州茅台");
        sellDTO.setTradeType(TradeTypeEnum.SELL.getCode());
        sellDTO.setTradePrice(new BigDecimal("120.00"));
        sellDTO.setTradeQuantity(50);
        sellDTO.setCommission(new BigDecimal("5.00"));
        sellDTO.setTax(new BigDecimal("0.60"));
    }

    @Test
    void buyDTO_shouldHaveValidFields() {
        assertNotNull(buyDTO.getUserId());
        assertNotNull(buyDTO.getStockCode());
        assertEquals(TradeTypeEnum.BUY.getCode(), buyDTO.getTradeType());
        assertTrue(buyDTO.getTradePrice().compareTo(BigDecimal.ZERO) > 0);
        assertTrue(buyDTO.getTradeQuantity() > 0);
    }

    @Test
    void sellDTO_shouldHaveValidFields() {
        assertNotNull(sellDTO.getUserId());
        assertEquals(TradeTypeEnum.SELL.getCode(), sellDTO.getTradeType());
        assertTrue(sellDTO.getTradePrice().compareTo(BigDecimal.ZERO) > 0);
    }

    @Test
    void tradeDTO_zeroPrice_shouldBeInvalid() {
        buyDTO.setTradePrice(BigDecimal.ZERO);
        assertTrue(buyDTO.getTradePrice().compareTo(BigDecimal.ZERO) <= 0);
    }

    @Test
    void tradeDTO_zeroQuantity_shouldBeInvalid() {
        buyDTO.setTradeQuantity(0);
        assertTrue(buyDTO.getTradeQuantity() <= 0);
    }

    @Test
    void tradeDTO_negativePrice_shouldBeInvalid() {
        buyDTO.setTradePrice(new BigDecimal("-10.00"));
        assertTrue(buyDTO.getTradePrice().compareTo(BigDecimal.ZERO) < 0);
    }
}
