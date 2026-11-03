package com.fuli.trade.security;

import com.fuli.common.api.config.JwtProperties;
import com.fuli.common.api.security.BearerUserFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TradeAuthenticationFilter extends BearerUserFilter {
    public TradeAuthenticationFilter(JwtProperties properties) {
        super(properties);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = com.fuli.common.api.security.RequestPaths.applicationPath(request);
        return path.equals("/trade/internal") || path.startsWith("/trade/internal/")
                || !(path.equals("/trade") || path.startsWith("/trade/")
                || path.equals("/stock/drawing") || path.equals("/stock/holding")
                || path.equals("/stock/available-quantity") || path.equals("/stock/fix-holding"));
    }
}
