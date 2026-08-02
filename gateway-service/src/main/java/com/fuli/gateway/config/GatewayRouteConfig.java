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

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        log.info("Creating customRouteLocator bean");
        return builder.routes()
                .route("auth-service", r -> r.path("/api/auth/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://auth-service"))
                .route("trade-service", r -> r.path("/api/trade/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://trade-service"))
                .route("analysis-service", r -> r.path("/api/analysis/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://analysis-service"))
                .route("stock-service", r -> r.path("/api/stock/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://trade-service"))
                .route("data-service", r -> r.path("/api/data/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri("lb://data-service"))
                .build();
    }
}
