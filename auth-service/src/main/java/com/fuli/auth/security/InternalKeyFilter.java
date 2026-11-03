package com.fuli.auth.security;

import com.fuli.common.api.config.InternalKeyProperties;
import com.fuli.common.api.security.InternalServiceFilter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Authenticates service-only calls without logging caller credentials. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class InternalKeyFilter extends InternalServiceFilter {
    public InternalKeyFilter(InternalKeyProperties properties) {
        super(properties, "/auth/internal");
    }
}
