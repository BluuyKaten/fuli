package com.fuli.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayRouteConfigTest {
    @Test
    void defaultRoutesUseServiceDiscoveryAndLocalOverridesUseDirectUrls() {
        MockEnvironment environment = new MockEnvironment();
        assertEquals(Map.of("auth-service", "lb://auth-service", "trade-service", "lb://trade-service",
                "analysis-service", "lb://analysis-service", "data-service", "lb://data-service"), routes(environment));

        environment.withProperty("fuli.routes.auth", "http://localhost:8081")
                .withProperty("fuli.routes.trade", "http://localhost:8082")
                .withProperty("fuli.routes.analysis", "http://localhost:8083")
                .withProperty("fuli.routes.data", "http://localhost:8084");
        assertEquals(Map.of("auth-service", "http://localhost:8081", "trade-service", "http://localhost:8082",
                "analysis-service", "http://localhost:8083", "data-service", "http://localhost:8084"), routes(environment));
    }

    private Map<String, String> routes(MockEnvironment environment) {
        try (StaticApplicationContext context = new StaticApplicationContext()) {
            context.getBeanFactory().registerSingleton("pathRoutePredicateFactory",
                    new org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory(
                            new org.springframework.boot.webflux.autoconfigure.WebFluxProperties()));
            context.getBeanFactory().registerSingleton("stripPrefixGatewayFilterFactory",
                    new org.springframework.cloud.gateway.filter.factory.StripPrefixGatewayFilterFactory());
            RouteLocator locator = new GatewayRouteConfig().customRouteLocator(new RouteLocatorBuilder(context), environment);
            return locator.getRoutes().collectList().block().stream()
                    .collect(Collectors.toMap(route -> route.getId(), route -> route.getUri().toString()));
        }
    }
}
