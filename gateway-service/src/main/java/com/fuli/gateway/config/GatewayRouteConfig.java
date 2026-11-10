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
                // 多市场 K 线统一契约（ADR-0007）。与 /api/data/** 分开，是为了让
                // 「新契约」和「旧 Feign 端点」在路由层就能区分：迁完旧端点即可下线，不必翻 controller。
                .route("market", r -> r.path("/api/market/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri(environment.getProperty("fuli.routes.data", "lb://data-service")))
                // MCP 端点预留（ADR-0009 决策 2/8）：server 尚未实现，但路由先纳管，
                // 保证将来注册 Spring AI MCP server 时，请求必经 JwtGlobalFilter 校验，
                // 不会以「默认未鉴权 JSON-RPC」的形态直接暴露。stripPrefix(1) 后落到 /okx/mcp/**，
                // 与 data-service 的 OkxAuthPaths 保护清单一致。
                .route("okx-mcp", r -> r.path("/api/okx/mcp/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri(environment.getProperty("fuli.routes.data", "lb://data-service")))
                // Agent 编排层（P9）：第 7 个模块，经网关纳入 JwtGlobalFilter
                .route("ai-service", r -> r.path("/api/ai/**")
                        .filters(f -> f.stripPrefix(1))
                        .uri(environment.getProperty("fuli.routes.ai", "lb://ai-service")))
                .build();
    }
}
