package com.fuli.analysis.security;

import com.fuli.common.api.config.JwtProperties;
import com.fuli.common.api.security.BearerUserFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AnalysisAuthenticationFilter extends BearerUserFilter {
    public AnalysisAuthenticationFilter(JwtProperties properties) {
        super(properties);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = com.fuli.common.api.security.RequestPaths.applicationPath(request);
        return !(path.equals("/analysis") || path.startsWith("/analysis/"));
    }
}
