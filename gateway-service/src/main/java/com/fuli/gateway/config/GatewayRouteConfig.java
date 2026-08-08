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
     * <p>
     * 默认使用 {@code lb://服务名} 走 Nacos 服务发现 + 客户端负载均衡，适用于生产 / 多实例部署。
     * 本地开发时可通过 {@code spring.profiles.active=local} 切换到 application-local.yml，
     * 直连 localhost 各端口，避免多实例端口冲突。
     */
    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        log.info("Creating customRouteLocator bean");
        return builder.routes()
                .route("auth-service", r -> r.path("/api/auth/**", "/api/watchlist/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://auth-service"))
                .route("trade-service", r -> r.path("/api/trade/**", "/api/stock/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://trade-service"))
                .route("analysis-service", r -> r.path("/api/analysis/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://analysis-service"))
                .route("data-service", r -> r.path("/api/data/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://data-service"))
                .build();
    }
}
