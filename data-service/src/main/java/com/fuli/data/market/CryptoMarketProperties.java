package com.fuli.data.market;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * 加密行情数据源配置，绑定 {@code crypto.market.*}。
 *
 * <pre>
 * crypto:
 *   market:
 *     proxy: 127.0.0.1:7897      # 可选，国内访问 Binance/Gate 需代理
 *     binance-enabled: true      # 主源开关
 *     gate-enabled: true         # 备用源开关
 *     price-scale: 8             # 注册 instrument 用的默认价格精度
 *     lot-size: 0.00001          # 默认最小下单量
 *     tick-size: 0.01            # 默认最小变动价位
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "crypto.market")
public class CryptoMarketProperties {

    /** 代理地址，形如 {@code 127.0.0.1:7897}，可带 http:// 前缀；为空则直连。 */
    private String proxy;

    /** 主源 Binance Vision 开关。 */
    private boolean binanceEnabled = true;

    /** 备用源 Gate 开关。 */
    private boolean gateEnabled = true;

    /** 注册 instrument 时用的默认价格精度。 */
    private int priceScale = 8;

    /** 注册 instrument 时用的默认最小下单量。 */
    private BigDecimal lotSize = new BigDecimal("0.00001");

    /** 注册 instrument 时用的默认最小变动价位。 */
    private BigDecimal tickSize = new BigDecimal("0.01");
}
