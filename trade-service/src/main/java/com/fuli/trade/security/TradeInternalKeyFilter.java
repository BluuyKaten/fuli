package com.fuli.trade.security;

import com.fuli.common.api.config.InternalKeyProperties;
import com.fuli.common.api.security.InternalServiceFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TradeInternalKeyFilter extends InternalServiceFilter {
    public TradeInternalKeyFilter(InternalKeyProperties properties) {
        super(properties, "/trade/internal");
    }
}
