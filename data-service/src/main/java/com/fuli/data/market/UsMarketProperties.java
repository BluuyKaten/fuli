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
 *     source: tencent        # tencent（默认，前复权）/ eastmoney（原始价 + 因子）
 *     history-years: 10      # 同步往回补多少年
 *     proxy:                 # 可选
 *     lot-size: 1            # 美股 1 股起
 *     tick-size: 0.01        # 美股最小变动价位
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "us.market")
public class UsMarketProperties {

    /**
     * 数据源：{@code tencent} 或 {@code eastmoney}。
     *
     * <p>默认腾讯，因为东财 K 线主机在部分网络下连不上（见 ADR-0019）。两个源口径不同
     * （腾讯只有前复权、东财是原始价 + 因子），**切换源后必须重新同步**——同步本身是
     * 先删后插，所以下一次同步就会把整段历史换成新口径。
     */
    private String source = "tencent";

    /** 同步往回补多少年（腾讯单次 1200 根约 4.7 年，超过会翻页）。 */
    private int historyYears = 10;

    /** 代理地址，形如 {@code 127.0.0.1:7897}；国内直连通常留空。 */
    private String proxy;

    /** 美股最小下单量（1 股）。 */
    private BigDecimal lotSize = BigDecimal.ONE;

    /** 美股最小变动价位（0.01 美元）。 */
    private BigDecimal tickSize = new BigDecimal("0.01");
}
