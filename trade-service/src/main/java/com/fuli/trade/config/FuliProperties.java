package com.fuli.trade.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "fuli")
public class FuliProperties {

    /**
     * 资金校验失败策略: reject(默认,拒绝交易) / allow(允许交易)
     */
    private String cashValidationFailStrategy = "reject";
}
