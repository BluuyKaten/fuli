package com.fuli.data.risk;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

/**
 * 风控配置，绑定 {@code okx.risk.*}（阶段 3.4 四道风控）。
 *
 * <pre>
 * okx:
 *   risk:
 *     enabled: true
 *     single-order-max-notional: 1000   # 单笔金额上限（USDT）
 *     daily-max-notional: 5000          # 日累计金额上限（USDT）
 *     max-drawdown: 0.20                # 最大回撤阈值（20%）
 *     kill-switch: false                # 全局 kill switch（紧急熔断）
 * </pre>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "okx.risk")
public class RiskControlProperties {

    /** 风控总开关；关闭时跳过全部风控检查。 */
    private boolean enabled = true;

    /** 单笔金额上限（计价币，如 USDT）；null/0 = 不限制。 */
    private BigDecimal singleOrderMaxNotional = new BigDecimal("1000");

    /** 日累计金额上限；null/0 = 不限制。 */
    private BigDecimal dailyMaxNotional = new BigDecimal("5000");

    /** 最大回撤阈值（比例，0.20 = 20%）；null/≤0 = 不熔断。 */
    private BigDecimal maxDrawdown = new BigDecimal("0.20");

    /** 全局 kill switch：true 时拒绝一切下单（紧急熔断，独立于 okx.trade-enabled 功能总闸）。 */
    private boolean killSwitch = false;
}
