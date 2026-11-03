package com.fuli.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;

@Slf4j
@Configuration
public class GatewayRouteConfig {

    @PostConstruct
    public void init() {
        log.info("GatewayRouteConfig loaded!");
    }

    /**
     * 路由配置。
     *
     * <p>默认通过服务发现路由，local profile 使用 fuli.routes.* 覆盖为直连地址。
     * 路由只在此处声明，避免与 YAML 中的同名路由重复注册。
     */
    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder, Environment environment) {
        log.info("Creating customRouteLocator bean");
        return builder.routes()
                .route("auth-service", r -> r.path("/api/auth/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri(environment.getProperty("fuli.routes.auth", "lb://auth-service")))
                .route("trade-service", r -> r.path("/api/trade/**", "/api/stock/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri(environment.getProperty("fuli.routes.trade", "lb://trade-service")))
                .route("analysis-service", r -> r.path("/api/analysis/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri(environment.getProperty("fuli.routes.analysis", "lb://analysis-service")))
                .route("data-service", r -> r.path("/api/data/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri(environment.getProperty("fuli.routes.data", "lb://data-service")))
                .build();
    }
}
