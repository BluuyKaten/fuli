package com.fuli.auth.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CORS 配置：允许前端跨域访问。
 *
 * <p>开发环境允许所有来源，生产环境应限制为具体域名。
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 允许所有来源（生产环境应改为具体域名）
        config.addAllowedOriginPattern("*");
        config.addAllowedHeader("*");
        config.addAllowedMethod("*");
        config.setAllowCredentials(true);
        config.addExposedHeader("Authorization");
        // 预检请求缓存时间（秒）
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Integer.MIN_VALUE); // 确保 CORS 过滤器最先执行
        return bean;
    }

    /** 处理 CORS 预检请求（OPTIONS） */
    @Bean
    public FilterRegistrationBean<Filter> corsPreflightFilter() {
        Filter preflightFilter = (request, response, chain) -> {
            HttpServletRequest req = (HttpServletRequest) request;
            HttpServletResponse res = (HttpServletResponse) response;
            if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
                res.setStatus(HttpStatus.OK.value());
                return;
            }
            chain.doFilter(request, response);
        };
        FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<>(preflightFilter);
        bean.addUrlPatterns("/*");
        bean.setOrder(Integer.MIN_VALUE + 1);
        return bean;
    }
}
