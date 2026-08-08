package com.fuli.gateway.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
     * <p>默认使用 {@code http://localhost:8081/8082/...} 直连本地服务，
     * 适用于开发环境。生产环境可配合 Nacos 替换为 {@code lb://服务名} 负载均衡。
     */
    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        log.info("Creating customRouteLocator bean");
        return builder.routes()
                .route("auth-service", r -> r.path("/api/auth/**", "/api/watchlist/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("http://localhost:8081"))
                .route("trade-service", r -> r.path("/api/trade/**", "/api/stock/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("http://localhost:8082"))
                .route("analysis-service", r -> r.path("/api/analysis/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("http://localhost:8083"))
                .route("data-service", r -> r.path("/api/data/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("http://localhost:8084"))
                .build();
    }
}
