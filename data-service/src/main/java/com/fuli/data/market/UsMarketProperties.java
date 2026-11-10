package com.fuli.data.market;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * 美股行情数据源配置，绑定 {@code us.market.*}。
 *
 * <pre>
 * us:
 *   market:
 *     proxy:                # 可选，东财国内直连通常不需要
 *     lot-size: 1           # 美股 1 股起
 *     tick-size: 0.01       # 美股最小变动价位
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "us.market")
public class UsMarketProperties {

    /** 代理地址，形如 {@code 127.0.0.1:7897}；东财国内直连，通常留空。 */
    private String proxy;

    /** 美股最小下单量（1 股）。 */
    private BigDecimal lotSize = BigDecimal.ONE;

    /** 美股最小变动价位（0.01 美元）。 */
    private BigDecimal tickSize = new BigDecimal("0.01");
}
