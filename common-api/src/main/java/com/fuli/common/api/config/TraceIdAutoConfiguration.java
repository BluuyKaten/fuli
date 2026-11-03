package com.fuli.common.api.config;

import com.fuli.common.api.filter.TraceIdFilter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 请求追踪过滤器按需自动装配。
 *
 * <p>仅作用于 servlet 应用（auth/trade/data/analysis）；网关为 WebFlux，
 * 在 {@code JwtGlobalFilter} 中自行生成/透传 {@code X-Request-Id}，无需注册 servlet 过滤器。
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class TraceIdAutoConfiguration {

    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilterRegistration() {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>(new TraceIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
